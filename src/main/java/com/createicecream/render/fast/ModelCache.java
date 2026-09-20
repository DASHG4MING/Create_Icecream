package com.createicecream.render.fast;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.createicecream.config.OptConfig;
import com.createicecream.render.cache.CacheStats;
import com.createicecream.render.cache.CachedMesh;
import com.createicecream.render.cache.FrameClock;
import com.createicecream.render.cache.MeshReplay;
import com.createicecream.render.cache.VertexRecorder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter;
import net.createmod.ponder.mixin.client.accessor.RenderSystemAccessor;

/**
 * Output cache for individual {@link IceSuperByteBuffer}s.
 * <p>
 * Create re-transforms every vertex of every model every frame. For a model that is drawn with exactly the
 * same transforms, colour, light and overlay as in the previous frame (a parked train's body, any
 * contraption that isn't moving), the finished vertices are identical except for the camera-relative
 * translation. So on the second identical frame we record the finished vertices once, and from then on
 * only copy them and add the translation ({@link MeshReplay}).
 * <p>
 * Safety rules:
 * <ul>
 *     <li>A contraption that only translates (a train on straight track) keeps its mesh; its level light is
 *     re-sampled every {@code modelCache.movingLightStep} blocks.</li>
 *     <li>Anything else that differs from the previous frame (a rotating part, a changed light
 *     value, a scrolling UV) is detected by comparing the full parameter set, and falls back to normal
 *     rendering.</li>
 *     <li>Buffers drawn more than once per frame (shared models such as shafts, cogs, track ties) are marked
 *     shared on first detection and never cached here; tracks have their own per-curve cache.</li>
 *     <li>Level lighting (contraptions) is re-recorded periodically, so torches and daylight changes show
 *     up within {@code modelCache.lightRefreshMs}.</li>
 *     <li>Recorded meshes contain every face, so they are valid from any camera position; in the main view the
 *     replay drops back faces with the same test fresh draws use.</li>
 * </ul>
 */
public final class ModelCache {
    private static final VertexRecorder RECORDER = new VertexRecorder();
    private static final PoseStack RECORD_POSE = new PoseStack();

    // scratch key, filled from the current call and compared against the slot
    private static final float[] T_IN_POSE = new float[9];
    private static final float[] T_IN_NORMAL = new float[9];
    private static final float[] T_POSE = new float[16];
    private static final float[] T_NORMAL = new float[9];
    private static final float[] T_LIGHT_TF = new float[16];
    private static final float[] T_LIGHT_DIRS = new float[6];

    private ModelCache() {
    }

    static final class Slot {
        final float[] inPose = new float[9];
        final float[] inNormal = new float[9];
        final float[] pose = new float[16];
        final float[] normal = new float[9];
        final float[] lightTf = new float[16];
        final float[] lightDirs = new float[6];
        boolean hasLightTf;
        int color, overlay, packedLight;
        boolean diffuseOff, customOverlay, customLight, levelLight;
        Object lightLevel;
        VertexFormat format;
        boolean keyValid;

        int lastFrame = Integer.MIN_VALUE;
        boolean shared;
        /** Valid mesh, or null. {@link #spare} keeps the native block of an invalidated mesh for reuse. */
        CachedMesh mesh;
        CachedMesh spare;
        long recordedAt;
        /** Translation of the light matrix when the mesh was recorded (world position of a moving contraption). */
        float lightX, lightY, lightZ;
        /** Per-slot offset of the light refresh, so meshes recorded together don't all refresh in one frame. */
        final long refreshJitter = (long) (Math.random() * 250_000_000L);
    }

    /** @return true if the call was fully served from (or recorded into) the cache */
    static boolean tryRender(IceSuperByteBuffer sbb, PoseStack input, VertexConsumer builder) {
        if (!OptConfig.enabled || !OptConfig.modelCache || sbb.hasSpriteShift() || sbb.isEmpty()) {
            return false;
        }
        VertexFormat format = FastFormats.of(builder);
        if (format == null) {
            return false;
        }
        VertexBufferWriter writer = VertexBufferWriter.tryOf(builder);
        if (writer == null) {
            return false;
        }
        int pass = IrisCompat.isShadowPass() ? 1 : 0;
        if (pass == 1 && !OptConfig.modelCacheInShadowPass) {
            // In the shadow pass Iris puts the sun rotation into the pose, and the sun moves every tick, so
            // a model would keep switching between replayed and freshly drawn frames here. Off by default.
            return false;
        }
        Slot slot = slot(sbb, pass);
        if (slot.shared) {
            return false;
        }
        int frame = FrameClock.frame;
        if (slot.lastFrame == frame) {
            // drawn twice in one frame: a shared model (e.g. the same cog for many block entities)
            slot.shared = true;
            invalidate(slot);
            slot.spare = null; // never used again: let the GC free it
            return false;
        }
        slot.lastFrame = frame;

        // From here on this model is a cache candidate. In the main view, fresh draws and replays both use the
        // same CPU back-face test (MeshReplay culls at replay time), so switching between them never changes
        // which faces are drawn. In the shadow pass nothing is culled, same as the recorder.
        IceSuperByteBuffer.NO_CPU_CULL = pass == 1;
        boolean cull = pass == 0 && IceSuperByteBuffer.replayShouldCull();

        fillKey(sbb, input);
        boolean same = slot.keyValid && slot.format == format && matches(slot, sbb);
        if (!same) {
            storeKey(slot, sbb, format);
            invalidate(slot);
            CacheStats.misses++;
            return false;
        }

        Matrix4f pose = input.last().pose();
        long now = System.nanoTime();
        boolean lightStale = slot.levelLight && slot.mesh != null
                && (now - slot.recordedAt > OptConfig.modelCacheLightRefreshNanos + slot.refreshJitter
                || slot.hasLightTf && movedTooFar(slot));
        if (slot.mesh != null && !lightStale) {
            MeshReplay.replay(slot.mesh, writer, pose.m30(), pose.m31(), pose.m32(), cull);
            CacheStats.hits++;
            return true;
        }

        // Same parameters two frames in a row: record once, then replay.
        if (CacheStats.BYTES.get() > OptConfig.modelCacheMaxBytes) {
            return false;
        }
        CachedMesh reuse = slot.mesh != null ? slot.mesh : slot.spare;
        CachedMesh mesh = record(sbb, input, format, reuse);
        if (mesh == null) {
            slot.shared = true; // something in this model can't be recorded; never try again
            slot.mesh = null;
            slot.spare = null;
            return false;
        }
        slot.mesh = mesh;
        slot.spare = null;
        slot.recordedAt = now;
        slot.lightX = T_LIGHT_TF[12];
        slot.lightY = T_LIGHT_TF[13];
        slot.lightZ = T_LIGHT_TF[14];
        CacheStats.records++;
        MeshReplay.replay(mesh, writer, pose.m30(), pose.m31(), pose.m32(), cull);
        return true;
    }

    private static void invalidate(Slot slot) {
        if (slot.mesh != null) {
            slot.spare = slot.mesh;
            slot.mesh = null;
        }
    }

    /**
     * A contraption that only moves (train on straight track) keeps its mesh: geometry is relative to the
     * pose, only the level light is sampled at the world position. Re-record once it moved far enough that
     * the light might differ.
     */
    private static boolean movedTooFar(Slot slot) {
        float step = OptConfig.modelCacheLightStep;
        if (step <= 0f) {
            return T_LIGHT_TF[12] != slot.lightX || T_LIGHT_TF[13] != slot.lightY || T_LIGHT_TF[14] != slot.lightZ;
        }
        return Math.abs(T_LIGHT_TF[12] - slot.lightX) >= step
                || Math.abs(T_LIGHT_TF[13] - slot.lightY) >= step
                || Math.abs(T_LIGHT_TF[14] - slot.lightZ) >= step;
    }

    private static CachedMesh record(IceSuperByteBuffer sbb, PoseStack input, VertexFormat format, CachedMesh reuse) {
        PoseStack.Pose in = input.last();
        PoseStack p = RECORD_POSE;
        p.setIdentity();
        // same rotation/scale as the real pose, zero translation: replay adds the translation
        p.last().pose().set3x3(in.pose());
        p.last().normal().set(in.normal());
        RECORDER.begin(format);
        IceSuperByteBuffer.RECORDING = true;
        try {
            if (!sbb.renderIntoSodium(p, RECORDER)) {
                return null;
            }
            return RECORDER.finishInto(reuse);
        } catch (Throwable t) {
            return null;
        } finally {
            IceSuperByteBuffer.RECORDING = false;
        }
    }

    private static Slot slot(IceSuperByteBuffer sbb, int pass) {
        Slot[] slots = sbb.cacheSlots;
        if (slots == null) {
            slots = sbb.cacheSlots = new Slot[2];
        }
        Slot slot = slots[pass];
        if (slot == null) {
            slot = slots[pass] = new Slot();
        }
        return slot;
    }

    // ---- key handling ----

    private static void fillKey(IceSuperByteBuffer sbb, PoseStack input) {
        Matrix4f in = input.last().pose();
        T_IN_POSE[0] = in.m00(); T_IN_POSE[1] = in.m01(); T_IN_POSE[2] = in.m02();
        T_IN_POSE[3] = in.m10(); T_IN_POSE[4] = in.m11(); T_IN_POSE[5] = in.m12();
        T_IN_POSE[6] = in.m20(); T_IN_POSE[7] = in.m21(); T_IN_POSE[8] = in.m22();
        input.last().normal().get(T_IN_NORMAL);
        PoseStack.Pose local = sbb.getTransforms().last();
        local.pose().get(T_POSE);
        local.normal().get(T_NORMAL);
        Matrix4f lightTf = sbb.lightTransformMatrix();
        if (lightTf != null) {
            lightTf.get(T_LIGHT_TF);
        }
        Vector3f[] dirs = RenderSystemAccessor.catnip$getShaderLightDirections();
        T_LIGHT_DIRS[0] = dirs[0].x; T_LIGHT_DIRS[1] = dirs[0].y; T_LIGHT_DIRS[2] = dirs[0].z;
        T_LIGHT_DIRS[3] = dirs[1].x; T_LIGHT_DIRS[4] = dirs[1].y; T_LIGHT_DIRS[5] = dirs[1].z;
    }

    private static boolean matches(Slot s, IceSuperByteBuffer sbb) {
        Matrix4f lightTf = sbb.lightTransformMatrix();
        return s.color == sbb.vertexColor()
                && s.diffuseOff == sbb.diffuseDisabled()
                && s.customOverlay == sbb.customOverlay()
                && (!s.customOverlay || s.overlay == sbb.overlayValue())
                && s.customLight == sbb.customLight()
                && (!s.customLight || s.packedLight == sbb.packedLightValue())
                && s.levelLight == sbb.usesLevelLight()
                && s.lightLevel == sbb.lightLevel()
                && s.hasLightTf == (lightTf != null)
                && (lightTf == null || eq(s.lightTf, T_LIGHT_TF, 12) && s.lightTf[15] == T_LIGHT_TF[15])
                && eq(s.inPose, T_IN_POSE, 9)
                && eq(s.inNormal, T_IN_NORMAL, 9)
                && eq(s.pose, T_POSE, 16)
                && eq(s.normal, T_NORMAL, 9)
                && eq(s.lightDirs, T_LIGHT_DIRS, 6);
    }

    private static void storeKey(Slot s, IceSuperByteBuffer sbb, VertexFormat format) {
        Matrix4f lightTf = sbb.lightTransformMatrix();
        s.color = sbb.vertexColor();
        s.diffuseOff = sbb.diffuseDisabled();
        s.customOverlay = sbb.customOverlay();
        s.overlay = sbb.overlayValue();
        s.customLight = sbb.customLight();
        s.packedLight = sbb.packedLightValue();
        s.levelLight = sbb.usesLevelLight();
        s.lightLevel = sbb.lightLevel();
        s.hasLightTf = lightTf != null;
        if (lightTf != null) {
            System.arraycopy(T_LIGHT_TF, 0, s.lightTf, 0, 16); // [12..14] (translation) is not compared, see movedTooFar
        }
        System.arraycopy(T_IN_POSE, 0, s.inPose, 0, 9);
        System.arraycopy(T_IN_NORMAL, 0, s.inNormal, 0, 9);
        System.arraycopy(T_POSE, 0, s.pose, 0, 16);
        System.arraycopy(T_NORMAL, 0, s.normal, 0, 9);
        System.arraycopy(T_LIGHT_DIRS, 0, s.lightDirs, 0, 6);
        s.format = format;
        s.keyValid = true;
    }

    private static boolean eq(float[] a, float[] b, int n) {
        for (int i = 0; i < n; i++) {
            if (Float.floatToRawIntBits(a[i]) != Float.floatToRawIntBits(b[i])) {
                return false;
            }
        }
        return true;
    }

    /** Exposed for the track cache: same recorder and pose helper. */
    static VertexRecorder recorder() {
        return RECORDER;
    }

    static PoseStack recordPose(PoseStack input) {
        PoseStack.Pose in = input.last();
        PoseStack p = RECORD_POSE;
        p.setIdentity();
        p.last().pose().set3x3(in.pose());
        p.last().normal().set(in.normal());
        return p;
    }

    static void fillInputKey(PoseStack input, float[] inPose, float[] inNormal) {
        Matrix4f in = input.last().pose();
        inPose[0] = in.m00(); inPose[1] = in.m01(); inPose[2] = in.m02();
        inPose[3] = in.m10(); inPose[4] = in.m11(); inPose[5] = in.m12();
        inPose[6] = in.m20(); inPose[7] = in.m21(); inPose[8] = in.m22();
        Matrix3f n = input.last().normal();
        n.get(inNormal);
    }

    static boolean floatsEqual(float[] a, float[] b, int n) {
        return eq(a, b, n);
    }
}
