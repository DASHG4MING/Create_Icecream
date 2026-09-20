package com.createicecream.mixin.minecraft;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import com.createicecream.culling.CullableBlockEntity;
import com.createicecream.render.capture.CaptureHolder;
import com.createicecream.render.capture.CaptureSlot;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

/**
 * Adds a few fields of culling state to every block entity (the same approach Entity Culling uses).
 * Only Create block entities ever get their state touched; for everything else this is ~40 bytes of zeros.
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityCullStateMixin implements CullableBlockEntity, CaptureHolder {
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

    @Unique
    private volatile boolean icecream$occluded;
    @Unique
    private volatile int icecream$lastSeen;
    @Unique
    private volatile boolean icecream$queued;
    @Unique
    private boolean icecream$listed;
    @Unique
    private volatile AABB icecream$cullBox;
    @Unique
    private volatile AABB icecream$renderBox;
    @Unique
    private long icecream$visibleUntil;
    @Unique
    private volatile int icecream$forceFrames;
    @Unique
    private volatile boolean icecream$neverCull;

    @Override
    public boolean icecream$isOccluded() {
        return icecream$occluded;
    }

    @Override
    public void icecream$setOccluded(boolean occluded) {
        icecream$occluded = occluded;
    }

    @Override
    public int icecream$getLastSeen() {
        return icecream$lastSeen;
    }

    @Override
    public void icecream$setLastSeen(int tick) {
        icecream$lastSeen = tick;
    }

    @Override
    public boolean icecream$isQueued() {
        return icecream$queued;
    }

    @Override
    public void icecream$setQueued(boolean queued) {
        icecream$queued = queued;
    }

    @Override
    public boolean icecream$isListed() {
        return icecream$listed;
    }

    @Override
    public void icecream$setListed(boolean listed) {
        icecream$listed = listed;
    }

    @Override
    public @Nullable AABB icecream$getCullBox() {
        return icecream$cullBox;
    }

    @Override
    public void icecream$setCullBox(@Nullable AABB box) {
        icecream$cullBox = box;
    }

    @Override
    public @Nullable AABB icecream$getRenderBox() {
        return icecream$renderBox;
    }

    @Override
    public void icecream$setRenderBox(@Nullable AABB box) {
        icecream$renderBox = box;
    }

    @Override
    public long icecream$getVisibleUntil() {
        return icecream$visibleUntil;
    }

    @Override
    public void icecream$setVisibleUntil(long nanos) {
        icecream$visibleUntil = nanos;
    }

    @Override
    public int icecream$getForceFrames() {
        return icecream$forceFrames;
    }

    @Override
    public void icecream$setForceFrames(int frames) {
        icecream$forceFrames = frames;
    }

    @Override
    public boolean icecream$isNeverCull() {
        return icecream$neverCull;
    }

    @Override
    public void icecream$setNeverCull(boolean neverCull) {
        icecream$neverCull = neverCull;
    }
}
