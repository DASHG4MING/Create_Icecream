package com.createicecream.render.fast;

import com.createicecream.config.OptConfig;
import com.createicecream.render.cache.CacheStats;
import com.createicecream.render.cache.CachedMesh;
import com.createicecream.render.cache.FrameClock;
import com.createicecream.render.cache.MeshReplay;
import com.createicecream.render.cache.ReloadGeneration;
import com.createicecream.render.cache.VertexRecorder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackRenderer;

import net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Whole-curve mesh cache for Create's curved train tracks (the path used when Flywheel is off, i.e. with a
 * shader pack).
 * <p>
 * {@code TrackRenderer.renderBezierTurn} rebuilds every tie, both rails and the girders of a curve from
 * shared part models on every frame, although a curve never moves. The spark profiles showed this at 4-8% of
 * the whole frame. Here the complete output of one curve is recorded once and replayed as a single copy.
 * The cache lives on the {@link BezierConnection} object itself, which Create replaces whenever a curve
 * changes, so edits invalidate it automatically. The per-segment light values are re-checked every
 * {@link #LIGHT_CHECK_FRAMES} frames; if any changed, the curve is recorded again.
 */
public final class TrackMeshCache {
    private static final int LIGHT_CHECK_FRAMES = 20;
    private static final float[] T_IN_POSE = new float[9];
    private static final float[] T_IN_NORMAL = new float[9];

    private TrackMeshCache() {
    }

    /** Duck interface implemented on BezierConnection by BezierConnectionMixin. */
    public interface Holder {
        Slot icecream$trackSlot(int pass);
    }

    public static final class Slot {
        final float[] inPose = new float[9];
        final float[] inNormal = new float[9];
        VertexFormat format;
        boolean keyValid;
        boolean disabled;
        CachedMesh mesh;
        /** Invalidated mesh kept for its native memory (re-recording reuses it instead of allocating). */
        CachedMesh spare;
        int lightHash;
        int reloadGeneration;
        int lastLightCheck;
    }

    /** @return true if the curve was drawn from the cache (the caller must then skip Create's code) */
    public static boolean render(Level level, BezierConnection bc, PoseStack ms, VertexConsumer vb) {
        if (!OptConfig.enabled || !OptConfig.trackCache || IceSuperByteBuffer.RECORDING || !bc.isPrimary()) {
            return false;
        }
        VertexFormat format = FastFormats.of(vb);
        if (format == null) {
            return false;
        }
        VertexBufferWriter writer = VertexBufferWriter.tryOf(vb);
        if (writer == null) {
            return false;
        }
        int pass = IrisCompat.isShadowPass() ? 1 : 0;
        if (pass == 1 && !OptConfig.modelCacheInShadowPass) {
            return false; // see ModelCache: the shadow pose rotates with the sun every tick
        }
        Slot slot = ((Holder) bc).icecream$trackSlot(pass);
        if (slot.disabled) {
            return false;
        }

        ModelCache.fillInputKey(ms, T_IN_POSE, T_IN_NORMAL);
        boolean same = slot.keyValid && slot.format == format && slot.reloadGeneration == ReloadGeneration.value
                && ModelCache.floatsEqual(slot.inPose, T_IN_POSE, 9)
                && ModelCache.floatsEqual(slot.inNormal, T_IN_NORMAL, 9);
        if (!same) {
            System.arraycopy(T_IN_POSE, 0, slot.inPose, 0, 9);
            System.arraycopy(T_IN_NORMAL, 0, slot.inNormal, 0, 9);
            slot.format = format;
            slot.reloadGeneration = ReloadGeneration.value;
            slot.keyValid = true;
            invalidate(slot);
            return false; // render normally this frame; record once the pose type is stable
        }

        int frame = FrameClock.frame;
        if (slot.mesh != null && frame - slot.lastLightCheck >= LIGHT_CHECK_FRAMES) {
            slot.lastLightCheck = frame;
            if (lightHash(level, bc) != slot.lightHash) {
                invalidate(slot); // a torch was placed, a block changed...: record again
            }
        }

        var pose = ms.last().pose();
        if (slot.mesh != null) {
            MeshReplay.replay(slot.mesh, writer, pose.m30(), pose.m31(), pose.m32(), pass == 0 && IceSuperByteBuffer.replayShouldCull());
            CacheStats.trackHits++;
            return true;
        }

        if (CacheStats.BYTES.get() > OptConfig.modelCacheMaxBytes) {
            return false;
        }
        VertexRecorder recorder = ModelCache.recorder();
        recorder.begin(format);
        IceSuperByteBuffer.RECORDING = true;
        CachedMesh mesh;
        try {
            TrackRenderer.renderBezierTurn(level, bc, ModelCache.recordPose(ms), recorder);
            mesh = recorder.finishInto(slot.spare);
            slot.spare = null;
        } catch (Throwable t) {
            // e.g. the part models are not IceSuperByteBuffers (fast renderer disabled): give up on this curve
            slot.disabled = true;
            return false;
        } finally {
            IceSuperByteBuffer.RECORDING = false;
        }
        slot.mesh = mesh;
        slot.lightHash = lightHash(level, bc);
        slot.lastLightCheck = frame;
        CacheStats.trackRecords++;
        MeshReplay.replay(mesh, writer, pose.m30(), pose.m31(), pose.m32(), pass == 0 && IceSuperByteBuffer.replayShouldCull());
        return true;
    }

    private static void invalidate(Slot slot) {
        if (slot.mesh != null) {
            slot.spare = slot.mesh;
            slot.mesh = null;
        }
    }

    /** Same light positions TrackRenderer samples (one per segment, plus girders). */
    private static int lightHash(Level level, BezierConnection bc) {
        BlockPos origin = bc.bePositions.getFirst();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int hash = 1;
        BezierConnection.SegmentAngles segments = bc.getBakedSegments();
        for (int i = 1; i < segments.length; i++) {
            BlockPos p = segments.lightPosition[i];
            pos.set(p.getX() + origin.getX(), p.getY() + origin.getY(), p.getZ() + origin.getZ());
            hash = 31 * hash + LevelRenderer.getLightColor(level, pos);
        }
        if (bc.hasGirder) {
            BezierConnection.GirderAngles girders = bc.getBakedGirders();
            for (int i = 1; i < girders.length; i++) {
                BlockPos p = girders.lightPosition[i];
                pos.set(p.getX() + origin.getX(), p.getY() + origin.getY(), p.getZ() + origin.getZ());
                hash = 31 * hash + LevelRenderer.getLightColor(level, pos);
            }
        }
        return hash;
    }
}
