package com.createicecream.culling;

/**
 * Voxel ray tracer that answers "can the camera see any part of this box?".
 * <p>
 * Same idea as the OcclusionCulling library used by Entity Culling: cast rays from the camera to sample
 * points on the faces of the target box that face the camera, walking the voxel grid with a 3D DDA
 * (Amanatides &amp; Woo) and stopping at the first opaque full block. The box is visible as soon as one ray
 * gets through.
 * <p>
 * Deliberately free of Minecraft types so it can be unit-tested; the world is accessed through
 * {@link OpacitySource}. Not thread-safe: one instance per culling thread.
 * <p>
 * The algorithm always errs towards "visible": unknown chunks, exceptions and degenerate input all return
 * {@code true}. A false "visible" only costs a bit of performance; a false "hidden" would be a visual bug.
 */
public final class OcclusionRaycaster {

    @FunctionalInterface
    public interface OpacitySource {
        /** @return true if the block at the given position fully blocks vision (opaque full cube). */
        boolean isOpaque(int x, int y, int z);
    }

    /** Sample points are pushed this far outside the face they lie on. */
    private static final double FACE_NUDGE = 0.02;
    /**
     * Upper bound of samples per face axis, to bound the cost for large boxes. Samples are spaced at most
     * one block apart up to this limit: a one-block gap in an occluder is always closer to the camera than
     * the target, so its projection onto the target face is at least one block wide and cannot fall between
     * two samples. Half-block spacing is used up to 17 samples; larger boxes (trains) fall back to one-block
     * spacing so the guarantee holds for any size, up to {@link #MAX_BOX_BLOCKS}.
     */
    private static final int MAX_SAMPLES_PER_AXIS = 17;
    /** Largest box extent (per axis) that is ever traced; callers treat anything larger as visible. */
    public static final int MAX_BOX_BLOCKS = 64;
    private static final int SAMPLE_CAPACITY = MAX_BOX_BLOCKS + 2;

    private final OpacitySource world;

    private double camX, camY, camZ;

    // target voxel range of the box currently being tested (inclusive)
    private int tMinX, tMinY, tMinZ, tMaxX, tMaxY, tMaxZ;

    private final double[] us = new double[SAMPLE_CAPACITY];
    private final double[] vs = new double[SAMPLE_CAPACITY];

    /** Number of rays cast since construction (statistics only). */
    public long raysCast;

    public OcclusionRaycaster(OpacitySource world) {
        this.world = world;
    }

    public void setCamera(double x, double y, double z) {
        this.camX = x;
        this.camY = y;
        this.camZ = z;
    }

    /**
     * @return true if any part of the box [min, max] may be visible from the camera.
     */
    public boolean isBoxVisible(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (!(minX <= maxX && minY <= maxY && minZ <= maxZ)) {
            return true; // NaN or inverted box: don't guess
        }
        if (maxX - minX > MAX_BOX_BLOCKS + 1 || maxY - minY > MAX_BOX_BLOCKS + 1 || maxZ - minZ > MAX_BOX_BLOCKS + 1) {
            return true; // too big to sample densely enough
        }
        boolean outX = camX < minX || camX > maxX;
        boolean outY = camY < minY || camY > maxY;
        boolean outZ = camZ < minZ || camZ > maxZ;
        if (!outX && !outY && !outZ) {
            return true; // camera inside the box
        }

        tMinX = floor(minX);
        tMinY = floor(minY);
        tMinZ = floor(minZ);
        tMaxX = Math.max(tMinX, floor(maxX - 1.0e-7));
        tMaxY = Math.max(tMinY, floor(maxY - 1.0e-7));
        tMaxZ = Math.max(tMinZ, floor(maxZ - 1.0e-7));

        // Camera voxel already inside the target's voxels -> nothing can be in between.
        if (inTarget(floor(camX), floor(camY), floor(camZ))) {
            return true;
        }

        // Test the faces that point towards the camera. Each face is sampled on a grid, centre first.
        if (camX < minX && faceVisible(0, minX - FACE_NUDGE, minY, maxY, minZ, maxZ)) return true;
        if (camX > maxX && faceVisible(0, maxX + FACE_NUDGE, minY, maxY, minZ, maxZ)) return true;
        if (camY < minY && faceVisible(1, minY - FACE_NUDGE, minX, maxX, minZ, maxZ)) return true;
        if (camY > maxY && faceVisible(1, maxY + FACE_NUDGE, minX, maxX, minZ, maxZ)) return true;
        if (camZ < minZ && faceVisible(2, minZ - FACE_NUDGE, minX, maxX, minY, maxY)) return true;
        if (camZ > maxZ && faceVisible(2, maxZ + FACE_NUDGE, minX, maxX, minY, maxY)) return true;
        return false;
    }

    /**
     * @param axis  0 = face perpendicular to X (u = y, v = z), 1 = Y (u = x, v = z), 2 = Z (u = x, v = y)
     * @param plane coordinate of the (nudged) face plane on {@code axis}
     */
    private boolean faceVisible(int axis, double plane, double uMin, double uMax, double vMin, double vMax) {
        int nu = fillSamples(us, uMin, uMax);
        int nv = fillSamples(vs, vMin, vMax);

        // centre first: statistically the most likely sample to be visible
        if (sampleVisible(axis, plane, (uMin + uMax) * 0.5, (vMin + vMax) * 0.5)) {
            return true;
        }
        for (int i = 0; i < nu; i++) {
            for (int j = 0; j < nv; j++) {
                if (sampleVisible(axis, plane, us[i], vs[j])) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean sampleVisible(int axis, double plane, double u, double v) {
        return switch (axis) {
            case 0 -> traceClear(plane, u, v);
            case 1 -> traceClear(u, plane, v);
            default -> traceClear(u, v, plane);
        };
    }

    /** Evenly spaced samples across [min, max], slightly inset from the edges. */
    static int fillSamples(double[] out, double min, double max) {
        double size = max - min;
        double inset = Math.min(0.05, size * 0.25);
        int n;
        // half-block spacing (fuzzing against a dense reference showed ~2x fewer false "hidden" results
        // than one-block spacing at a negligible cost), degrading to <= 1 block for boxes up to 16 blocks
        int halfBlock = (int) Math.ceil(size * 2) + 1;
        int oneBlock = (int) Math.ceil(size) + 1;
        n = Math.max(3, Math.min(halfBlock, Math.max(MAX_SAMPLES_PER_AXIS, oneBlock)));
        n = Math.min(n, out.length);
        double a = min + inset;
        double b = max - inset;
        for (int i = 0; i < n; i++) {
            out[i] = a + (b - a) * i / (n - 1);
        }
        return n;
    }

    /**
     * Walks the voxels between the camera and the target point.
     *
     * @return true if no opaque block blocks the segment (target reached)
     */
    boolean traceClear(double tx, double ty, double tz) {
        raysCast++;
        int x = floor(camX), y = floor(camY), z = floor(camZ);
        final int ex = floor(tx), ey = floor(ty), ez = floor(tz);

        double dx = tx - camX, dy = ty - camY, dz = tz - camZ;

        int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        int stepY = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
        int stepZ = dz > 0 ? 1 : (dz < 0 ? -1 : 0);

        // parametric distance (t in [0,1] along the segment) to cross one voxel on each axis
        double tDeltaX = stepX != 0 ? Math.abs(1.0 / dx) : Double.POSITIVE_INFINITY;
        double tDeltaY = stepY != 0 ? Math.abs(1.0 / dy) : Double.POSITIVE_INFINITY;
        double tDeltaZ = stepZ != 0 ? Math.abs(1.0 / dz) : Double.POSITIVE_INFINITY;

        // parametric distance to the first voxel boundary on each axis
        double tMaxX = stepX > 0 ? (x + 1 - camX) * tDeltaX : stepX < 0 ? (camX - x) * tDeltaX : Double.POSITIVE_INFINITY;
        double tMaxY = stepY > 0 ? (y + 1 - camY) * tDeltaY : stepY < 0 ? (camY - y) * tDeltaY : Double.POSITIVE_INFINITY;
        double tMaxZ = stepZ > 0 ? (z + 1 - camZ) * tDeltaZ : stepZ < 0 ? (camZ - z) * tDeltaZ : Double.POSITIVE_INFINITY;

        // Allow the ray to leave opaque blocks the camera starts in (spectator / camera clipping into walls).
        boolean clipping = true;
        int steps = Math.abs(ex - x) + Math.abs(ey - y) + Math.abs(ez - z) + 1;

        for (int i = 0; i <= steps; i++) {
            if (inTarget(x, y, z)) {
                return true; // reached the target's own voxels: the target itself never occludes
            }
            if (world.isOpaque(x, y, z)) {
                if (!clipping) {
                    return false;
                }
            } else {
                clipping = false;
            }
            if (x == ex && y == ey && z == ez) {
                return true;
            }

            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    x += stepX;
                    tMaxX += tDeltaX;
                } else {
                    z += stepZ;
                    tMaxZ += tDeltaZ;
                }
            } else {
                if (tMaxY < tMaxZ) {
                    y += stepY;
                    tMaxY += tDeltaY;
                } else {
                    z += stepZ;
                    tMaxZ += tDeltaZ;
                }
            }
        }
        return true; // numerical safety net: never report "hidden" on a walk we couldn't finish
    }

    private boolean inTarget(int x, int y, int z) {
        return x >= tMinX && x <= tMaxX && y >= tMinY && y <= tMaxY && z >= tMinZ && z <= tMaxZ;
    }

    private static int floor(double d) {
        int i = (int) d;
        return d < i ? i - 1 : i;
    }
}
