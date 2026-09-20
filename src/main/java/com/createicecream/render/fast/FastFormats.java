package com.createicecream.render.fast;

import org.jetbrains.annotations.Nullable;

import com.createicecream.mixin.minecraft.BufferBuilderAccessor;
import com.createicecream.render.cache.VertexRecorder;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

/** The four vertex formats the fast renderer writes directly. */
public final class FastFormats {
    private FastFormats() {
    }

    /** @return the consumer's vertex format if it is one we can write directly, else null */
    @Nullable
    public static VertexFormat of(VertexConsumer consumer) {
        VertexFormat format;
        if (consumer instanceof VertexRecorder recorder) {
            format = recorder.format();
        } else if (consumer instanceof BufferBuilder builder) {
            format = ((BufferBuilderAccessor) builder).icecream$getFormat();
        } else {
            return null;
        }
        return isSupported(format) ? format : null;
    }

    public static boolean isSupported(@Nullable VertexFormat format) {
        return format != null && (format == BlockVertex.FORMAT || format == EntityVertex.FORMAT
                || format == IrisTerrainVertex.FORMAT || format == IrisEntityVertex.FORMAT);
    }
}
