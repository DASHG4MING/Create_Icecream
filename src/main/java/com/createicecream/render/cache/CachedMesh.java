package com.createicecream.render.cache;

import java.lang.ref.Cleaner;

import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.vertex.VertexFormat;

/**
 * Finished vertex data in the target vertex format, with positions relative to the pose's origin (the
 * translation part of the pose it was recorded with is zero). Replaying only has to add the current
 * translation.
 * <p>
 * The native block is <b>reused</b> when the mesh is recorded again (light refresh, animation update), so
 * steady-state re-recording allocates nothing: no garbage, no direct-memory pressure, no {@code System.gc()}
 * stalls. The memory is freed by {@link #free()} or, as a fallback, when the owner is garbage collected.
 */
public final class CachedMesh {
    private static final Cleaner CLEANER = Cleaner.create();

    /** Owns the native memory; must not reference the CachedMesh (Cleaner rule). */
    private static final class Native implements Runnable {
        volatile long address;
        volatile long capacity;

        @Override
        public synchronized void run() {
            if (address != 0L) {
                MemoryUtil.nmemFree(address);
                CacheStats.BYTES.addAndGet(-capacity);
                address = 0L;
                capacity = 0L;
            }
        }
    }

    private final Native mem = new Native();
    public VertexFormat format;
    public int vertexCount;

    public CachedMesh() {
        CLEANER.register(this, mem);
    }

    /** Copies {@code vertexCount} vertices from {@code src}, growing (or shrinking a lot) the block if needed. */
    public void set(VertexFormat format, long src, int vertexCount) {
        long bytes = (long) vertexCount * format.getVertexSize();
        long cap = mem.capacity;
        if (bytes > cap || (cap > 64 * 1024 && bytes < cap / 4)) {
            long newCap = Math.max(bytes, 256);
            if (bytes > cap) {
                newCap = Math.max(newCap, cap + cap / 2);
            }
            synchronized (mem) {
                long addr = MemoryUtil.nmemRealloc(mem.address, newCap);
                if (addr == 0L) {
                    throw new OutOfMemoryError("CachedMesh");
                }
                CacheStats.BYTES.addAndGet(newCap - mem.capacity);
                mem.address = addr;
                mem.capacity = newCap;
            }
        }
        if (bytes > 0) {
            MemoryUtil.memCopy(src, mem.address, bytes);
        }
        this.format = format;
        this.vertexCount = vertexCount;
    }

    public long address() {
        return mem.address;
    }

    public int bytes() {
        return format == null ? 0 : vertexCount * format.getVertexSize();
    }

    /** Releases the native memory now. The mesh is empty afterwards but can be {@link #set} again. */
    public void free() {
        mem.run();
        vertexCount = 0;
    }
}
