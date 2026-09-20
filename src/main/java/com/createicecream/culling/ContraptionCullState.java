package com.createicecream.culling;

/**
 * Occlusion state of one contraption entity (train carriage, elevator, bearing...). The render thread writes
 * the current box every frame; the culling thread reads it and writes {@link #occluded}.
 */
public final class ContraptionCullState {
    volatile double minX, minY, minZ, maxX, maxY, maxZ;
    volatile boolean hasBox;
    volatile boolean occluded;
    volatile int lastSeen;
    boolean listed;
    long visibleUntil;

    public boolean isOccluded() {
        return occluded;
    }
}
