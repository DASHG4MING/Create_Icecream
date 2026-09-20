package com.createicecream.mixin.minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.createicecream.culling.ContraptionShadowGate;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;

/**
 * Create's {@code ContraptionEntityRenderer#shouldRender} (and the train carriage renderer) end in
 * {@code super.shouldRender(...)}, i.e. here. Iris asks the same question for its shadow pass. For Create
 * contraptions only, we answer "no" during the shadow pass when the contraption is beyond the configured
 * shadow distance. Every other entity, and every normal (non-shadow) frame, is untouched.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void icecream$skipFarContraptionShadows(Entity entity, Frustum frustum, double camX, double camY, double camZ,
                                                    CallbackInfoReturnable<Boolean> cir) {
        if (ContraptionShadowGate.shouldSkip(entity)) {
            cir.setReturnValue(false);
        }
    }
}
