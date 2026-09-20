package com.createicecream.mixin.minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.createicecream.render.lod.DetailLod;

import net.minecraft.client.gui.Font;

/** Far-away text drawn by Create renderers (display boards, clipboards, signs on trains) is skipped. */
@Mixin(Font.class)
public abstract class FontLodMixin {
    @Inject(
            method = {
                    "drawInBatch(Ljava/lang/String;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I",
                    "drawInBatch(Ljava/lang/String;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;IIZ)I",
                    "drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I",
                    "drawInBatch(Lnet/minecraft/util/FormattedCharSequence;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I"
            },
            at = @At("HEAD"),
            cancellable = true
    )
    private void icecream$skipFarText(CallbackInfoReturnable<Integer> cir) {
        if (DetailLod.skipDetails) {
            DetailLod.skippedTexts++;
            cir.setReturnValue(0);
        }
    }
}
