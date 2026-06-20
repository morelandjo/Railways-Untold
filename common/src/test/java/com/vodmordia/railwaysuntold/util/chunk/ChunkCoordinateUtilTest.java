package com.vodmordia.railwaysuntold.util.chunk;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterizes the pure coordinate math in {@link ChunkCoordinateUtil} and {@link ChunkBounds}: block->chunk
 * shifts, the packed chunk-key round trip (incl. negatives), and the bounding-box chunk span. (The
 * level-reading helpers are world-bound and gametest-covered.) World/config-free.
 */
class ChunkCoordinateUtilTest {

    @Test
    void blockToChunkShiftsArithmeticallyAcrossZero() {
        assertEquals(0, ChunkCoordinateUtil.getChunkX(0));
        assertEquals(0, ChunkCoordinateUtil.getChunkX(15), "15 is still chunk 0");
        assertEquals(1, ChunkCoordinateUtil.getChunkX(16), "16 starts chunk 1");
        assertEquals(-1, ChunkCoordinateUtil.getChunkX(-1), "−1 is chunk −1 (arithmetic shift)");
        assertEquals(-1, ChunkCoordinateUtil.getChunkZ(-16));
        assertEquals(-2, ChunkCoordinateUtil.getChunkZ(-17));
    }

    @Test
    void chunkKeyPacksAndUnpacksBothCoordinatesIncludingNegatives() {
        for (int[] cxz : new int[][]{{0, 0}, {5, -3}, {-7, 9}, {-1, -1}, {1_000_000, -2_000_000}}) {
            long key = ChunkCoordinateUtil.getChunkKey(cxz[0], cxz[1]);
            assertEquals(cxz[0], ChunkCoordinateUtil.getChunkXFromKey(key), "X round trips for " + cxz[0]);
            assertEquals(cxz[1], ChunkCoordinateUtil.getChunkZFromKey(key), "Z round trips for " + cxz[1]);
        }
    }

    @Test
    void chunkKeyFromBlockPosUsesTheContainingChunk() {
        BlockPos pos = new BlockPos(20, 64, -5); // chunk (1, -1)
        assertEquals(ChunkCoordinateUtil.getChunkKey(1, -1), ChunkCoordinateUtil.getChunkKey(pos));
    }

    @Test
    void chunkBoundsFromIsOrderIndependentAndCoversBothCorners() {
        ChunkBounds a = ChunkBounds.from(new BlockPos(0, 0, 0), new BlockPos(40, 0, 40));
        ChunkBounds b = ChunkBounds.from(new BlockPos(40, 0, 40), new BlockPos(0, 0, 0)); // swapped corners
        assertEquals(a, b, "min/max are normalized regardless of corner order");
        assertEquals(0, a.minChunkX());
        assertEquals(2, a.maxChunkX(), "40 >> 4 = 2");
        assertEquals(0, a.minChunkZ());
        assertEquals(2, a.maxChunkZ());
    }

    @Test
    void boundingBoxChunkSpanEnumeratesEveryChunkInclusive() {
        // A box spanning chunks x[0,1] z[0,1] -> 4 chunks.
        Set<ChunkPos> chunks = ChunkCoordinateUtil.getChunksInBoundingBox(new BlockPos(0, 0, 0), new BlockPos(20, 0, 20));
        assertEquals(4, chunks.size());
        assertTrue(chunks.contains(new ChunkPos(0, 0)));
        assertTrue(chunks.contains(new ChunkPos(1, 1)));

        // A box within a single chunk -> exactly one.
        assertEquals(1, ChunkCoordinateUtil.getChunksInBoundingBox(new BlockPos(1, 0, 1), new BlockPos(5, 0, 5)).size());
    }
}
