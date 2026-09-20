package com.createicecream.mixin.minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.createicecream.render.capture.ThrottleHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Every in-world block entity render (vanilla, Sodium and Iris' shadow pass all go through the dispatcher)
 * ends in this one call. Only Create's renderers are handed to the render throttle; see {@link ThrottleHooks}.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {
    @WrapOperation(
            method = "setupAndRender",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V")
    )
    private static void icecream$throttle(BlockEntityRenderer<BlockEntity> renderer, BlockEntity be, float partialTicks,
                                          PoseStack ms, MultiBufferSource buffers, int light, int overlay,
                                          Operation<Void> original) {
        ThrottleHooks.renderBlockEntity(renderer, be, partialTicks, ms, buffers, light, overlay, original);
    }
}
