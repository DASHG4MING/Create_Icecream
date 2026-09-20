package com.createicecream.mixin.create;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import com.createicecream.render.fast.TrackMeshCache;
import com.createicecream.trains.BezierBounds;

import net.minecraft.world.phys.AABB;

/**
 * Exposes {@code BezierConnection#getBounds()} and stores the per-curve mesh cache on the connection
 * itself (Create creates a new connection object whenever a curve changes, so edits invalidate it).
 */
@Mixin(targets = "com.simibubi.create.content.trains.track.BezierConnection", remap = false)
public abstract class BezierConnectionMixin implements BezierBounds, TrackMeshCache.Holder {
    @Unique
    private TrackMeshCache.Slot[] icecream$trackSlots;

    @Shadow(remap = false)
    public abstract AABB getBounds();

    @Override
    public AABB icecream$getBounds() {
        return getBounds();
    }

    @Override
    public TrackMeshCache.Slot icecream$trackSlot(int pass) {
        TrackMeshCache.Slot[] slots = icecream$trackSlots;
        if (slots == null) {
            slots = icecream$trackSlots = new TrackMeshCache.Slot[2];
        }
        TrackMeshCache.Slot slot = slots[pass];
        if (slot == null) {
            slot = slots[pass] = new TrackMeshCache.Slot();
        }
        return slot;
    }
}
