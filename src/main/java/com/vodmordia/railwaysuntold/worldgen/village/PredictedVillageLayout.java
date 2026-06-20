package com.vodmordia.railwaysuntold.worldgen.village;

import com.vodmordia.railwaysuntold.util.chunk.ChunkBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.List;

/**
 * Pre-computed village layout from structure seed.
 *
 * @param structureChunk The chunk where the structure start is placed
 * @param totalBounds    Union of all piece bounding boxes
 * @param chunkBounds    Chunk-coordinate bounds for RunwayPositionCalculator compatibility
 * @param actualCenter   Centroid of the total bounds
 * @param pieceBounds    Individual piece bounding boxes
 */
public record PredictedVillageLayout(
        ChunkPos structureChunk,
        BoundingBox totalBounds,
        ChunkBounds chunkBounds,
        BlockPos actualCenter,
        List<BoundingBox> pieceBounds
) {

    /**
     * Creates a PredictedVillageLayout from a total bounding box and piece list.
     */
    public static PredictedVillageLayout create(ChunkPos structureChunk, BoundingBox totalBounds,
                                                 List<BoundingBox> pieceBounds) {
        ChunkBounds chunkBounds = new ChunkBounds(
                totalBounds.minX() >> 4, totalBounds.maxX() >> 4,
                totalBounds.minZ() >> 4, totalBounds.maxZ() >> 4
        );

        BlockPos actualCenter = new BlockPos(
                (totalBounds.minX() + totalBounds.maxX()) / 2,
                (totalBounds.minY() + totalBounds.maxY()) / 2,
                (totalBounds.minZ() + totalBounds.maxZ()) / 2
        );

        return new PredictedVillageLayout(structureChunk, totalBounds, chunkBounds, actualCenter, pieceBounds);
    }
}
