package com.vodmordia.railwaysuntold.worldgen.terrain;

import com.vodmordia.railwaysuntold.util.chunk.ChunkVerificationUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/**
 * Analyzes terrain for branch viability scoring.
 */
public class BranchTerrainAnalyzer {

    private static final int BRANCH_SCAN_DISTANCE = 20;

    private BranchTerrainAnalyzer() {
    }

    /**
     * Scores terrain in a given direction for branch viability.
     *
     * @param startPos  Starting position for terrain scan
     * @param direction Direction to scan
     * @param level     The server level
     * @return BranchTerrainScore with calculated score
     */
    public static BranchTerrainScore scoreBranchTerrain(BlockPos startPos, Direction direction, ServerLevel level) {
        // Calculate scan area
        BlockPos scanEnd = startPos.relative(direction, BRANCH_SCAN_DISTANCE);
        BlockPos expandedStart = new BlockPos(
                Math.min(startPos.getX(), scanEnd.getX()),
                startPos.getY() - BRANCH_SCAN_DISTANCE,
                Math.min(startPos.getZ(), scanEnd.getZ())
        );
        BlockPos expandedEnd = new BlockPos(
                Math.max(startPos.getX(), scanEnd.getX()),
                startPos.getY() + 10,
                Math.max(startPos.getZ(), scanEnd.getZ())
        );

        // Verify chunks are loaded
        if (!ChunkVerificationUtil.areBoundingBoxChunksLoaded(level, expandedStart, expandedEnd)) {
            return new BranchTerrainScore(-100);  // Penalty for unloaded chunks
        }

        int score = 100;
        int clearanceBonus = 0;
        int elevationPenalty = 0;
        int obstructionPenalty = 0;

        for (int distance = 1; distance <= BRANCH_SCAN_DISTANCE; distance++) {
            BlockPos checkPos = startPos.relative(direction, distance);

            // Clearance check
            int clearanceHeight = TerrainHeightUtil.getClearanceHeight(checkPos, level);
            if (clearanceHeight >= 4) {
                clearanceBonus += 2;
            } else if (clearanceHeight < 2) {
                obstructionPenalty += 10;
            }

            // Elevation check
            int groundLevel = TerrainHeightUtil.findSimpleGroundLevel(checkPos, level);
            int elevationChange = Math.abs(groundLevel - startPos.getY());
            if (elevationChange > 5) {
                elevationPenalty += elevationChange * 2;
            } else if (elevationChange <= 2) {
                clearanceBonus += 5;
            }

            // Obstruction check
            if (TerrainHeightUtil.hasLiquidObstruction(checkPos, level)) {
                obstructionPenalty += 15;
            }
        }

        return new BranchTerrainScore(Math.max(0, score + clearanceBonus - elevationPenalty - obstructionPenalty));
    }

    /**
     * Result of branch terrain scoring.
     */
    public static class BranchTerrainScore {
        public final int score;

        public BranchTerrainScore(int score) {
            this.score = score;
        }
    }
}
