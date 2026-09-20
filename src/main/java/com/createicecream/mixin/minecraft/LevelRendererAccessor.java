package com.createicecream.mixin.minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;

/** Same two fields Create's own {@code LevelRendererAccessor} reads. */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
    @Accessor("cullingFrustum")
    Frustum icecream$getCullingFrustum();

    @Accessor("capturedFrustum")
    Frustum icecream$getCapturedFrustum();
}
