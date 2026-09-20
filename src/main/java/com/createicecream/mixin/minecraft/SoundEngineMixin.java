package com.createicecream.mixin.minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.createicecream.sound.SoundLimiter;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;

/** Skips inaudible / duplicate one-shot Create sounds before a channel is requested; see {@link SoundLimiter}. */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
    @Inject(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)V", at = @At("HEAD"), cancellable = true)
    private void icecream$limitCreateSounds(SoundInstance sound, CallbackInfo ci) {
        if (SoundLimiter.shouldSkip(sound)) {
            ci.cancel();
        }
    }
}
