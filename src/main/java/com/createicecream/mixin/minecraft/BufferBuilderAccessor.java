package com.createicecream.mixin.minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;

/** Replaces CreateBetterFps' access transformer on {@code BufferBuilder.format}. */
@Mixin(BufferBuilder.class)
public interface BufferBuilderAccessor {
    @Accessor("format")
    VertexFormat icecream$getFormat();
}
