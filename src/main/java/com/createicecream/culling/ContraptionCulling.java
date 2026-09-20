package com.createicecream.culling;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.createicecream.compat.ModCompat;
import com.createicecream.config.OptConfig;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/**
 * Occlusion culling for Create contraptions: a train in a tunnel or behind a hill is not drawn. Uses the same
 * background ray tracer as the block entities ({@link CullingManager}); the render thread only reads a flag.
 * <p>
 * A hidden contraption's renderer still runs (into a null buffer), so train couplings and other state that
 * the renderer updates stay correct; only the geometry is skipped. Never applied in the shadow pass, so a
 * train in a cutting still casts its shadow.
 */
public final class ContraptionCulling {
    /** Contraptions move: extra margin around the box so a train coming out of a tunnel is never late. */
    private static final double PAD = 2.0;
    private static final int STALE_TICKS = 40;

    private static final ConcurrentLinkedQueue<Entity> QUEUE = new ConcurrentLinkedQueue<>();
    private static final ArrayList<Entity> TRACKED = new ArrayList<>();
    private static volatile ContraptionCullState[] snapshot = new ContraptionCullState[0];
    public static volatile int trackedCount, occludedCount;
    public static long culled;
    public static volatile long culledPerSec;

    private ContraptionCulling() {
    }

    /** Render thread, main view only. Records the current box and returns the last tracing result. */
    public static boolean isOccluded(Entity entity, int clientTick) {
        if (!OptConfig.enabled || !OptConfig.occlusionEnabled || !OptConfig.occludeContraptions
                || ModCompat.isRenderingShadowPass()) {
            return false;
        }
        ContraptionCullHolder holder = (ContraptionCullHolder) entity;
        ContraptionCullState state = holder.icecream$cullState();
        if (state == null) {
            state = new ContraptionCullState();
            holder.icecream$setCullState(state);
        }
        AABB box = entity.getBoundingBoxForCulling();
        double max = OcclusionRaycaster.MAX_BOX_BLOCKS - 2 * PAD;
        if (box.isInfinite() || box.getXsize() > max || box.getYsize() > max || box.getZsize() > max) {
            state.hasBox = false;
            state.occluded = false;
        } else {
            state.minX = box.minX - PAD;
            state.minY = box.minY - PAD;
            state.minZ = box.minZ - PAD;
            state.maxX = box.maxX + PAD;
            state.maxY = box.maxY + PAD;
            state.maxZ = box.maxZ + PAD;
            state.hasBox = true;
        }
        state.lastSeen = clientTick;
        if (!state.listed) {
            state.listed = true;
            QUEUE.add(entity);
        }
        if (state.occluded) {
            culled++;
            return true;
        }
        return false;
    }

    /** Client tick (main thread). */
    static void tick(ClientLevel level, int clientTick) {
        boolean dirty = false;
        Entity e;
        while ((e = QUEUE.poll()) != null) {
            TRACKED.add(e);
            dirty = true;
        }
        for (int i = TRACKED.size() - 1; i >= 0; i--) {
            Entity entity = TRACKED.get(i);
            ContraptionCullState state = ((ContraptionCullHolder) entity).icecream$cullState();
            if (state == null || entity.isRemoved() || entity.level() != level || clientTick - state.lastSeen > STALE_TICKS) {
                if (state != null) {
                    state.listed = false;
                    state.occluded = false;
                }
                int last = TRACKED.size() - 1;
                TRACKED.set(i, TRACKED.get(last));
                TRACKED.remove(last);
                dirty = true;
            }
        }
        if (dirty) {
            ContraptionCullState[] arr = new ContraptionCullState[TRACKED.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = ((ContraptionCullHolder) TRACKED.get(i)).icecream$cullState();
            }
            snapshot = arr;
        }
        trackedCount = TRACKED.size();
    }

    static void reset() {
        for (Entity entity : TRACKED) {
            ContraptionCullState state = ((ContraptionCullHolder) entity).icecream$cullState();
            if (state != null) {
                state.listed = false;
                state.occluded = false;
            }
        }
        TRACKED.clear();
        QUEUE.clear();
        snapshot = new ContraptionCullState[0];
        trackedCount = 0;
        occludedCount = 0;
    }

    /** Culling thread. */
    static void runPass(OcclusionRaycaster raycaster, double cx, double cy, double cz) {
        double maxDistSq = OptConfig.occlusionMaxDistanceSq;
        long hold = OptConfig.occlusionHysteresisNanos;
        int count = 0;
        for (ContraptionCullState s : snapshot) {
            if (s == null) {
                continue;
            }
            if (!OptConfig.occludeContraptions || !s.hasBox) {
                s.occluded = false;
                continue;
            }
            double minX = s.minX, minY = s.minY, minZ = s.minZ, maxX = s.maxX, maxY = s.maxY, maxZ = s.maxZ;
            double dx = Math.max(0, Math.max(minX - cx, cx - maxX));
            double dy = Math.max(0, Math.max(minY - cy, cy - maxY));
            double dz = Math.max(0, Math.max(minZ - cz, cz - maxZ));
            if (dx * dx + dy * dy + dz * dz > maxDistSq) {
                s.occluded = false;
                continue;
            }
            boolean visible;
            try {
                visible = raycaster.isBoxVisible(minX, minY, minZ, maxX, maxY, maxZ);
            } catch (Throwable t) {
                visible = true;
            }
            long now = System.nanoTime();
            if (visible) {
                long until = now + hold;
                s.visibleUntil = until == 0 ? 1 : until;
                s.occluded = false;
            } else if (s.visibleUntil == 0 || now - s.visibleUntil >= 0) {
                s.occluded = true;
                count++;
            }
        }
        occludedCount = count;
    }
}
