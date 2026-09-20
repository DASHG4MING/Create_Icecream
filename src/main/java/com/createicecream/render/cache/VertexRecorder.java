package com.createicecream.render.cache;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter;

/**
 * A vertex sink that only accepts Sodium's bulk {@link VertexBufferWriter#push} calls (which is how the fast
 * renderer emits vertices) and appends them to a growable native buffer. The per-vertex
 * {@link VertexConsumer} methods throw: if anything tries to draw through them while recording, the
 * recording is aborted and that model simply isn't cached.
 */
public final class VertexRecorder implements VertexConsumer, VertexBufferWriter {
    private VertexFormat format;
    private long buffer;
    private long capacity;
    private int vertexCount;

    public VertexFormat format() {
        return format;
    }

    public void begin(VertexFormat format) {
        this.format = format;
        this.vertexCount = 0;
    }

    @Override
    public void push(MemoryStack stack, long ptr, int count, VertexFormat format) {
        if (format != this.format) {
            throw new IllegalStateException("Vertex format changed while recording");
        }
        int stride = format.getVertexSize();
        long needed = (long) (vertexCount + count) * stride;
        if (needed > capacity) {
            long newCapacity = Math.max(needed, Math.max(64 * 1024, capacity * 2));
            buffer = MemoryUtil.nmemRealloc(buffer, newCapacity);
            if (buffer == 0L) {
                capacity = 0;
                vertexCount = 0;
                throw new OutOfMemoryError("VertexRecorder");
            }
            capacity = newCapacity;
        }
        MemoryUtil.memCopy(ptr, buffer + (long) vertexCount * stride, (long) count * stride);
        vertexCount += count;
    }

    public CachedMesh finish() {
        return finishInto(null);
    }

    /** Copies the recording into {@code reuse} (or a new mesh), reusing its native memory. */
    public CachedMesh finishInto(CachedMesh reuse) {
        CachedMesh mesh = reuse != null ? reuse : new CachedMesh();
        mesh.set(format, buffer, vertexCount);
        vertexCount = 0;
        // don't keep a huge buffer around after recording a big contraption
        if (capacity > 8L * 1024 * 1024) {
            MemoryUtil.nmemFree(buffer);
            buffer = 0L;
            capacity = 0;
        }
        return mesh;
    }

    @Override
    public boolean canUseIntrinsics() {
        return true;
    }

    // ---- per-vertex API: not supported while recording ----

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("VertexRecorder only accepts bulk vertex data");
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        throw unsupported();
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        throw unsupported();
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        throw unsupported();
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        throw unsupported();
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        throw unsupported();
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        throw unsupported();
    }
}
