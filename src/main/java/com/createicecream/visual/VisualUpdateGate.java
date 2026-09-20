package com.createicecream.visual;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.createicecream.config.OptConfig;
import com.createicecream.culling.CullableBlockEntity;
import com.createicecream.culling.CullingManager;
import com.createicecream.debug.OptStats;
import com.createicecream.mixin.flywheel.AbstractBlockEntityVisualAccessor;

import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.lib.task.functional.ConsumerWithContext;
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Decides, per frame, whether a {@link SimpleDynamicVisual}'s {@code beginFrame} runs.
 * <p>
 * Flywheel runs {@code beginFrame} for every dynamic visual every frame, on its worker pool. Flywheel
 * offers {@code isVisible(frustum)} and a distance limiter to visuals, but in Create 6.0.10 only
 * {@code BlazeBurnerVisual} uses them. This gate applies the same idea to every Create visual from the
 * outside, plus occlusion:
 * <ol>
 *     <li><b>occluded</b> (async culling thread says so) -&gt; skip</li>
 *     <li><b>outside the frustum</b> (render box + padding) -&gt; skip</li>
 *     <li><b>far away</b> -&gt; update only every N-th frame so the animation runs at ~midFps/farFps.
 *     N adapts to the actual FPS and each block gets its own phase so work is spread across frames.</li>
 * </ol>
 * A skipped visual keeps its last instance transforms, so it simply shows the previous pose. The
 * instanced geometry itself is still drawn by Flywheel (and GPU-culled by the indirect backend).
 * <p>
 * Runs on Flywheel worker threads: everything here must be thread-safe and allocation-free.
 */
public final class VisualUpdateGate {
    /** Hard cap on the frame divisor, whatever the FPS. */
    private static final int MAX_DIVISOR = 20;

    private static final ConcurrentHashMap<Class<?>, ClassInfo> CLASS_INFO = new ConcurrentHashMap<>();
    private static volatile int classInfoGeneration = -1;

    // per-frame values, written on the render thread before Flywheel's frame plan starts
    private static volatile int frame;
    private static volatile int midDivisor = 1;
    private static volatile int farDivisor = 1;
    private static volatile int shadowDivisor = 1;
    /** Every visual is updated unconditionally until this frame (after Flywheel recreated all visuals). */
    private static volatile int forceAllUntilFrame = 0;

    /** Set once Flywheel has built a frame plan through our hook (proves the StorageMixin injection works). */
    private static volatile boolean hooked;

    private VisualUpdateGate() {
    }

    public static boolean isHooked() {
        return hooked;
    }

    /** Update every visual for the next {@code frames} frames. Any thread. */
    public static void forceAll(int frames) {
        forceAllUntilFrame = frame + frames + 1;
    }

    private record ClassInfo(boolean managed, boolean noSpatialGating) {
        static final ClassInfo UNMANAGED = new ClassInfo(false, false);
    }

    /** Wraps Flywheel's per-visual frame action. Installed by {@code StorageMixin}. */
    public static ConsumerWithContext<SimpleDynamicVisual, DynamicVisual.Context> wrap(
            ConsumerWithContext<SimpleDynamicVisual, DynamicVisual.Context> original) {
        hooked = true;
        return (visual, ctx) -> {
            if (shouldUpdate(visual, ctx)) {
                original.accept(visual, ctx);
            }
        };
    }

    /** Render thread, once per frame. */
    public static void onRenderFrame() {
        frame++;
        int fps = Math.max(1, Minecraft.getInstance().getFps());
        midDivisor = divisorFor(fps, OptConfig.animMidFps);
        farDivisor = divisorFor(fps, OptConfig.animFarFps);
        shadowDivisor = OptConfig.animEnabled ? divisorFor(fps, OptConfig.shadowAnimFps) : 1;
    }

    /**
     * Frame divisor for something at this squared distance from the camera: 1 = update every frame.
     * Shared by the Flywheel visual gate and the BER/contraption throttle (shader path).
     */
    public static int divisorForDistanceSq(double d2) {
        if (!OptConfig.animEnabled || d2 <= OptConfig.animFullRateDistanceSq) {
            return 1;
        }
        return d2 > OptConfig.animMidDistanceSq ? farDivisor : midDivisor;
    }

    /** Minimum divisor for everything in the shader shadow pass (Create machines and contraptions). */
    public static int shadowDivisor() {
        return shadowDivisor;
    }

    static int divisorFor(int fps, int targetFps) {
        if (targetFps <= 0 || fps <= targetFps) {
            return 1;
        }
        // round to nearest: 60 fps -> 30 target = 2, 144 -> 30 = 5, 45 -> 30 = 2 (22.5 Hz), 40 -> 30 = 1
        int d = (fps + targetFps / 2) / targetFps;
        return Math.max(1, Math.min(MAX_DIVISOR, d));
    }

    static boolean shouldUpdate(SimpleDynamicVisual visual, DynamicVisual.Context ctx) {
        if (!OptConfig.enabled) {
            return true;
        }
        if (!(visual instanceof AbstractBlockEntityVisual<?>)) {
            return true; // entity visuals (packages, ...) are left alone
        }
        ClassInfo info = classInfo(visual.getClass());
        if (!info.managed) {
            return true;
        }
        BlockEntity be = ((AbstractBlockEntityVisualAccessor) visual).icecream$getBlockEntity();
        if (be == null || be.getLevel() == null || be.getLevel() != CullingManager.activeLevel()) {
            return true; // Ponder / virtual levels
        }
        CullableBlockEntity state = (CullableBlockEntity) be;
        if (info.noSpatialGating && !state.icecream$isNeverCull()) {
            state.icecream$setNeverCull(true);
            state.icecream$setCullBox(null); // the next render-box refresh keeps it null
            state.icecream$setOccluded(false);
        }
        CullingManager.markSeen(be, state);

        // freshly created / updated visuals always get a few real frames
        if (frame - forceAllUntilFrame < 0) {
            OptStats.VISUAL_UPDATED.increment();
            return true;
        }
        int force = state.icecream$getForceFrames();
        if (force > 0) {
            state.icecream$setForceFrames(force - 1);
            OptStats.VISUAL_UPDATED.increment();
            return true;
        }

        if (!info.noSpatialGating) {
            // 1. occlusion
            if (OptConfig.occlusionForVisuals && CullingManager.occlusionActive() && state.icecream$isOccluded()) {
                OptStats.VISUAL_SKIP_OCCLUDED.increment();
                return false;
            }
            // 2. frustum
            if (OptConfig.frustumEnabled) {
                AABB box = state.icecream$getRenderBox();
                if (box != null && !inFrustum((AbstractBlockEntityVisual<?>) visual, be.getBlockPos(), box, ctx)) {
                    OptStats.VISUAL_SKIP_FRUSTUM.increment();
                    return false;
                }
            }
        }

        // 3. distance-based animation framerate
        if (OptConfig.animEnabled) {
            BlockPos pos = be.getBlockPos();
            Vec3 cam = ctx.camera().getPosition();
            double dx = pos.getX() + 0.5 - cam.x;
            double dy = pos.getY() + 0.5 - cam.y;
            double dz = pos.getZ() + 0.5 - cam.z;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > OptConfig.animFullRateDistanceSq) {
                int divisor = d2 > OptConfig.animMidDistanceSq ? farDivisor : midDivisor;
                if (divisor > 1 && Math.floorMod(frame + phase(pos), divisor) != 0) {
                    OptStats.VISUAL_SKIP_DISTANCE.increment();
                    return false;
                }
            }
        }

        OptStats.VISUAL_UPDATED.increment();
        return true;
    }

    /**
     * Sphere test in Flywheel's render-origin-relative space, like {@code AbstractBlockEntityVisual#isVisible}
     * but using the block entity's real render bounds plus padding.
     */
    private static boolean inFrustum(AbstractBlockEntityVisual<?> visual, BlockPos pos, AABB box, DynamicVisual.Context ctx) {
        BlockPos visualPos = visual.getVisualPosition();
        double ox = visualPos.getX() - pos.getX();
        double oy = visualPos.getY() - pos.getY();
        double oz = visualPos.getZ() - pos.getZ();
        double hx = box.getXsize() * 0.5, hy = box.getYsize() * 0.5, hz = box.getZsize() * 0.5;
        float radius = (float) (Math.sqrt(hx * hx + hy * hy + hz * hz) + OptConfig.frustumPadding);
        float cx = (float) ((box.minX + box.maxX) * 0.5 + ox);
        float cy = (float) ((box.minY + box.maxY) * 0.5 + oy);
        float cz = (float) ((box.minZ + box.maxZ) * 0.5 + oz);
        return ctx.frustum().testSphere(cx, cy, cz, radius);
    }

    /** Stable per-position phase so throttled visuals don't all update on the same frame. */
    private static int phase(BlockPos pos) {
        int h = pos.getX() * 73856093 ^ pos.getY() * 19349663 ^ pos.getZ() * 83492791;
        return (h ^ (h >>> 16)) & 0x7fffffff;
    }

    private static ClassInfo classInfo(Class<?> type) {
        int gen = OptConfig.generation;
        if (gen != classInfoGeneration) {
            CLASS_INFO.clear();
            classInfoGeneration = gen;
        }
        ClassInfo info = CLASS_INFO.get(type);
        if (info == null) {
            info = computeClassInfo(type);
            CLASS_INFO.put(type, info);
        }
        return info;
    }

    private static ClassInfo computeClassInfo(Class<?> type) {
        String name = type.getName();
        Set<String> ungated = OptConfig.ungatedClasses;
        Set<String> noFrustum = OptConfig.noFrustumClasses;

        boolean managed = false;
        List<String> packages = OptConfig.managedPackages;
        for (String prefix : packages) {
            if (!prefix.isEmpty() && name.startsWith(prefix)) {
                managed = true;
                break;
            }
        }
        if (!managed) {
            return ClassInfo.UNMANAGED;
        }

        boolean noSpatial = false;
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            String n = c.getName();
            if (ungated.contains(n)) {
                return ClassInfo.UNMANAGED;
            }
            if (noFrustum.contains(n)) {
                noSpatial = true;
            }
        }
        return new ClassInfo(true, noSpatial);
    }
}
