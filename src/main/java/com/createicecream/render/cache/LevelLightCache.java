package com.createicecream.render.cache;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;

/**
 * Per-frame cache of {@code LevelRenderer.getLightColor} for the "use level light" path (contraptions).
 * <p>
 * CreateBetterFps had a static map here that was never cleared: lighting of contraptions never updated
 * after the first lookup (moving trains kept the light of wherever a block was first seen) and the map
 * grew forever. Clearing it every frame keeps the de-duplication benefit (many vertices share a block)
 * without either problem.
 */
public final class LevelLightCache {
    private static final Long2IntOpenHashMap CACHE = new Long2IntOpenHashMap();
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();
    private static BlockAndTintGetter lastLevel;

    static {
        CACHE.defaultReturnValue(-1);
    }

    private LevelLightCache() {
    }

    public static int get(BlockAndTintGetter level, float x, float y, float z) {
        if (level != lastLevel) {
            // e.g. a Ponder scene rendered in the same frame as the world
            CACHE.clear();
            lastLevel = level;
        }
        POS.set(x, y, z);
        long key = POS.asLong();
        int light = CACHE.get(key);
        if (light == -1) {
            light = LevelRenderer.getLightColor(level, POS);
            CACHE.put(key, light);
        }
        return light;
    }

    static void clear() {
        lastLevel = null;
        if (!CACHE.isEmpty()) {
            if (CACHE.size() > 65536) {
                CACHE.clear();
                CACHE.trim(1024);
            } else {
                CACHE.clear();
            }
        }
    }
}
