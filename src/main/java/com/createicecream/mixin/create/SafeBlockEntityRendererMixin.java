package com.createicecream.mixin.create;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.createicecream.culling.CullingManager;
import com.createicecream.render.capture.CullHandoff;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Every Create block entity renderer extends
 * {@code com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer}, whose
 * {@code render(...)} is final and simply forwards to {@code renderSafe}. Injecting here covers all of
 * Create's BERs (and addons built on the same base class) without touching vanilla or other mods' BERs.
 * <p>
 * The hook reads a single boolean produced by the culling thread. Virtual levels (Ponder, contraptions,
 * schematics) are ignored inside {@link CullingManager#shouldSkipBer}.
 */
@Mixin(targets = "com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer", remap = false)
public abstract class SafeBlockEntityRendererMixin {

    @Inject(
            method = "render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void icecream$occlusionCull(BlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource bufferSource,
                                         int light, int overlay, CallbackInfo ci) {
        if (CullHandoff.consume()) {
            return; // RenderThrottle already asked CullingManager for this exact call
        }
        if (CullingManager.shouldSkipBer(be, (BlockEntityRenderer<?>) (Object) this)) {
            ci.cancel();
        }
    }
}
