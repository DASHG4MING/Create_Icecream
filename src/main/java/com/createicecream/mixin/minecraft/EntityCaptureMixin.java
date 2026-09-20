package com.createicecream.mixin.minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import com.createicecream.culling.ContraptionCullHolder;
import com.createicecream.culling.ContraptionCullState;
import com.createicecream.render.capture.CaptureHolder;
import com.createicecream.render.capture.CaptureSlot;

import net.minecraft.world.entity.Entity;

/** One reference field per entity; only Create contraptions ever get it set (render throttle captures). */
@Mixin(Entity.class)
public abstract class EntityCaptureMixin implements CaptureHolder, ContraptionCullHolder {
    @Unique
    private ContraptionCullState icecream$cullState;

    @Override
    public ContraptionCullState icecream$cullState() {
        return icecream$cullState;
    }

    @Override
    public void icecream$setCullState(ContraptionCullState state) {
        icecream$cullState = state;
    }

    @Unique
    private CaptureSlot[] icecream$captureSlots;

    @Override
    public CaptureSlot[] icecream$captureSlots() {
        return icecream$captureSlots;
    }

    @Override
    public void icecream$setCaptureSlots(CaptureSlot[] slots) {
        icecream$captureSlots = slots;
    }
}
