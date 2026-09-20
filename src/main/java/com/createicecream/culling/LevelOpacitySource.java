package com.createicecream.culling;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Reads block opacity from the client level on the culling thread.
 * <p>
 * Like Entity Culling, this reads chunk sections without synchronisation. Client chunk storage is an
 * atomic array and section reads never mutate, so the worst case of a race with a chunk update is one
 * stale (or exception-producing) lookup, which the caller treats as "visible" and fixes on the next pass.
 * Results are memoised per pass because neighbouring rays hit the same blocks over and over.
 */
final class LevelOpacitySource implements OcclusionRaycaster.OpacitySource {
    private static final byte UNKNOWN = 0, CLEAR = 1, OPAQUE = 2;

    private final Long2ByteOpenHashMap cache = new Long2ByteOpenHashMap(4096);

    private ClientLevel level;
    private boolean leavesOpaque;
    private int minY, maxY;

    private long lastChunkKey = Long.MIN_VALUE;
    private LevelChunk lastChunk;

    void begin(ClientLevel level, boolean leavesOpaque) {
        this.level = level;
        this.leavesOpaque = leavesOpaque;
        this.minY = level.getMinBuildHeight();
        this.maxY = level.getMaxBuildHeight();
        this.lastChunkKey = Long.MIN_VALUE;
        this.lastChunk = null;
        if (cache.size() > 1 << 16) {
            cache.clear();
            cache.trim(4096);
        } else {
            cache.clear();
        }
    }

    void end() {
        level = null;
        lastChunk = null;
        lastChunkKey = Long.MIN_VALUE;
    }

    @Override
    public boolean isOpaque(int x, int y, int z) {
        if (y < minY || y >= maxY) {
            return false;
        }
        long key = BlockPos.asLong(x, y, z);
        byte cached = cache.get(key);
        if (cached != UNKNOWN) {
            return cached == OPAQUE;
        }
        boolean opaque = lookup(x, y, z);
        cache.put(key, opaque ? OPAQUE : CLEAR);
        return opaque;
    }

    private boolean lookup(int x, int y, int z) {
        LevelChunk chunk = chunk(x >> 4, z >> 4);
        if (chunk == null) {
            return false; // unloaded: assume see-through
        }
        LevelChunkSection[] sections = chunk.getSections();
        int index = level.getSectionIndex(y);
        if (index < 0 || index >= sections.length) {
            return false;
        }
        LevelChunkSection section = sections[index];
        if (section == null || section.hasOnlyAir()) {
            return false;
        }
        BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
        if (leavesOpaque && state.getBlock() instanceof LeavesBlock) {
            return true;
        }
        // Same test Entity Culling uses on 1.21.1: cached per state for virtually every block.
        return state.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    private LevelChunk chunk(int cx, int cz) {
        long key = (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
        if (key != lastChunkKey) {
            lastChunkKey = key;
            lastChunk = level.getChunkSource().getChunk(cx, cz, false);
        }
        return lastChunk;
    }
}
