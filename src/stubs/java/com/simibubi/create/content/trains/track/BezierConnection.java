// COMPILE-ONLY STUB. Mirrors the members of Create 6.0.10's class that Create IceCream uses.
// It is NOT packaged: at runtime the real class from Create is used.
package com.simibubi.create.content.trains.track;

import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

public class BezierConnection {
    public final Couple<BlockPos> bePositions = null;
    public final boolean hasGirder = false;

    public boolean isPrimary() { throw new AssertionError("stub"); }

    public AABB getBounds() { throw new AssertionError("stub"); }

    public SegmentAngles getBakedSegments() { throw new AssertionError("stub"); }

    public GirderAngles getBakedGirders() { throw new AssertionError("stub"); }

    public static class SegmentAngles {
        public final int length = 0;
        public final BlockPos[] lightPosition = null;
    }

    public static class GirderAngles {
        public final int length = 0;
        public final BlockPos[] lightPosition = null;
    }
}
