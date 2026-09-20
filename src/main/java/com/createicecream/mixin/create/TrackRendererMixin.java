package com.createicecream.mixin.create;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.createicecream.render.fast.TrackMeshCache;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackRenderer;

import net.minecraft.world.level.Level;

/** Serves curved tracks from {@link TrackMeshCache}. Only applied when Sodium is present. */
@Mixin(value = TrackRenderer.class, remap = false)
public abstract class TrackRendererMixin {
    @Inject(method = "renderBezierTurn", at = @At("HEAD"), cancellable = true, remap = false)
    private static void icecream$cachedCurve(Level level, BezierConnection bc, PoseStack ms, VertexConsumer vb, CallbackInfo ci) {
        if (TrackMeshCache.render(level, bc, ms, vb)) {
            ci.cancel();
        }
    }
}
