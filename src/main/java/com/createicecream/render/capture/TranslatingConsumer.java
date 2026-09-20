package com.createicecream.render.capture;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * During a capture the renderer draws with a pose whose translation is zero (the capture is relative to the
 * object). Anything that can't be captured goes straight to the real buffer through this wrapper, which adds
 * the object's real camera-relative position back, so it lands where it belongs.
 */
final class TranslatingConsumer implements VertexConsumer {
    private final VertexConsumer delegate;
    private final float tx, ty, tz;

    TranslatingConsumer(VertexConsumer delegate, float tx, float ty, float tz) {
        this.delegate = delegate;
        this.tx = tx;
        this.ty = ty;
        this.tz = tz;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        delegate.addVertex(x + tx, y + ty, z + tz);
        return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        delegate.setColor(red, green, blue, alpha);
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        delegate.setUv(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        delegate.setUv1(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        delegate.setUv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        delegate.setNormal(x, y, z);
        return this;
    }
}
