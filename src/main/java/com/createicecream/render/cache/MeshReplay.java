package com.createicecream.render.cache;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

import net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter;

/**
 * Writes a {@link CachedMesh} into a real vertex buffer, adding the current pose translation to every
 * position. That is a memcpy plus three float adds per vertex, instead of matrix transforms, lighting,
 * colour, normal and (with Iris) tangent computation.
 * <p>
 * With {@code cull}, quads facing away from the camera are dropped on the way, using exactly the test the
 * fast renderer applies to freshly drawn models. Recorded meshes contain every face (they must be valid from
 * any camera position); culling at replay time halves what is copied and uploaded to the GPU again.
 */
public final class MeshReplay {
    /** Scratch space; {@link VertexBufferWriter#push} is allowed to modify its input, so never push the cache itself. */
    private static final int SCRATCH_BYTES = 256 * 1024;
    private static final long SCRATCH = MemoryUtil.nmemAlignedAlloc(64, SCRATCH_BYTES);
    private static final MemoryStack STACK = MemoryStack.create(64 * 1024);

    private static VertexFormat lastFormat;
    private static int lastNormalOffset = -1;

    private MeshReplay() {
    }

    public static void replay(CachedMesh mesh, VertexBufferWriter writer, float tx, float ty, float tz) {
        replay(mesh, writer, tx, ty, tz, false);
    }

    /** Positions are the first three floats of every supported format (BLOCK, NEW_ENTITY, Iris TERRAIN/ENTITY). */
    public static void replay(CachedMesh mesh, VertexBufferWriter writer, float tx, float ty, float tz, boolean cull) {
        if (cull) {
            int normalOffset = normalOffset(mesh.format);
            if (normalOffset >= 0) {
                replayCulled(mesh, writer, tx, ty, tz, normalOffset);
                return;
            }
        }
        int stride = mesh.format.getVertexSize();
        int perChunk = (SCRATCH_BYTES / stride) & ~3; // whole quads per push
        int total = mesh.vertexCount;
        long src = mesh.address();
        for (int done = 0; done < total; ) {
            int n = Math.min(perChunk, total - done);
            long bytes = (long) n * stride;
            MemoryUtil.memCopy(src + (long) done * stride, SCRATCH, bytes);
            if (tx != 0f || ty != 0f || tz != 0f) {
                for (long p = SCRATCH, end = SCRATCH + bytes; p < end; p += stride) {
                    MemoryUtil.memPutFloat(p, MemoryUtil.memGetFloat(p) + tx);
                    MemoryUtil.memPutFloat(p + 4, MemoryUtil.memGetFloat(p + 4) + ty);
                    MemoryUtil.memPutFloat(p + 8, MemoryUtil.memGetFloat(p + 8) + tz);
                }
            }
            push(writer, n, mesh.format);
            done += n;
        }
    }

    private static void replayCulled(CachedMesh mesh, VertexBufferWriter writer, float tx, float ty, float tz, int normalOffset) {
        VertexFormat format = mesh.format;
        int stride = format.getVertexSize();
        int quadBytes = stride * 4;
        int quads = mesh.vertexCount / 4;
        long src = mesh.address();
        long out = SCRATCH;
        long limit = SCRATCH + ((long) (SCRATCH_BYTES / quadBytes)) * quadBytes;
        for (int q = 0; q < quads; q++) {
            long v0 = src + (long) q * quadBytes;
            long v1 = v0 + stride;
            long v2 = v1 + stride;
            float x0 = MemoryUtil.memGetFloat(v0) + tx, y0 = MemoryUtil.memGetFloat(v0 + 4) + ty, z0 = MemoryUtil.memGetFloat(v0 + 8) + tz;
            float x1 = MemoryUtil.memGetFloat(v1) + tx, y1 = MemoryUtil.memGetFloat(v1 + 4) + ty, z1 = MemoryUtil.memGetFloat(v1 + 8) + tz;
            float x2 = MemoryUtil.memGetFloat(v2) + tx, y2 = MemoryUtil.memGetFloat(v2 + 4) + ty, z2 = MemoryUtil.memGetFloat(v2 + 8) + tz;
            float ex = x1 - x0, ey = y1 - y0, ez = z1 - z0;
            float fx = x2 - x0, fy = y2 - y0, fz = z2 - z0;
            float cx = ey * fz - ez * fy;
            float cy = ez * fx - ex * fz;
            float cz = ex * fy - ey * fx;
            float nx = MemoryUtil.memGetByte(v0 + normalOffset);
            float ny = MemoryUtil.memGetByte(v0 + normalOffset + 1);
            float nz = MemoryUtil.memGetByte(v0 + normalOffset + 2);
            if (nx * cx + ny * cy + nz * cz < 0) {
                cx = -cx; cy = -cy; cz = -cz;
            }
            if (cx * (x0 + x2) + cy * (y0 + y2) + cz * (z0 + z2) > 0) {
                continue; // faces away from the camera
            }
            MemoryUtil.memCopy(v0, out, quadBytes);
            for (long p = out, end = out + quadBytes; p < end; p += stride) {
                MemoryUtil.memPutFloat(p, MemoryUtil.memGetFloat(p) + tx);
                MemoryUtil.memPutFloat(p + 4, MemoryUtil.memGetFloat(p + 4) + ty);
                MemoryUtil.memPutFloat(p + 8, MemoryUtil.memGetFloat(p + 8) + tz);
            }
            out += quadBytes;
            if (out >= limit) {
                push(writer, (int) ((out - SCRATCH) / stride), format);
                out = SCRATCH;
            }
        }
        if (out > SCRATCH) {
            push(writer, (int) ((out - SCRATCH) / stride), format);
        }
    }

    private static void push(VertexBufferWriter writer, int vertices, VertexFormat format) {
        STACK.push();
        try {
            writer.push(STACK, SCRATCH, vertices, format);
        } finally {
            STACK.pop();
        }
    }

    private static int normalOffset(VertexFormat format) {
        if (format != lastFormat) {
            lastFormat = format;
            lastNormalOffset = format.getElements().contains(VertexFormatElement.NORMAL)
                    ? format.getOffset(VertexFormatElement.NORMAL) : -1;
        }
        return lastNormalOffset;
    }
}
