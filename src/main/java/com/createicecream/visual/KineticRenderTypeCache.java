package com.createicecream.visual;

import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import com.createicecream.config.OptConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Memoised result of {@code KineticBlockEntityRenderer#getRenderType}, keyed by block state and validated
 * against the identity of the currently baked model, so a resource reload can never serve a stale type.
 */
public final class KineticRenderTypeCache {
    private record Entry(BakedModel model, RenderType type) {
    }

    private static final ConcurrentHashMap<BlockState, Entry> CACHE = new ConcurrentHashMap<>();

    private KineticRenderTypeCache() {
    }

    @Nullable
    public static RenderType get(BlockState state) {
        if (!OptConfig.enabled || !OptConfig.renderTypeCache) {
            return null;
        }
        Entry entry = CACHE.get(state);
        if (entry == null) {
            return null;
        }
        BakedModel current = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        return current == entry.model ? entry.type : null;
    }

    public static void put(BlockState state, @Nullable RenderType type) {
        if (type == null || !OptConfig.enabled || !OptConfig.renderTypeCache) {
            return;
        }
        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        CACHE.put(state, new Entry(model, type));
    }

    public static void clear() {
        CACHE.clear();
    }
}
