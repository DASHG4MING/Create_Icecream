package com.createicecream.render.capture;

import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

/**
 * A buffer source that discards everything. Contraptions are still "rendered" into it on replayed frames so
 * that every side effect of their renderer (train coupling anchors, animation state, ...) keeps running every
 * frame; Create's models recognise {@link #CONSUMER} and skip all vertex work.
 */
public final class NullSink implements MultiBufferSource {
    public static final NullSink INSTANCE = new NullSink();
    public static final VertexConsumer CONSUMER = new Consumer();

    private NullSink() {
    }

    @Override
    public VertexConsumer getBuffer(RenderType renderType) {
        return CONSUMER;
    }

    private static final class Consumer implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }
    }
}
