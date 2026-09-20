package com.createicecream.debug;

import java.util.concurrent.atomic.LongAdder;

/**
 * Lock-free counters (written from the render thread, Flywheel workers and the culling thread),
 * snapshotted once per second for the F3 overlay.
 */
public final class OptStats {
    public static final LongAdder BER_RENDERED = new LongAdder();
    public static final LongAdder BER_CULLED = new LongAdder();
    public static final LongAdder BER_FRUSTUM = new LongAdder();
    public static final LongAdder SHADOW_BER_SKIPPED = new LongAdder();
    public static final LongAdder SHADOW_CONTRAPTION_SKIPPED = new LongAdder();
    public static final LongAdder TRACK_BOUNDS_FIXED = new LongAdder();
    public static final LongAdder VISUAL_UPDATED = new LongAdder();
    public static final LongAdder VISUAL_SKIP_OCCLUDED = new LongAdder();
    public static final LongAdder VISUAL_SKIP_FRUSTUM = new LongAdder();
    public static final LongAdder VISUAL_SKIP_DISTANCE = new LongAdder();

    // per-second snapshots (average per frame / per second)
    public static volatile long berRenderedPerSec, berCulledPerSec, berFrustumPerSec;
    public static volatile long shadowBerSkippedPerSec, shadowContraptionSkippedPerSec, trackBoundsPerSec;
    public static volatile long visualUpdatedPerSec, visualOccludedPerSec, visualFrustumPerSec, visualDistancePerSec;
    public static volatile int framesLastSecond;

    // culling thread
    public static volatile int tracked;
    public static volatile int occludedNow;
    public static volatile double lastPassMs;
    public static volatile long raysLastPass;

    private static long lastSnapshot = System.nanoTime();
    private static int frames;

    private OptStats() {
    }

    /** Called once per frame on the render thread. */
    public static void onFrame() {
        frames++;
        long now = System.nanoTime();
        if (now - lastSnapshot >= 1_000_000_000L) {
            lastSnapshot = now;
            framesLastSecond = frames;
            frames = 0;
            berRenderedPerSec = BER_RENDERED.sumThenReset();
            berCulledPerSec = BER_CULLED.sumThenReset();
            berFrustumPerSec = BER_FRUSTUM.sumThenReset();
            visualUpdatedPerSec = VISUAL_UPDATED.sumThenReset();
            shadowBerSkippedPerSec = SHADOW_BER_SKIPPED.sumThenReset();
            shadowContraptionSkippedPerSec = SHADOW_CONTRAPTION_SKIPPED.sumThenReset();
            trackBoundsPerSec = TRACK_BOUNDS_FIXED.sumThenReset();
            visualOccludedPerSec = VISUAL_SKIP_OCCLUDED.sumThenReset();
            visualFrustumPerSec = VISUAL_SKIP_FRUSTUM.sumThenReset();
            visualDistancePerSec = VISUAL_SKIP_DISTANCE.sumThenReset();
        }
    }
}
