package com.vodmordia.railwaysuntold.worldgen.terrain;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.block.BlockTypeUtil;
import com.vodmordia.railwaysuntold.util.block.ChunkSafeBlockAccess;
import com.vodmordia.railwaysuntold.util.spatial.DirectionOffsets;
import com.vodmordia.railwaysuntold.util.spatial.DirectionUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Utility class for detecting if a cleared track segment is surrounded by water.
 */
public class UnderwaterDetector {

    /**
     * Threshold percentage for water blocks on walls/floor to trigger underwater facade (50%).
     */
    private static final double WATER_THRESHOLD = 0.50;

    /**
     * Checks if a cleared segment is underwater based on water surrounding the walls and floor.
     * A segment is considered underwater if 50%+ of wall and floor positions contain water.
     *
     * @param level    The server level
     * @param trackPos The track position to check (center of cleared area)
     * @param normal   Normal vector perpendicular to track direction
     * @return true if the segment is surrounded by water on walls/floor
     */
    public static boolean isSegmentUnderwater(ServerLevel level, BlockPos trackPos, Vec3 normal) {
        if (!isWaterAt(level, trackPos)) {
            return false;
        }

        int horizontalRadius = RailwaysUntoldConfig.getHorizontalExpansion();
        int verticalHeight = RailwaysUntoldConfig.getVerticalExpansion();
        int facadeWallDist = horizontalRadius + 1;

        DirectionOffsets offsets = DirectionOffsets.fromNormal(normal);
        WaterCheckResult result = new WaterCheckResult();

        checkWalls(level, trackPos, offsets, facadeWallDist, verticalHeight, result);
        checkFloor(level, trackPos, offsets, horizontalRadius, result);

        return result.getWaterPercentage() >= WATER_THRESHOLD;
    }

    private static void checkWalls(ServerLevel level, BlockPos trackPos, DirectionOffsets offsets,
                                    int facadeWallDist, int verticalHeight, WaterCheckResult result) {
        for (int y = -2; y <= verticalHeight; y++) {
            BlockPos basePos = trackPos.above(y);
            checkWaterPosition(level, DirectionUtil.getOffsetPosition(basePos, offsets.left(), facadeWallDist), result);
            checkWaterPosition(level, DirectionUtil.getOffsetPosition(basePos, offsets.right(), facadeWallDist), result);
        }
    }

    private static void checkFloor(ServerLevel level, BlockPos trackPos, DirectionOffsets offsets,
                                    int horizontalRadius, WaterCheckResult result) {
        BlockPos floorCenter = trackPos.below(2);
        checkWaterPosition(level, floorCenter, result);

        for (int d = 1; d <= horizontalRadius; d++) {
            checkWaterPosition(level, DirectionUtil.getOffsetPosition(floorCenter, offsets.left(), d), result);
            checkWaterPosition(level, DirectionUtil.getOffsetPosition(floorCenter, offsets.right(), d), result);
        }
    }

    private static void checkWaterPosition(ServerLevel level, BlockPos pos, WaterCheckResult result) {
        result.totalPositions++;
        if (isWaterAt(level, pos)) {
            result.waterCount++;
        }
    }

    private static class WaterCheckResult {
        int waterCount = 0;
        int totalPositions = 0;

        double getWaterPercentage() {
            return totalPositions > 0 ? (double) waterCount / totalPositions : 0.0;
        }
    }

    /**
     * Checks if there's a water fluid block at the given position.
     *
     * @param level The server level
     * @param pos   Position to check
     * @return true if the block at pos is water
     */
    private static boolean isWaterAt(ServerLevel level, BlockPos pos) {
        BlockState state = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, pos);
        return state != null && BlockTypeUtil.isWater(state);
    }
}
