package com.createicecream.mixin.create;

import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.createicecream.config.OptConfig;
import com.createicecream.render.cache.FrameClock;
import com.createicecream.debug.OptStats;
import com.createicecream.trains.BezierBounds;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

/**
 * Create 6.0.10's {@code TrackBlockEntity#getRenderBoundingBox()} returns {@code AABB.INFINITE}, and its
 * renderer asks to be drawn up to 192 blocks away. With Flywheel off (any shader pack), every curved track
 * in that radius is rebuilt vertex by vertex every frame, and again for the shadow map, even when it is
 * directly behind the camera. The profile of a shader + Create setup showed {@code renderBezierTurn} alone at
 * ~4% of the render thread.
 * <p>
 * The curves' real extent is known: each {@code BezierConnection} caches the bounds of its centre line. We
 * return the union of those (plus a margin for rails, ties and girders), so NeoForge's normal BER frustum
 * check and Iris' shadow frustum can skip curves that are not in view. Nothing changes for curves that are
 * in view.
 */
@Mixin(targets = "com.simibubi.create.content.trains.track.TrackBlockEntity", remap = false)
public abstract class TrackBlockEntityMixin {
    /** Rails are ~1.5 blocks either side of the centre line; girders hang ~2 blocks below it. */
    private static final double MARGIN = 3.0;

    @Shadow(remap = false)
    Map<BlockPos, ?> connections;

    /** The box is asked for several times per frame (frustum checks, shadow pass): compute it once. */
    @Unique
    private AABB icecream$cachedBox;
    @Unique
    private int icecream$cachedFrame = Integer.MIN_VALUE;

    @Inject(method = "getRenderBoundingBox", at = @At("HEAD"), cancellable = true, remap = false)
    private void icecream$finiteBounds(CallbackInfoReturnable<AABB> cir) {
        if (!OptConfig.enabled || !OptConfig.finiteTrackBounds) {
            return;
        }
        int frame = FrameClock.frame;
        if (icecream$cachedFrame == frame && icecream$cachedBox != null) {
            cir.setReturnValue(icecream$cachedBox);
            return;
        }
        Map<BlockPos, ?> map = connections;
        if (map == null) {
            return;
        }
        try {
            AABB box = new AABB(((BlockEntity) (Object) this).getBlockPos());
            for (Object connection : map.values()) {
                if (!(connection instanceof BezierBounds bounds)) {
                    return; // unexpected type: keep Create's behaviour
                }
                AABB curve = bounds.icecream$getBounds();
                if (curve == null) {
                    return;
                }
                box = box.minmax(curve);
            }
            AABB result = box.inflate(MARGIN);
            icecream$cachedBox = result;
            icecream$cachedFrame = frame;
            cir.setReturnValue(result);
            OptStats.TRACK_BOUNDS_FIXED.increment();
        } catch (Throwable t) {
            // e.g. a connection mid-update: fall back to Create's infinite box for this call
        }
    }
}
