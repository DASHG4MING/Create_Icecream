package com.createicecream.culling;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.phys.AABB;

/**
 * Per-block-entity culling state, mixed into {@link net.minecraft.world.level.block.entity.BlockEntity}.
 * <p>
 * Thread ownership:
 * <ul>
 *     <li>render thread + Flywheel workers: read {@code occluded}/{@code renderBox}, write {@code lastSeen}/{@code queued}/{@code forceFrames}</li>
 *     <li>client (main) thread tick: owns {@code listed}, writes {@code cullBox}/{@code renderBox}</li>
 *     <li>culling thread: owns {@code visibleUntil}, writes {@code occluded}</li>
 * </ul>
 */
public interface CullableBlockEntity {
    boolean icecream$isOccluded();

    void icecream$setOccluded(boolean occluded);

    int icecream$getLastSeen();

    void icecream$setLastSeen(int tick);

    boolean icecream$isQueued();

    void icecream$setQueued(boolean queued);

    boolean icecream$isListed();

    void icecream$setListed(boolean listed);

    /** Box used by the occlusion tracer, or null if this block entity must not be culled. */
    @Nullable
    AABB icecream$getCullBox();

    void icecream$setCullBox(@Nullable AABB box);

    /** Render bounds (world space) used for frustum gating of visuals, or null if unknown / unbounded. */
    @Nullable
    AABB icecream$getRenderBox();

    void icecream$setRenderBox(@Nullable AABB box);

    long icecream$getVisibleUntil();

    void icecream$setVisibleUntil(long nanos);

    /** Frames during which the visual must be updated regardless of gating (after creation / state updates). */
    int icecream$getForceFrames();

    void icecream$setForceFrames(int frames);

    /** Set by the visual gate for visuals whose geometry reaches far outside the block. */
    boolean icecream$isNeverCull();

    void icecream$setNeverCull(boolean neverCull);
}
