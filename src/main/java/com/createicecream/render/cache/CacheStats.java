package com.createicecream.render.cache;

import java.util.concurrent.atomic.AtomicLong;

/** Counters for the F3 overlay (render thread only, except {@link #BYTES}). */
public final class CacheStats {
    public static final AtomicLong BYTES = new AtomicLong();
    public static long hits, records, misses;
    public static long trackHits, trackRecords;
    public static long throttleReplays, throttleCaptures, throttleFailures;
    public static volatile long hitsPerSec, recordsPerSec, trackHitsPerSec, trackRecordsPerSec;
    public static volatile long throttleReplaysPerSec, throttleCapturesPerSec, throttleFailuresPerSec;

    private static long lastSnapshot = System.nanoTime();

    private CacheStats() {
    }

    public static void onFrame() {
        long now = System.nanoTime();
        if (now - lastSnapshot >= 1_000_000_000L) {
            lastSnapshot = now;
            hitsPerSec = hits;
            recordsPerSec = records;
            trackHitsPerSec = trackHits;
            trackRecordsPerSec = trackRecords;
            throttleReplaysPerSec = throttleReplays;
            throttleCapturesPerSec = throttleCaptures;
            throttleFailuresPerSec = throttleFailures;
            hits = records = misses = trackHits = trackRecords = 0;
            throttleReplays = throttleCaptures = throttleFailures = 0;
        }
    }
}
