package com.vodmordia.railwaysuntold.util.chunk;

import net.minecraft.core.BlockPos;

/**
 * Immutable representation of chunk coordinate bounds.
 *
 * @param minChunkX Minimum chunk X coordinate (west edge)
 * @param maxChunkX Maximum chunk X coordinate (east edge)
 * @param minChunkZ Minimum chunk Z coordinate (north edge)
 * @param maxChunkZ Maximum chunk Z coordinate (south edge)
 */
public record ChunkBounds(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {

    /**
     * Creates ChunkBounds from two block positions, automatically determining min/max.
     *
     * @param start First corner of the bounding box
     * @param end   Second corner of the bounding box
     * @return ChunkBounds encompassing both positions
     */
    public static ChunkBounds from(BlockPos start, BlockPos end) {
        int startChunkX = start.getX() >> 4;
        int endChunkX = end.getX() >> 4;
        int startChunkZ = start.getZ() >> 4;
        int endChunkZ = end.getZ() >> 4;

        return new ChunkBounds(
                Math.min(startChunkX, endChunkX),
                Math.max(startChunkX, endChunkX),
                Math.min(startChunkZ, endChunkZ),
                Math.max(startChunkZ, endChunkZ)
        );
    }

}
