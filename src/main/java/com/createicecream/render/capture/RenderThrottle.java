package com.createicecream.render.capture;

import org.joml.Matrix4f;

import com.createicecream.config.OptConfig;
import com.createicecream.mixin.minecraft.BufferBuilderAccessor;
import com.createicecream.render.cache.CacheStats;
import com.createicecream.render.cache.CachedMesh;
import com.createicecream.render.cache.FrameClock;
import com.createicecream.render.cache.MeshReplay;
import com.createicecream.render.fast.IceSuperByteBuffer;
import com.createicecream.render.fast.IrisCompat;
import com.createicecream.visual.VisualUpdateGate;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter;
import net.minecraft.client.renderer.MultiBufferSource;

/**
 * Dynamic animation framerate for the path Create uses when Flywheel is off (always the case with a shader
 * pack): block entity renderers and contraptions (trains!) that are farther than
 * {@code animation.fullRateDistance} are only really rendered every Nth frame. The output of that frame is
 * captured as finished vertex data, and on the frames in between it is copied into the frame buffers with
 * the <i>current</i> translation, so a moving train still glides smoothly; only its internal animation
 * (wheels, rotating parts, turning on a curve) advances at the lower rate.
 * <p>
 * Frame-time consistency: every object gets its own phase, so the real renders are spread evenly over the
 * frames instead of all landing on the same one, and captured meshes reuse their memory (no allocation in
 * the steady state).
 */
public final class RenderThrottle {
    /** Render normally, the throttle isn't involved. */
    public static final int NORMAL = 0;
    /** The object was drawn from its capture; for contraptions, run the renderer into {@link NullSink}. */
    public static final int REPLAYED = 1;
    /** Render into {@link #captureSource()} with {@link #capturePose()}, then call {@link #endCapture}. */
    public static final int CAPTURE = 2;

    /** Frames to wait before retrying an object whose capture was incomplete. */
    private static final int RETRY_FRAMES = 400;
    /** Max difference in the pose's rotation part before a capture is considered outdated. */
    private static final float LINEAR_TOLERANCE = 2e-3f;

    private static final PoseStack CAPTURE_POSE = new PoseStack();
    private static final float[] T_LINEAR = new float[9];
    private static CaptureSlot capturing;

    private RenderThrottle() {
    }

    public static boolean isCapturing() {
        return CaptureSession.INSTANCE.active;
    }

    /**
     * @param distSq squared distance from the camera
     * @param phase  stable per-object value that spreads the real renders over the frames
     * @return {@link #NORMAL}, {@link #REPLAYED} or {@link #CAPTURE}
     */
    public static int begin(CaptureHolder holder, double distSq, int phase, PoseStack ms, MultiBufferSource buffers) {
        if (CaptureSession.INSTANCE.active || IceSuperByteBuffer.RECORDING) {
            return NORMAL; // nested (a block entity inside a contraption being captured)
        }
        int pass = IrisCompat.isShadowPass() ? 1 : 0;
        CaptureSlot[] slots = holder.icecream$captureSlots();
        int divisor = VisualUpdateGate.divisorForDistanceSq(distSq);
        if (pass == 1) {
            // shader shadow map: low resolution and soft, so even nearby animation can update less often
            divisor = Math.max(divisor, VisualUpdateGate.shadowDivisor());
        }
        // Any buffer source is fine (Iris wraps every one in its own BufferSourceWrapper); buffers that can't be
        // captured are detected per render type in CaptureSession and simply drawn normally.
        if (divisor <= 1 || buffers instanceof NullSink) {
            if (slots != null && slots[pass] != null) {
                slots[pass].free(); // came close: full rate again, give the memory back
                slots[pass] = null;
            }
            return NORMAL;
        }
        if (slots == null) {
            slots = new CaptureSlot[2];
            holder.icecream$setCaptureSlots(slots);
        }
        CaptureSlot slot = slots[pass];
        if (slot == null) {
            slot = slots[pass] = new CaptureSlot();
        }
        int frame = FrameClock.frame;
        if (frame - slot.failedUntil < 0) {
            return NORMAL;
        }
        fillLinear(ms.last().pose());

        int age = frame - slot.recordFrame;
        boolean due = !slot.valid
                || age >= 2 * divisor
                || age >= 1 && Math.floorMod(frame + phase, divisor) == 0
                || !linearClose(slot.linear);
        if (!due) {
            if (replay(slot, buffers, ms.last().pose())) {
                CacheStats.throttleReplays++;
                return REPLAYED;
            }
            slot.free();
            return NORMAL;
        }
        if (CacheStats.BYTES.get() > OptConfig.modelCacheMaxBytes) {
            slot.free();
            return NORMAL;
        }

        // capture this frame
        System.arraycopy(T_LINEAR, 0, slot.linear, 0, 9);
        PoseStack.Pose in = ms.last();
        PoseStack p = CAPTURE_POSE;
        while (!p.clear()) {
            p.popPose(); // left over from a renderer that threw
        }
        p.setIdentity();
        p.last().pose().set3x3(in.pose());
        p.last().normal().set(in.normal());
        CaptureSession.INSTANCE.begin(buffers, in.pose().m30(), in.pose().m31(), in.pose().m32());
        IceSuperByteBuffer.CAPTURING = true;
        capturing = slot;
        return CAPTURE;
    }

    public static PoseStack capturePose() {
        return CAPTURE_POSE;
    }

    public static MultiBufferSource captureSource() {
        return CaptureSession.INSTANCE;
    }

    /** Stores the capture and draws it into the real buffers for this frame. */
    public static void endCapture(PoseStack ms, MultiBufferSource buffers) {
        CaptureSlot slot = capturing;
        capturing = null;
        IceSuperByteBuffer.CAPTURING = false;
        CaptureSession session = CaptureSession.INSTANCE;
        boolean complete = !session.incomplete;
        try {
            session.finishInto(slot);
        } catch (Throwable t) {
            // out of native memory or similar: this object is simply not throttled for a while
            slot.free();
            slot.failedUntil = FrameClock.frame + RETRY_FRAMES;
            CacheStats.throttleFailures++;
            return;
        }
        int frame = FrameClock.frame;
        slot.recordFrame = frame;
        // Whatever couldn't be captured was already drawn directly; draw the captured rest now.
        boolean ok = replay(slot, buffers, ms.last().pose());
        CacheStats.throttleCaptures++;
        if (complete && ok) {
            slot.valid = true;
        } else {
            slot.free();
            slot.failedUntil = frame + RETRY_FRAMES;
            CacheStats.throttleFailures++;
        }
    }

    /** The renderer threw while capturing: drop everything, the exception propagates as usual. */
    public static void abortCapture() {
        CaptureSlot slot = capturing;
        capturing = null;
        IceSuperByteBuffer.CAPTURING = false;
        CaptureSession.INSTANCE.abort();
        if (slot != null) {
            slot.free();
            slot.failedUntil = FrameClock.frame + RETRY_FRAMES;
        }
    }

    private static boolean replay(CaptureSlot slot, MultiBufferSource buffers, Matrix4f pose) {
        // Check every buffer first, so a mismatch never leaves half an object drawn (and then drawn again).
        for (int i = 0; i < slot.count; i++) {
            VertexConsumer real = buffers.getBuffer(slot.types[i]);
            if (!(real instanceof BufferBuilder builder)
                    || ((BufferBuilderAccessor) builder).icecream$getFormat() != slot.meshes[i].format
                    || VertexBufferWriter.tryOf(real) == null) {
                return false;
            }
        }
        float tx = pose.m30(), ty = pose.m31(), tz = pose.m32();
        // In the main view, drop back faces of render types the GPU culls anyway (halves copy + upload).
        boolean mayCull = IceSuperByteBuffer.replayShouldCull();
        for (int i = 0; i < slot.count; i++) {
            CachedMesh mesh = slot.meshes[i];
            VertexBufferWriter writer = VertexBufferWriter.tryOf(buffers.getBuffer(slot.types[i]));
            MeshReplay.replay(mesh, writer, tx, ty, tz, mayCull && RenderTypeCulling.culls(slot.types[i]));
        }
        return true;
    }

    private static void fillLinear(Matrix4f m) {
        T_LINEAR[0] = m.m00(); T_LINEAR[1] = m.m01(); T_LINEAR[2] = m.m02();
        T_LINEAR[3] = m.m10(); T_LINEAR[4] = m.m11(); T_LINEAR[5] = m.m12();
        T_LINEAR[6] = m.m20(); T_LINEAR[7] = m.m21(); T_LINEAR[8] = m.m22();
    }

    /**
     * The captured vertices contain the pose's rotation. In the normal view it is the identity; in the shader
     * shadow pass it is the sun's rotation, which moves by a tiny amount between two captures (well below a
     * pixel). Anything bigger (a different view, a mod rendering the world twice) forces a new capture.
     */
    private static boolean linearClose(float[] captured) {
        for (int i = 0; i < 9; i++) {
            if (Math.abs(captured[i] - T_LINEAR[i]) > LINEAR_TOLERANCE) {
                return false;
            }
        }
        return true;
    }
}
