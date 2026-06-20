package com.vodmordia.railwaysuntold.worldgen.siding;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.spatial.DirectionUtil;
import com.vodmordia.railwaysuntold.util.spatial.PositionRandom;
import com.vodmordia.railwaysuntold.worldgen.head.TrackExpansionHead;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementConstants;
import com.vodmordia.railwaysuntold.worldgen.terrain.TerrainAnalyzer;
import com.vodmordia.railwaysuntold.worldgen.terrain.TerrainScanner;
import com.vodmordia.railwaysuntold.worldgen.tracking.BoundarySegmentData.ConnectedSegment;
import com.vodmordia.railwaysuntold.worldgen.tracking.ConnectedBoundaryTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.List;
import java.util.Set;

/**
 * Validates conditions for placing a rail siding and selects which side to place it on.
 */
public class SidingValidator {

    private static final int PERPENDICULAR_SCAN_DISTANCE = 30;
    private static final int TRACK_PROXIMITY_CHECK_DISTANCE = 40;

    /** Salt for deriving a deterministic side-selection RNG from position + world seed. */
    private static final long SALT_SIDE_SELECTION = 5101L;

    /**
     * Result of siding validation.
     */
    public static class ValidationResult {
        public final boolean approved;
        public final boolean placeLeft;
        public final String reason;

        private ValidationResult(boolean approved, boolean placeLeft, String reason) {
            this.approved = approved;
            this.placeLeft = placeLeft;
            this.reason = reason;
        }

        public static ValidationResult approved(boolean placeLeft) {
            return new ValidationResult(true, placeLeft, null);
        }

        public static ValidationResult rejected(String reason) {
            return new ValidationResult(false, false, reason);
        }
    }

    /**
     * Validates whether a siding should be placed and selects the side.
     *
     * @param head      The expansion head
     * @param position  Current head position
     * @param direction Current travel direction
     * @param level     The server level
     * @param config    Config reference
     * @return ValidationResult with approval status and chosen side
     */
    public static ValidationResult validate(
            TrackExpansionHead head,
            BlockPos position,
            Direction direction,
            ServerLevel level,
            RailwaysUntoldConfig config) {

        if (!RailwaysUntoldConfig.areSidingsEnabled()) {
            return ValidationResult.rejected("Sidings disabled");
        }

        int minBlocks = RailwaysUntoldConfig.getSidingMinStraightSegments() * PlacementConstants.STANDARD_SEGMENT_LENGTH;
        if (!head.getSidingState().isEligible(minBlocks)) {
            return ValidationResult.rejected("Not enough straight blocks or already placed");
        }

        // Score left and right sides
        int leftScore = scoreSide(level, position, direction, true, config);
        int rightScore = scoreSide(level, position, direction, false, config);

        if (leftScore <= 0 && rightScore <= 0) {
            return ValidationResult.rejected("Both sides unsuitable");
        }

        boolean placeLeft;
        if (leftScore > 0 && rightScore <= 0) {
            placeLeft = true;
        } else if (rightScore > 0 && leftScore <= 0) {
            placeLeft = false;
        } else {
            // Both viable - weighted random, seeded by position + world seed for reproducibility
            placeLeft = PositionRandom.createWithSalt(position, level.getSeed(), SALT_SIDE_SELECTION)
                    .nextInt(leftScore + rightScore) < leftScore;
        }

        return ValidationResult.approved(placeLeft);
    }

    /**
     * Scores a side for siding placement. Higher is better. 0 or negative = unsuitable.
     */
    private static int scoreSide(ServerLevel level, BlockPos position, Direction direction,
                                  boolean isLeft, RailwaysUntoldConfig config) {
        int score = 100;

        Direction sideDir = DirectionUtil.getPerpendicularDirection(direction, isLeft);

        // Check for ravine on this side
        TerrainScanner.TerrainScan scan = TerrainScanner.scanAhead(
                level, position, sideDir, PERPENDICULAR_SCAN_DISTANCE, config);
        if (scan != null) {
            TerrainAnalyzer.TerrainAnalysis analysis = TerrainAnalyzer.analyzeTerrain(scan, position);
            if (analysis.isRavine()) {
                return -1; // Hard veto
            }
        }

        // Check for existing track in the siding's footprint area
        int lateralDisplacement = SidingTrackCreator.calculateLateralDisplacement(
                RailwaysUntoldConfig.getMinCurveRadius(), 10);
        BlockPos checkPos = position.relative(sideDir, lateralDisplacement);

        Set<ChunkPos> chunks = ConnectedBoundaryTracker.getChunksSpannedBySegment(
                position, checkPos.relative(direction, TRACK_PROXIMITY_CHECK_DISTANCE));
        for (ChunkPos chunk : chunks) {
            List<ConnectedSegment> segments = ConnectedBoundaryTracker.getSegmentsInChunk(level, chunk);
            if (!segments.isEmpty()) {
                score -= 80;
                break;
            }
        }

        return score;
    }
}
