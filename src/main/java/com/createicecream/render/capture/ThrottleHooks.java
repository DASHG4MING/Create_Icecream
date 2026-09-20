package com.createicecream.render.capture;

import com.createicecream.config.OptConfig;
import com.createicecream.culling.ContraptionShadowGate;
import com.createicecream.culling.ContraptionCulling;
import com.createicecream.culling.CullingManager;
import com.createicecream.render.fast.IrisCompat;
import com.createicecream.render.lod.DetailLod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Bodies of the two dispatcher hooks (kept out of the mixin classes so they stay one-liners). */
public final class ThrottleHooks {
    private static final String SAFE_BER = "com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer";
    private static Class<?> safeBerClass;
    private static boolean lookedUp;

    private ThrottleHooks() {
    }

    private static boolean isCreateBer(BlockEntityRenderer<?> renderer) {
        if (!lookedUp) {
            lookedUp = true;
            try {
                safeBerClass = Class.forName(SAFE_BER, false, ThrottleHooks.class.getClassLoader());
            } catch (Throwable t) {
                safeBerClass = null;
            }
        }
        return safeBerClass != null && safeBerClass.isInstance(renderer);
    }

    private static int phase(BlockPos pos) {
        return pos.getX() * 73856093 ^ pos.getY() * 19349663 ^ pos.getZ() * 83492791;
    }

    public static void renderBlockEntity(BlockEntityRenderer<BlockEntity> renderer, BlockEntity be, float partialTicks,
                                         PoseStack ms, MultiBufferSource buffers, int light, int overlay,
                                         Operation<Void> original) {
        CullHandoff.berHookSeen = true;
        if (!OptConfig.enabled || RenderThrottle.isCapturing() || be.getLevel() == null
                || be.getLevel() != CullingManager.activeLevel() || !isCreateBer(renderer)) {
            original.call(renderer, be, partialTicks, ms, buffers, light, overlay);
            return;
        }
        // Cull first: a hidden machine must neither be replayed nor captured.
        if (CullingManager.shouldSkipBer(be, renderer)) {
            return;
        }
        BlockPos pos = be.getBlockPos();
        double d2 = CullingManager.distanceSq(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        boolean prevLod = DetailLod.skipDetails;
        DetailLod.skipDetails = DetailLod.farEnough(d2, IrisCompat.isShadowPass());
        try {
            int mode = OptConfig.animThrottleBers
                    ? RenderThrottle.begin((CaptureHolder) be, d2, phase(pos), ms, buffers) : RenderThrottle.NORMAL;
            if (mode == RenderThrottle.REPLAYED) {
                return;
            }
            CullHandoff.mark();
            if (mode == RenderThrottle.NORMAL) {
                try {
                    original.call(renderer, be, partialTicks, ms, buffers, light, overlay);
                } finally {
                    CullHandoff.clear();
                }
                return;
            }
            try {
                original.call(renderer, be, partialTicks, RenderThrottle.capturePose(), RenderThrottle.captureSource(), light, overlay);
            } catch (RuntimeException | Error e) {
                RenderThrottle.abortCapture();
                throw e;
            } finally {
                CullHandoff.clear();
            }
            RenderThrottle.endCapture(ms, buffers);
        } finally {
            DetailLod.skipDetails = prevLod;
        }
    }

    public static void renderEntity(EntityRenderer<Entity> renderer, Entity entity, float yaw, float partialTicks,
                                    PoseStack ms, MultiBufferSource buffers, int light, Operation<Void> original) {
        CullHandoff.entityHookSeen = true;
        if (!OptConfig.enabled || RenderThrottle.isCapturing()
                || entity.level() != CullingManager.activeLevel() || !ContraptionShadowGate.isContraption(entity)) {
            original.call(renderer, entity, yaw, partialTicks, ms, buffers, light);
            return;
        }
        // Hidden behind terrain (tunnel, hill, factory wall): run the renderer for its side effects only.
        if (ContraptionCulling.isOccluded(entity, CullingManager.clientTick())) {
            original.call(renderer, entity, yaw, partialTicks, ms, NullSink.INSTANCE, light);
            return;
        }
        // distance to the nearest point of the contraption, so a long train passing close by stays full rate
        double d2 = CullingManager.distanceSq(entity.getBoundingBox());
        boolean prevLod = DetailLod.skipDetails;
        DetailLod.skipDetails = DetailLod.farEnough(d2, IrisCompat.isShadowPass());
        try {
            int mode = OptConfig.animThrottleContraptions
                    ? RenderThrottle.begin((CaptureHolder) entity, d2, entity.getId() * 0x9E3779B9, ms, buffers)
                    : RenderThrottle.NORMAL;
            if (mode == RenderThrottle.NORMAL) {
                original.call(renderer, entity, yaw, partialTicks, ms, buffers, light);
            } else if (mode == RenderThrottle.REPLAYED) {
                // Still run the renderer (train coupling anchors, animation state...), but without any geometry.
                original.call(renderer, entity, yaw, partialTicks, ms, NullSink.INSTANCE, light);
            } else {
                try {
                    original.call(renderer, entity, yaw, partialTicks, RenderThrottle.capturePose(), RenderThrottle.captureSource(), light);
                } catch (RuntimeException | Error e) {
                    RenderThrottle.abortCapture();
                    throw e;
                }
                RenderThrottle.endCapture(ms, buffers);
            }
        } finally {
            DetailLod.skipDetails = prevLod;
        }
    }
}
