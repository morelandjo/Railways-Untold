package com.vodmordia.railwaysuntold.worldgen.terrain;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.block.BlockTypeUtil;
import com.vodmordia.railwaysuntold.util.block.ChunkSafeBlockAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Utility class for detecting if a track segment is underground.
 */
public class UndergroundDetector {

    // Fraction of the check zone that must be solid terrain to classify as underground
    private static final double MIN_SOLID_FRACTION = 0.5;

    /**
     * Checks if a segment position is underground by looking for solid terrain blocks above.
     * Checks ABOVE the clearing zone to work correctly after terrain has been cleared.
     * A position is underground when at least 50% of the check zone is solid terrain.
     *
     * @param level    The server level
     * @param trackPos The track position to check
     * @return true if the position is underground (has substantial terrain above)
     */
    public static boolean isSegmentUnderground(ServerLevel level, BlockPos trackPos) {
        int verticalExpansion = RailwaysUntoldConfig.getVerticalExpansion();

        // Start checking ABOVE the clearing zone (verticalExpansion + 1)
        // This ensures we detect terrain that wasn't cleared away
        int minCheckHeight = verticalExpansion + 1;
        int maxCheckHeight = verticalExpansion + TerrainConstants.UNDERGROUND_BUFFER_OFFSET + 5;

        return hasSubstantialCeiling(level, trackPos, minCheckHeight, maxCheckHeight);
    }

    /**
     * Checks if there's a substantial ceiling above the position.
     * Uses a percentage threshold rather than an absolute block count
     *
     * @param level    The server level
     * @param trackPos The track position to check
     * @param minY     Minimum Y offset to start checking (relative to trackPos)
     * @param maxY     Maximum Y offset to check (relative to trackPos)
     * @return true if at least 50% of the checked positions are solid terrain
     */
    private static boolean hasSubstantialCeiling(ServerLevel level, BlockPos trackPos, int minY, int maxY) {
        int solidTerrainCount = 0;
        int checkedCount = 0;

        for (int y = minY; y <= maxY; y++) {
            BlockPos checkPos = trackPos.above(y);
            BlockState state = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, checkPos);

            // If chunk not loaded, skip this position (assume not underground)
            if (state == null) {
                continue;
            }

            checkedCount++;
            if (isTerrainBlock(state)) {
                solidTerrainCount++;
            }
        }

        if (checkedCount == 0) {
            return false;
        }

        return (double) solidTerrainCount / checkedCount >= MIN_SOLID_FRACTION;
    }

    /**
     * Checks if a block state represents solid terrain (not vegetation/trees).
     *
     * @param state The block state to check
     * @return true if this is a solid terrain block (stone, dirt, etc.)
     */
    private static boolean isTerrainBlock(BlockState state) {
        // Must be solid first
        if (!state.isSolid()) {
            return false;
        }

        // Exclude trees and wood (logs, leaves, bamboo, roots, etc.)
        if (BlockTypeUtil.isTreeOrWood(state)) {
            return false;
        }

        // Exclude mushrooms (giant mushroom blocks/stems)
        // If not a tree/wood or mushroom, this is solid terrain (stone, dirt, sand, gravel, etc.)
        return !BlockTypeUtil.isMushroom(state);
    }
}
