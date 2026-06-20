package com.vodmordia.railwaysuntold.worldgen.placement.analyzer;

import com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.Set;

/**
 * Calculates safe lookahead distances based on available loaded chunks.
 */
public final class LookaheadCalculator {

    private LookaheadCalculator() {
    }

    /**
     * Calculates dynamic lookahead distance based on available chunks.
     * Starts at MAX_LOOKAHEAD and works down to find the highest distance
     * where all chunks are loaded. Returns 0 if REQUIRED_LOOKAHEAD cannot be met.
     *
     * @param level     Server level
     * @param start     Starting position
     * @param direction Direction to scan
     * @return Safe lookahead distance in blocks (REQUIRED_LOOKAHEAD to MAX_LOOKAHEAD), or 0 if insufficient
     */
    public static int calculateDynamicLookahead(ServerLevel level, BlockPos start, Direction direction) {
        for (int distance = PlacementConstants.MAX_LOOKAHEAD; distance >= PlacementConstants.REQUIRED_LOOKAHEAD; distance -= PlacementConstants.LOOKAHEAD_STEP) {
            // Use distance + 1 because TerrainScanner.scanAhead samples positions
            // start+1 through start+distance+1, requiring chunks one block further
            BlockPos endPos = start.relative(direction, distance + 1);
            Set<ChunkPos> requiredChunks = ChunkCoordinateUtil.getChunksInBoundingBox(start, endPos);

            boolean allLoaded = true;
            for (ChunkPos chunkPos : requiredChunks) {
                if (ChunkCoordinateUtil.getLoadedChunk(level, chunkPos) == null) {
                    allLoaded = false;
                    break;
                }
            }

            if (allLoaded) {
                return distance;
            }
        }

        return 0;
    }
}
