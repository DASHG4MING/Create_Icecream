package com.createicecream.trains;

import net.minecraft.world.phys.AABB;

/** Duck interface mixed into Create's {@code BezierConnection} (see {@code BezierConnectionMixin}). */
public interface BezierBounds {
    /** World-space bounds of the curve's centre line (Create's own {@code BezierConnection#getBounds}). */
    AABB icecream$getBounds();
}
