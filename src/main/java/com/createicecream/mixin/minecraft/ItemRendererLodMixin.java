package com.createicecream.mixin.minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.createicecream.render.lod.DetailLod;

import net.minecraft.client.renderer.entity.ItemRenderer;

/** Far-away items drawn by Create renderers are skipped; see {@link DetailLod}. */
@Mixin(ItemRenderer.class)
public abstract class ItemRendererLodMixin {
    @Inject(
            method = "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void icecream$skipFarItem(CallbackInfo ci) {
        if (DetailLod.skipDetails) {
            DetailLod.skippedItems++;
            ci.cancel();
        }
    }
}
