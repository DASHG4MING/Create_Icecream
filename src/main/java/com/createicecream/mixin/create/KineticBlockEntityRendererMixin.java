package com.createicecream.mixin.create;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.createicecream.visual.KineticRenderTypeCache;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code KineticBlockEntityRenderer#getRenderType} runs for every kinetic block entity on every frame when
 * Flywheel is not visualising (backend off, shader pack active). Each call allocates a new
 * {@code RandomSource}, looks up the baked model and scans its render type set. The answer only depends on
 * the block state and the baked model, so we memoise it per state and validate against the current model
 * (which changes identity on resource reload).
 * <p>
 * Subclasses that override {@code getRenderType} (e.g. the chain conveyor renderer) are unaffected.
 */
@Mixin(targets = "com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer", remap = false)
public abstract class KineticBlockEntityRendererMixin {

    @Inject(method = "getRenderType", at = @At("HEAD"), cancellable = true, remap = false)
    private void icecream$cachedRenderType(CallbackInfoReturnable<RenderType> cir,
                                            @Local(argsOnly = true) BlockState state) {
        RenderType cached = KineticRenderTypeCache.get(state);
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "getRenderType", at = @At("RETURN"), remap = false)
    private void icecream$storeRenderType(CallbackInfoReturnable<RenderType> cir,
                                           @Local(argsOnly = true) BlockState state) {
        KineticRenderTypeCache.put(state, cir.getReturnValue());
    }
}
