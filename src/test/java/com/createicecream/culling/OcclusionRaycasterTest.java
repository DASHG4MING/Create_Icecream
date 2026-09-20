package com.createicecream.culling;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class OcclusionRaycasterTest {

    /** Tiny voxel world backed by a set of opaque positions. */
    static final class Grid implements OcclusionRaycaster.OpacitySource {
        final Set<Long> opaque = new HashSet<>();

        void set(int x, int y, int z) {
            opaque.add(key(x, y, z));
        }

        void fill(int x0, int y0, int z0, int x1, int y1, int z1) {
            for (int x = x0; x <= x1; x++)
                for (int y = y0; y <= y1; y++)
                    for (int z = z0; z <= z1; z++)
                        set(x, y, z);
        }

        @Override
        public boolean isOpaque(int x, int y, int z) {
            return opaque.contains(key(x, y, z));
        }

        static long key(int x, int y, int z) {
            return ((long) x & 0x1FFFFF) << 42 | ((long) y & 0x1FFFFF) << 21 | ((long) z & 0x1FFFFF);
        }
    }

    private static boolean visible(Grid g, double cx, double cy, double cz, int bx, int by, int bz) {
        OcclusionRaycaster r = new OcclusionRaycaster(g);
        r.setCamera(cx, cy, cz);
        return r.isBoxVisible(bx, by, bz, bx + 1, by + 1, bz + 1);
    }

    @Test
    void emptyWorldIsVisible() {
        assertTrue(visible(new Grid(), 0.5, 1.6, 0.5, 10, 1, 3));
    }

    @Test
    void solidWallHides() {
        Grid g = new Grid();
        g.fill(5, -20, -20, 5, 20, 20); // infinite-ish wall at x = 5
        assertFalse(visible(g, 0.5, 1.6, 0.5, 10, 1, 0));
    }

    @Test
    void wallWithHoleIsVisible() {
        Grid g = new Grid();
        g.fill(5, -20, -20, 5, 20, 20);
        g.opaque.remove(Grid.key(5, 1, 0)); // window in line of sight
        assertTrue(visible(g, 0.5, 1.6, 0.5, 10, 1, 0));
    }

    @Test
    void wallBehindTargetDoesNotHide() {
        Grid g = new Grid();
        g.fill(12, -20, -20, 12, 20, 20);
        assertTrue(visible(g, 0.5, 1.6, 0.5, 10, 1, 0));
    }

    @Test
    void targetEmbeddedInWallButFaceExposed() {
        Grid g = new Grid();
        // the target block itself is opaque (e.g. an encased gearbox) and surrounded on 5 sides
        g.fill(9, 0, -1, 11, 2, 1);
        g.opaque.remove(Grid.key(9, 1, 0)); // the face towards the camera is open
        assertTrue(visible(g, 0.5, 1.6, 0.5, 10, 1, 0));
    }

    @Test
    void targetFullyEnclosedIsHidden() {
        Grid g = new Grid();
        g.fill(9, 0, -1, 11, 2, 1); // 3x3x3 shell, target at the centre (also opaque)
        assertFalse(visible(g, 0.5, 1.6, 0.5, 10, 1, 0));
    }

    @Test
    void cameraInsideOpaqueBlockCanStillSee() {
        Grid g = new Grid();
        g.set(0, 1, 0); // spectator clipped into a block
        assertTrue(visible(g, 0.5, 1.6, 0.5, 10, 1, 0));
    }

    @Test
    void diagonalPeekAroundCorner() {
        Grid g = new Grid();
        // wall along z = 3 from x = -20..4; the sight line to the far corner of the target (10,1,6)
        // crosses z = 3 at x ~ 5.3, just past the wall's end
        g.fill(-20, -5, 3, 4, 5, 3);
        assertTrue(visible(g, 0.5, 1.6, 0.5, 10, 1, 6));
        // extend the wall past the target: now hidden
        g.fill(5, -5, 3, 30, 5, 3);
        assertFalse(visible(g, 0.5, 1.6, 0.5, 10, 1, 6));
    }

    @Test
    void cameraInsideBoxIsVisible() {
        Grid g = new Grid();
        g.fill(-5, -5, -5, 5, 5, 5);
        OcclusionRaycaster r = new OcclusionRaycaster(g);
        r.setCamera(0.5, 0.5, 0.5);
        assertTrue(r.isBoxVisible(0, 0, 0, 1, 1, 1));
    }

    @Test
    void largeBoxPartiallyVisible() {
        Grid g = new Grid();
        g.fill(5, -20, -20, 5, 20, 20);
        // single 1x1 window; only sight lines to z ~ 7.9..10 on the belt pass through it
        g.opaque.remove(Grid.key(5, 1, 4));
        OcclusionRaycaster r = new OcclusionRaycaster(g);
        r.setCamera(0.5, 1.6, 0.5);
        assertTrue(r.isBoxVisible(10, 1, 0, 11, 2, 12)); // 12-long belt behind the wall
    }

    @Test
    void negativeCoordinates() {
        Grid g = new Grid();
        g.fill(-6, -40, -20, -6, 20, 20);
        OcclusionRaycaster r = new OcclusionRaycaster(g);
        r.setCamera(-0.5, -30.4, -0.5);
        assertFalse(r.isBoxVisible(-12, -31, -1, -11, -30, 0));
        g.opaque.remove(Grid.key(-6, -31, -1));
        g.opaque.remove(Grid.key(-6, -30, -1));
        g.opaque.remove(Grid.key(-6, -31, 0));
        g.opaque.remove(Grid.key(-6, -30, 0));
        assertTrue(r.isBoxVisible(-12, -31, -1, -11, -30, 0));
    }

    @Test
    void sampleCountsAreBounded() {
        double[] out = new double[OcclusionRaycaster.MAX_BOX_BLOCKS + 2];
        int n = OcclusionRaycaster.fillSamples(out, 0, 1);
        assertTrue(n == 3 && out[0] > 0 && out[2] < 1);
        n = OcclusionRaycaster.fillSamples(out, 0, 16);
        assertTrue(n <= 17 && out[1] - out[0] <= 1.0); // half-block spacing, capped at 17 samples
        n = OcclusionRaycaster.fillSamples(out, 0, 40);
        assertTrue(n <= out.length && out[1] - out[0] <= 1.0); // big boxes (trains): still <= one block apart
        n = OcclusionRaycaster.fillSamples(out, 0, OcclusionRaycaster.MAX_BOX_BLOCKS);
        assertTrue(out[1] - out[0] <= 1.0);
    }

    @Test
    void everyOneBlockWindowIsFoundOnLongBelt() {
        // Slide a single window along a wall and check the long target is reported visible exactly when
        // some straight line from the camera through the window reaches it (brute force reference).
        int expectedVisible = 0;
        for (int wz = -6; wz <= 14; wz++) {
            Grid g = new Grid();
            g.fill(5, -20, -30, 5, 20, 30);
            g.opaque.remove(Grid.key(5, 1, wz));
            OcclusionRaycaster r = new OcclusionRaycaster(g);
            r.setCamera(0.5, 1.6, 0.5);
            boolean expected = bruteForce(0.5, 1.6, 0.5, wz);
            boolean actual = r.isBoxVisible(10, 1, 0, 11, 2, 12);
            if (expected) {
                expectedVisible++;
                assertTrue(actual); // a miss here would be a visible pop-out
            }
        }
        assertTrue(expectedVisible >= 2); // the sweep must actually exercise visible cases
    }

    /** Dense reference: is any point on the belt's x=10 face reachable through window (5,1,wz)? */
    private static boolean bruteForce(double cx, double cy, double cz, int wz) {
        for (double y = 1.0; y <= 2.0; y += 0.05) {
            for (double z = 0.0; z <= 12.0; z += 0.05) {
                double t = (5.5 - cx) / (10 - cx); // middle of the wall voxel
                double yy = cy + t * (y - cy), zz = cz + t * (z - cz);
                double t0 = (5 - cx) / (10 - cx), t1 = (6 - cx) / (10 - cx);
                double y0 = cy + t0 * (y - cy), z0 = cz + t0 * (z - cz);
                double y1 = cy + t1 * (y - cy), z1 = cz + t1 * (z - cz);
                if (Math.floor(y0) == 1 && Math.floor(y1) == 1 && Math.floor(yy) == 1
                        && Math.floor(z0) == wz && Math.floor(z1) == wz && Math.floor(zz) == wz) {
                    return true;
                }
            }
        }
        return false;
    }
}
