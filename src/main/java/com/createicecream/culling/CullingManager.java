package com.createicecream.culling;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.createicecream.compat.ModCompat;
import com.createicecream.config.OptConfig;
import com.createicecream.debug.OptStats;
import com.createicecream.mixin.minecraft.LevelRendererAccessor;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Coordinates the asynchronous occlusion culling.
 *
 * <pre>
 *  render thread / Flywheel workers           client tick (main thread)             "IceCream-Culling" thread
 *  ---------------------------------          --------------------------            ---------------------------
 *  markSeen(be) --queue-->                    drain queue into 'tracked'
 *                                             compute render/cull boxes (safe:
 *                                             renderer + BE read on main thread)
 *                                             publish snapshot array    --------->  trace every tracked box from
 *  camera position (per frame)  ----------------------------------------------->    the camera, write be.occluded
 *  read be.occluded  <-------------------------------------------------------------
 * </pre>
 * The render thread never waits for the culling thread; it only reads one volatile boolean.
 */
public final class CullingManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("CreateIceCream/Culling");

    /** Block entities not rendered/updated for this many client ticks are dropped from tracking. */
    private static final int STALE_TICKS = 60;
    /** Render boxes are recomputed at this interval (ticks); staggered across entries. */
    private static final int BOX_REFRESH_TICKS = 20;
    /** After (re)creation or a state update, a visual is updated unconditionally for this many frames. */
    public static final int FORCE_FRAMES = 3;

    private static final ConcurrentLinkedQueue<BlockEntity> PENDING = new ConcurrentLinkedQueue<>();
    private static final ArrayList<BlockEntity> TRACKED = new ArrayList<>();

    // published state (read by other threads)
    private static volatile BlockEntity[] snapshot = new BlockEntity[0];
    private static volatile ClientLevel activeLevel;
    private static volatile double camX, camY, camZ;
    private static volatile boolean passRequested;
    private static volatile int clientTick;

    private static boolean snapshotDirty;
    private static Thread thread;

    private CullingManager() {
    }

    // ------------------------------------------------------------------------------------------------
    // Hooks (any thread)
    // ------------------------------------------------------------------------------------------------

    /** Current client tick counter (for "last seen" bookkeeping). */
    public static int clientTick() {
        return clientTick;
    }

    /** The level all culling state refers to; hooks ignore block entities of any other level (Ponder, contraptions, schematics). */
    @Nullable
    public static ClientLevel activeLevel() {
        return activeLevel;
    }

    /**
     * Registers a block entity as "currently being drawn". Cheap: one volatile write per call, one queue
     * insertion the first time.
     */
    public static void markSeen(BlockEntity be, CullableBlockEntity state) {
        state.icecream$setLastSeen(clientTick);
        if (!state.icecream$isQueued()) {
            state.icecream$setQueued(true);
            PENDING.add(be);
        }
    }

    /** Whether culling results should currently be honoured at all. */
    public static boolean occlusionActive() {
        return OptConfig.enabled && OptConfig.occlusionEnabled && !ModCompat.isRenderingShadowPass();
    }

    /** Whether Create BERs should be skipped when occluded (see {@link OptConfig.BerCullingMode}). */
    public static boolean berCullingActive() {
        if (!occlusionActive()) {
            return false;
        }
        return switch (OptConfig.berCullingMode) {
            case ON -> true;
            case OFF -> false;
            case AUTO -> !ModCompat.isEntityCullingLoaded();
        };
    }

    /**
     * Called from the Create BER hook on the render thread.
     *
     * @return true if rendering of this block entity should be skipped this frame
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean shouldSkipBer(BlockEntity be, BlockEntityRenderer renderer) {
        if (!OptConfig.enabled) {
            return false;
        }
        Level level = be.getLevel();
        if (level == null || level != activeLevel) {
            return false; // Ponder scenes, contraption/schematic virtual levels, etc.
        }
        if (ModCompat.isRenderingShadowPass()) {
            // Shadow map: never occlusion-cull (hidden machines still cast shadows), but drop far ones.
            BlockPos pos = be.getBlockPos();
            if (isBeyondShadowDistance(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)) {
                OptStats.SHADOW_BER_SKIPPED.increment();
                return true;
            }
            return false;
        }
        if (OptConfig.berFrustumCulling && isOutsideFrustum(be, renderer)) {
            OptStats.BER_FRUSTUM.increment();
            return true;
        }
        CullableBlockEntity state = (CullableBlockEntity) be;
        boolean berCulling = berCullingActive();
        // Only trace what someone uses: BER culling, or Flywheel visuals (when Entity Culling already culls
        // BERs and Flywheel is off - i.e. with shaders - tracing every machine would be wasted work).
        if (berCulling || trackForVisuals) {
            markSeen(be, state);
        }
        if (berCulling && state.icecream$isOccluded()) {
            OptStats.BER_CULLED.increment();
            return true;
        }
        OptStats.BER_RENDERED.increment();
        return false;
    }

    /**
     * Frustum test with the renderer's own bounding box, i.e. exactly what NeoForge does in the vanilla
     * block entity loop (which Sodium replaces). Off-screen renderers are only tested when their box is on
     * the trusted list, because some of them draw far outside a unit box (ejector arcs, elevator ropes...).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean isOutsideFrustum(BlockEntity be, BlockEntityRenderer renderer) {
        try {
            if (renderer.shouldRenderOffScreen(be) && !offscreenTrusted(be)) {
                return false;
            }
            LevelRendererAccessor lr = (LevelRendererAccessor) Minecraft.getInstance().levelRenderer;
            Frustum frustum = lr.icecream$getCapturedFrustum();
            if (frustum == null) {
                frustum = lr.icecream$getCullingFrustum();
            }
            if (frustum == null) {
                return false;
            }
            AABB box = renderer.getRenderBoundingBox(be);
            if (box == null || box.isInfinite()) {
                return false;
            }
            return !frustum.isVisible(box);
        } catch (Throwable t) {
            return false; // never hide something because of an unexpected renderer
        }
    }

    private static final java.util.IdentityHashMap<Object, Boolean> OFFSCREEN_TRUST = new java.util.IdentityHashMap<>();
    private static int offscreenGeneration = -1;
    private static volatile boolean trackForVisuals = true;

    /** Render thread; cached per block entity type (no registry lookup / string per frame). */
    private static boolean offscreenTrusted(BlockEntity be) {
        if (offscreenGeneration != OptConfig.generation) {
            offscreenGeneration = OptConfig.generation;
            OFFSCREEN_TRUST.clear();
        }
        Boolean trusted = OFFSCREEN_TRUST.get(be.getType());
        if (trusted == null) {
            trusted = OptConfig.offscreenAllow.contains(typeKey(be));
            OFFSCREEN_TRUST.put(be.getType(), trusted);
        }
        return trusted;
    }

    private static String typeKey(BlockEntity be) {
        ResourceLocation id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
        return id == null ? "" : id.toString();
    }

    /**
     * True if shadows are distance-limited and the point is farther than the limit from the camera.
     * Only meaningful while a shader shadow pass is running.
     */
    public static boolean isBeyondShadowDistance(double x, double y, double z) {
        double max = OptConfig.shadowMaxDistanceSq;
        if (max <= 0) {
            return false;
        }
        double dx = x - camX, dy = y - camY, dz = z - camZ;
        return dx * dx + dy * dy + dz * dz > max;
    }

    /** Squared distance from this frame's camera to a point. */
    public static double distanceSq(double x, double y, double z) {
        double dx = x - camX, dy = y - camY, dz = z - camZ;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Squared distance from this frame's camera to the nearest point of a box (0 inside). */
    public static double distanceSq(AABB box) {
        double dx = Math.max(0, Math.max(box.minX - camX, camX - box.maxX));
        double dy = Math.max(0, Math.max(box.minY - camY, camY - box.maxY));
        double dz = Math.max(0, Math.max(box.minZ - camZ, camZ - box.maxZ));
        return dx * dx + dy * dy + dz * dz;
    }

    /** Box variant: distance from the camera to the nearest point of the box. */
    public static boolean isBeyondShadowDistance(AABB box) {
        double max = OptConfig.shadowMaxDistanceSq;
        if (max <= 0 || box == null || box.isInfinite()) {
            return false;
        }
        double dx = Math.max(0, Math.max(box.minX - camX, camX - box.maxX));
        double dy = Math.max(0, Math.max(box.minY - camY, camY - box.maxY));
        double dz = Math.max(0, Math.max(box.minZ - camZ, camZ - box.maxZ));
        return dx * dx + dy * dy + dz * dz > max;
    }

    /** Called when Flywheel creates or updates the visual of a block entity. */
    public static void onVisualChanged(Object obj) {
        if (obj instanceof BlockEntity be) {
            ((CullableBlockEntity) be).icecream$setForceFrames(FORCE_FRAMES);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Render thread
    // ------------------------------------------------------------------------------------------------

    /** Once per frame, before the level is rendered. */
    public static void onRenderFrame() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        // Position of the camera used for the previous frame; one frame of latency is irrelevant here.
        boolean flywheelOn;
        try {
            flywheelOn = dev.engine_room.flywheel.api.backend.BackendManager.isBackendOn();
        } catch (Throwable t) {
            flywheelOn = false;
        }
        trackForVisuals = OptConfig.occlusionForVisuals && flywheelOn;
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 pos = camera.getPosition();
        camX = pos.x;
        camY = pos.y;
        camZ = pos.z;
    }

    // ------------------------------------------------------------------------------------------------
    // Client tick (main thread)
    // ------------------------------------------------------------------------------------------------

    public static void onClientTick() {
        clientTick++;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;

        if (level != activeLevel) {
            resetAll();
            activeLevel = level;
        }
        if (level == null || !OptConfig.enabled) {
            if (!TRACKED.isEmpty() || !PENDING.isEmpty()) {
                resetAll();
            }
            return;
        }
        ensureThread();

        // 1. adopt newly seen block entities
        BlockEntity be;
        while ((be = PENDING.poll()) != null) {
            CullableBlockEntity state = (CullableBlockEntity) be;
            if (state.icecream$isListed()) {
                continue;
            }
            if (be.isRemoved() || be.getLevel() != level) {
                state.icecream$setQueued(false);
                continue;
            }
            state.icecream$setListed(true);
            TRACKED.add(be);
            refreshBoxes(be, state);
            snapshotDirty = true;
        }

        // 2. drop stale entries, refresh boxes (staggered)
        BlockEntityRenderDispatcher dispatcher = mc.getBlockEntityRenderDispatcher();
        int now = clientTick;
        for (int i = TRACKED.size() - 1; i >= 0; i--) {
            BlockEntity e = TRACKED.get(i);
            CullableBlockEntity state = (CullableBlockEntity) e;
            if (e.isRemoved() || e.getLevel() != level || now - state.icecream$getLastSeen() > STALE_TICKS) {
                untrack(state);
                int last = TRACKED.size() - 1;
                TRACKED.set(i, TRACKED.get(last));
                TRACKED.remove(last);
                snapshotDirty = true;
                continue;
            }
            if (((now + i) % BOX_REFRESH_TICKS) == 0) {
                refreshBoxes(e, state, dispatcher);
            }
        }

        ContraptionCulling.tick(level, now);

        if (snapshotDirty) {
            snapshotDirty = false;
            snapshot = TRACKED.toArray(new BlockEntity[0]);
        }
        OptStats.tracked = TRACKED.size();

        // blocks may have changed even if the camera didn't move
        passRequested = true;
        Thread t = thread;
        if (t != null) {
            LockSupport.unpark(t);
        }
    }

    private static void refreshBoxes(BlockEntity be, CullableBlockEntity state) {
        refreshBoxes(be, state, Minecraft.getInstance().getBlockEntityRenderDispatcher());
    }

    private static void refreshBoxes(BlockEntity be, CullableBlockEntity state, BlockEntityRenderDispatcher dispatcher) {
        AABB box;
        boolean offscreen = false;
        try {
            BlockEntityRenderer<BlockEntity> renderer = dispatcher.getRenderer(be);
            if (renderer != null) {
                box = renderer.getRenderBoundingBox(be);
                offscreen = renderer.shouldRenderOffScreen(be);
            } else {
                box = new AABB(be.getBlockPos());
            }
        } catch (Throwable t) {
            box = null; // be defensive with third-party renderers
        }

        double limit = OptConfig.occlusionMaxBoxSize;
        boolean bounded = box != null && !box.isInfinite()
                && box.getXsize() <= limit && box.getYsize() <= limit && box.getZsize() <= limit;
        state.icecream$setRenderBox(bounded ? box : null);

        boolean cullable = bounded && !state.icecream$isNeverCull();
        if (cullable) {
            ResourceLocation id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
            String key = id == null ? "" : id.toString();
            if (OptConfig.occlusionBlacklist.contains(key)) {
                cullable = false;
            } else if (offscreen && !OptConfig.offscreenAllow.contains(key)) {
                cullable = false;
            }
        }
        state.icecream$setCullBox(cullable ? box : null);
        if (!cullable) {
            state.icecream$setOccluded(false);
        }
    }

    private static void untrack(CullableBlockEntity state) {
        state.icecream$setListed(false);
        state.icecream$setQueued(false);
        state.icecream$setOccluded(false);
        state.icecream$setCullBox(null);
        state.icecream$setRenderBox(null);
    }

    private static void resetAll() {
        for (BlockEntity be : TRACKED) {
            untrack((CullableBlockEntity) be);
        }
        TRACKED.clear();
        BlockEntity be;
        while ((be = PENDING.poll()) != null) {
            untrack((CullableBlockEntity) be);
        }
        snapshot = new BlockEntity[0];
        snapshotDirty = false;
        ContraptionCulling.reset();
        OptStats.tracked = 0;
        OptStats.occludedNow = 0;
    }

    /** Config changed: boxes depend on blacklist/limits, so recompute everything on the next ticks. */
    public static void onConfigChanged() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            BlockEntityRenderDispatcher dispatcher = mc.getBlockEntityRenderDispatcher();
            for (BlockEntity be : TRACKED) {
                CullableBlockEntity state = (CullableBlockEntity) be;
                refreshBoxes(be, state, dispatcher);
                if (!OptConfig.occlusionEnabled) {
                    state.icecream$setOccluded(false);
                }
            }
        });
    }

    // ------------------------------------------------------------------------------------------------
    // Culling thread
    // ------------------------------------------------------------------------------------------------

    private static synchronized void ensureThread() {
        if (thread != null && thread.isAlive()) {
            return;
        }
        Thread t = new Thread(CullingManager::runLoop, "IceCream-Culling");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.setUncaughtExceptionHandler((th, ex) -> LOGGER.error("Culling thread crashed", ex));
        thread = t;
        t.start();
    }

    private static void runLoop() {
        LevelOpacitySource opacity = new LevelOpacitySource();
        OcclusionRaycaster raycaster = new OcclusionRaycaster(opacity);
        double lastX = Double.NaN, lastY = Double.NaN, lastZ = Double.NaN;
        boolean loggedError = false;

        while (true) {
            LockSupport.parkNanos(OptConfig.occlusionThreadSleepMs * 1_000_000L);
            try {
                ClientLevel level = activeLevel;
                if (level == null || !OptConfig.enabled || !OptConfig.occlusionEnabled) {
                    lastX = Double.NaN;
                    continue;
                }
                double x = camX, y = camY, z = camZ;
                boolean moved = x != lastX || y != lastY || z != lastZ;
                if (!moved && !passRequested) {
                    continue;
                }
                passRequested = false;
                lastX = x;
                lastY = y;
                lastZ = z;

                long start = System.nanoTime();
                long raysBefore = raycaster.raysCast;
                opacity.begin(level, OptConfig.leavesOpaque);
                raycaster.setCamera(x, y, z);
                int occluded = runPass(snapshot, level, raycaster, x, y, z);
                if (activeLevel == level) {
                    ContraptionCulling.runPass(raycaster, x, y, z);
                }
                opacity.end();

                OptStats.occludedNow = occluded;
                OptStats.raysLastPass = raycaster.raysCast - raysBefore;
                OptStats.lastPassMs = (System.nanoTime() - start) / 1_000_000.0;
            } catch (Throwable t) {
                opacity.end();
                if (!loggedError) {
                    loggedError = true;
                    LOGGER.warn("Error during occlusion pass (will keep running, further errors are not logged)", t);
                }
            }
        }
    }

    private static int runPass(BlockEntity[] entries, ClientLevel level, OcclusionRaycaster raycaster,
                               double cx, double cy, double cz) {
        final double maxDistSq = OptConfig.occlusionMaxDistanceSq;
        final double pad = OptConfig.occlusionBoxPadding;
        final long hold = OptConfig.occlusionHysteresisNanos;
        int occludedCount = 0;

        for (BlockEntity be : entries) {
            if (activeLevel != level) {
                return occludedCount; // level changed mid-pass; results would be meaningless
            }
            CullableBlockEntity state = (CullableBlockEntity) be;
            AABB box = state.icecream$getCullBox();
            if (box == null) {
                state.icecream$setOccluded(false);
                continue;
            }
            double mx = (box.minX + box.maxX) * 0.5 - cx;
            double my = (box.minY + box.maxY) * 0.5 - cy;
            double mz = (box.minZ + box.maxZ) * 0.5 - cz;
            if (mx * mx + my * my + mz * mz > maxDistSq) {
                state.icecream$setOccluded(false);
                continue;
            }

            boolean visible;
            try {
                visible = raycaster.isBoxVisible(box.minX - pad, box.minY - pad, box.minZ - pad,
                        box.maxX + pad, box.maxY + pad, box.maxZ + pad);
            } catch (Throwable t) {
                visible = true; // racing a chunk update; try again next pass
            }

            long now = System.nanoTime();
            if (visible) {
                // 0 is reserved for "never seen visible"
                long until = now + hold;
                state.icecream$setVisibleUntil(until == 0 ? 1 : until);
                state.icecream$setOccluded(false);
            } else {
                long until = state.icecream$getVisibleUntil();
                // Re-check the (volatile) cull box: if the main thread untracked this entry while we were
                // tracing, don't resurrect a stale "occluded" flag.
                if ((until == 0 || now - until >= 0) && state.icecream$getCullBox() != null) {
                    state.icecream$setOccluded(true);
                    occludedCount++;
                }
            }
        }
        return occludedCount;
    }
}
