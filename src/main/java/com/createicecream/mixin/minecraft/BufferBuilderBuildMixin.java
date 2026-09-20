package com.createicecream.mixin.minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.createicecream.render.fast.ParallelMeshes;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;

/**
 * Right before a buffer is finished for drawing, append the Create models that were built for it on worker
 * threads ({@link ParallelMeshes}). Every consumer of a BufferBuilder has to call build(), so nothing can be
 * drawn without them.
 */
@Mixin(BufferBuilder.class)
public abstract class BufferBuilderBuildMixin {
    @Inject(method = "build", at = @At("HEAD"))
    private void icecream$appendParallelMeshes(CallbackInfoReturnable<MeshData> cir) {
        ParallelMeshes.flush((BufferBuilder) (Object) this);
    }
}
