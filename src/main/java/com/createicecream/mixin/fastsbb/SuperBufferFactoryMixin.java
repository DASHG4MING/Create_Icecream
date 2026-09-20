/*
 * Derived from CreateBetterFps by MoePus (https://github.com/MoePus/CreateBetterFps), MIT License,
 * Copyright (c) 2025 MoePus. See THIRD_PARTY_LICENSE_CreateBetterFps.txt. Modified for Create IceCream.
 */
package com.createicecream.mixin.fastsbb;

import com.createicecream.render.fast.IceSuperByteBuffer;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import net.createmod.catnip.render.MutableTemplateMesh;
import net.createmod.catnip.render.SuperBufferFactory;
import net.createmod.catnip.render.SuperByteBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = SuperBufferFactory.class, remap = false)
public abstract class SuperBufferFactoryMixin {
    @Inject(method = "create", at = @At(value = "HEAD"), cancellable = true, require = 0)
    public void onCreate(MeshData data, CallbackInfoReturnable<SuperByteBuffer> cir) {
        cir.setReturnValue(new IceSuperByteBuffer(new MutableTemplateMesh(data).toImmutable()));
    }
}
