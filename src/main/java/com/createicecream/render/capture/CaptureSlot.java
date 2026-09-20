package com.createicecream.render.capture;

import com.createicecream.render.cache.CachedMesh;

import net.minecraft.client.renderer.RenderType;

/**
 * The last captured output of one block entity or contraption in one render pass: one finished mesh per
 * render type, positions relative to the object's pose origin.
 */
public final class CaptureSlot {
    RenderType[] types = new RenderType[2];
    CachedMesh[] meshes = new CachedMesh[2];
    int count;
    boolean valid;
    int recordFrame;
    int failedUntil;
    final float[] linear = new float[9];

    void ensureCapacity(int n) {
        if (n > types.length) {
            int size = Math.max(n, types.length * 2);
            types = java.util.Arrays.copyOf(types, size);
            meshes = java.util.Arrays.copyOf(meshes, size);
        }
    }

    /** Releases all native memory; the slot can be captured again later. */
    void free() {
        for (int i = 0; i < meshes.length; i++) {
            if (meshes[i] != null) {
                meshes[i].free();
            }
            types[i] = null;
        }
        count = 0;
        valid = false;
    }
}
