package com.createicecream.render.capture;

import org.lwjgl.system.MemoryUtil;

import com.createicecream.mixin.minecraft.BufferBuilderAccessor;
import com.createicecream.render.cache.CachedMesh;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

import net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

/**
 * A buffer source that records everything a renderer draws into private vanilla {@link BufferBuilder}s, one
 * per render type. Because they are real BufferBuilders, Iris/Sodium extend and fill them exactly like the
 * game's own buffers (extended vertex formats, tangents, entity ids), so the captured bytes can later be
 * copied verbatim into the real buffers.
 * <p>
 * Anything that can't be captured (non-quad render types, buffers that aren't plain BufferBuilders, e.g.
 * outline or crumbling wrappers) is drawn straight into the real buffer instead and the capture is marked
 * incomplete: the current frame is still correct, the object is just not throttled.
 * <p>
 * Render thread only; never nested.
 */
final class CaptureSession implements MultiBufferSource {
    static final CaptureSession INSTANCE = new CaptureSession();
    private static final int MAX_TYPES = 32;

    private final RenderType[] types = new RenderType[MAX_TYPES];
    private final BufferBuilder[] builders = new BufferBuilder[MAX_TYPES];
    private final VertexFormat[] formats = new VertexFormat[MAX_TYPES];
    private final ByteBufferBuilder[] pool = new ByteBufferBuilder[MAX_TYPES];
    private int count;
    private MultiBufferSource delegate;
    private float tx, ty, tz;
    boolean active;
    boolean incomplete;

    private CaptureSession() {
    }

    /** @param tx,ty,tz the object's real camera-relative position (the capture pose has none) */
    void begin(MultiBufferSource delegate, float tx, float ty, float tz) {
        this.delegate = delegate;
        this.tx = tx;
        this.ty = ty;
        this.tz = tz;
        this.count = 0;
        this.incomplete = false;
        this.active = true;
    }

    @Override
    public VertexConsumer getBuffer(RenderType renderType) {
        for (int i = 0; i < count; i++) {
            if (types[i] == renderType) {
                return builders[i];
            }
        }
        VertexConsumer real = delegate.getBuffer(renderType);
        if (count == MAX_TYPES || renderType.mode() != VertexFormat.Mode.QUADS
                || !(real instanceof BufferBuilder realBuilder) || VertexBufferWriter.tryOf(real) == null) {
            return live(real);
        }
        VertexFormat realFormat = ((BufferBuilderAccessor) realBuilder).icecream$getFormat();
        if (!positionFirst(realFormat)) {
            return live(real);
        }
        ByteBufferBuilder bytes = pool[count];
        if (bytes == null) {
            bytes = pool[count] = new ByteBufferBuilder(16 * 1024);
        }
        BufferBuilder capture = new BufferBuilder(bytes, renderType.mode(), renderType.format());
        if (((BufferBuilderAccessor) capture).icecream$getFormat() != realFormat) {
            // Iris extended one but not the other: the bytes wouldn't be interchangeable
            closeUnbuilt(capture);
            bytes.discard();
            return live(real);
        }
        types[count] = renderType;
        builders[count] = capture;
        formats[count] = realFormat;
        count++;
        return capture;
    }

    /** Draw this render type directly (at the right position); the object won't be throttled for a while. */
    private VertexConsumer live(VertexConsumer real) {
        incomplete = true;
        return new TranslatingConsumer(real, tx, ty, tz);
    }

    private static void closeUnbuilt(BufferBuilder builder) {
        try {
            MeshData data = builder.build();
            if (data != null) {
                data.close();
            }
        } catch (Throwable ignored) {
            // already finished
        }
    }

    private static boolean positionFirst(VertexFormat format) {
        return format != null && !format.getElements().isEmpty() && format.getElements().get(0) == VertexFormatElement.POSITION;
    }

    /** Ends the capture and moves the result into {@code slot} (reusing its meshes' memory). */
    void finishInto(CaptureSlot slot) {
        int n = 0;
        try {
            for (int i = 0; i < count; i++) {
                MeshData data = builders[i].build();
                builders[i] = null; // finished; reset() must not build it again
                if (data == null) {
                    continue; // nothing was drawn with this render type
                }
                try {
                    slot.ensureCapacity(n + 1);
                    CachedMesh mesh = slot.meshes[n];
                    if (mesh == null) {
                        mesh = slot.meshes[n] = new CachedMesh();
                    }
                    mesh.set(formats[i], MemoryUtil.memAddress(data.vertexBuffer()), data.drawState().vertexCount());
                    slot.types[n] = types[i];
                    n++;
                } finally {
                    data.close();
                }
            }
        } finally {
            reset();
        }
        // release meshes of render types this object no longer uses
        for (int i = n; i < slot.meshes.length; i++) {
            if (slot.meshes[i] != null) {
                slot.meshes[i].free();
            }
            slot.types[i] = null;
        }
        slot.count = n;
    }

    /** Throws the capture away (renderer crashed). */
    void abort() {
        reset();
    }

    private void reset() {
        for (int i = 0; i < count; i++) {
            if (builders[i] != null) {
                // abandoned mid-capture: finish it so its bytes can't leak into the next capture
                closeUnbuilt(builders[i]);
            }
            pool[i].discard();
            builders[i] = null;
            types[i] = null;
            formats[i] = null;
        }
        count = 0;
        delegate = null;
        active = false;
    }
}
