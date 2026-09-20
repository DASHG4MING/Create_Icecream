// COMPILE-ONLY STUB. Mirrors the public signature of the real class so Create IceCream can be compiled
// without the mod jar. It is NOT packaged: at runtime the real class from Sodium is used.
package net.caffeinemc.mods.sodium.api.vertex.buffer;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

public interface VertexBufferWriter {
    static VertexBufferWriter of(VertexConsumer consumer) {
        throw new AssertionError("stub");
    }

    @Nullable
    static VertexBufferWriter tryOf(VertexConsumer consumer) {
        throw new AssertionError("stub");
    }

    @Nullable
    static VertexBufferWriter tryOf(VertexConsumer consumer, VertexFormat format) {
        throw new AssertionError("stub");
    }

    void push(MemoryStack stack, long ptr, int count, VertexFormat format);

    default boolean canUseIntrinsics() {
        return true;
    }

    default boolean canUseIntrinsics(VertexFormat format) {
        return this.canUseIntrinsics();
    }
}
