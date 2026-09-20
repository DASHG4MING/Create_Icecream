package com.createicecream.mixin.minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.createicecream.render.capture.ThrottleHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;

/**
 * The dispatcher has already translated the pose to the entity here. Only Create contraptions (trains,
 * elevators, bearings...) are handed to the render throttle; see {@link ThrottleHooks}.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @WrapOperation(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;render(Lnet/minecraft/world/entity/Entity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V")
    )
    private void icecream$throttle(EntityRenderer<Entity> renderer, Entity entity, float yaw, float partialTicks,
                                   PoseStack ms, MultiBufferSource buffers, int light, Operation<Void> original) {
        ThrottleHooks.renderEntity(renderer, entity, yaw, partialTicks, ms, buffers, light, original);
    }
}
