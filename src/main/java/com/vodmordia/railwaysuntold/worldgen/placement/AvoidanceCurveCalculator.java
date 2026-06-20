package com.vodmordia.railwaysuntold.worldgen.placement;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.CreateTrackUtil;
import com.vodmordia.railwaysuntold.worldgen.placement.decider.CurveParameterDecider;
import com.vodmordia.railwaysuntold.worldgen.terrain.TerrainScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Utility class for calculating avoidance curves around obstacles.
 *
 */
public final class AvoidanceCurveCalculator {

    private AvoidanceCurveCalculator() {
    }

    /**
     * Angle in degrees for avoidance curves
     */
    public static final int AVOIDANCE_CURVE_ANGLE = 90;

    /**
     * Builds curve radii to try for avoidance (standard first, then smaller emergency radii).
     * Radii are derived from the configured min/max curve radius.
     */
    public static int[] getCurveRadii() {
        int min = RailwaysUntoldConfig.getMinCurveRadius();
        int max = RailwaysUntoldConfig.getMaxCurveRadius();
        int mid = (min + max) / 2;
        int quarterDown = (min + mid) / 2;
        int quarterUp = (mid + max) / 2;
        int eighthDown = (min + quarterDown) / 2;
        int eighthUp = (quarterUp + max) / 2;
        return new int[]{min, eighthDown, quarterDown, mid, quarterUp, eighthUp, max};
    }

    /**
     * Maximum height difference allowed for avoidance curves at largest radius
     */
    private static final int MAX_HEIGHT_DIFFERENCE_LARGE_RADIUS = 10;

    /**
     * Maximum height difference allowed for avoidance curves at smallest radius
     */
    private static final int MAX_HEIGHT_DIFFERENCE_SMALL_RADIUS = 2;

    /**
     * Smallest radius for scaling max height difference
     */
    private static final int MIN_RADIUS_FOR_SCALING = 6;

    /**
     * Largest radius for scaling max height difference
     */
    private static final int MAX_RADIUS_FOR_SCALING = 30;

    /**
     * Tries to find an avoidance curve around an obstacle in a specific turn direction.
     *
     * Iterates through available curve radii to find a valid path that:
     * - Ends outside the obstacle bounding box
     * - Has a clear path that doesn't intersect the obstacle
     * - Has acceptable height difference for the curve radius
     *
     * @param level       Server level for terrain queries
     * @param start       Start position
     * @param heading     Current heading direction
     * @param obstacleBox Obstacle bounding box (should include buffer)
     * @param turnLeft    Whether to turn left (true) or right (false)
     * @return Curve parameters if successful, null if no valid curve found
     */
    public static CurveParameterDecider.CurveParameters findCurveAroundObstacle(
            ServerLevel level, BlockPos start, Direction heading,
            BoundingBox obstacleBox, boolean turnLeft) {

        boolean bankingEnabled = RailwaysUntoldConfig.areBankingCurvesEnabled();

        for (int radius : getCurveRadii()) {
            BlockPos curveEnd = CreateTrackUtil.calculateCurveEndpoint(
                    start, heading, turnLeft, AVOIDANCE_CURVE_ANGLE, radius, 0);

            if (bankingEnabled) {
                int trackHeight = TerrainScanner.getGroundLevelAt(level, curveEnd);
                curveEnd = new BlockPos(curveEnd.getX(), trackHeight, curveEnd.getZ());
            }

            if (obstacleBox.isInside(curveEnd)) {
                continue;
            }

            if (!isCurvePathClearOfObstacles(start, curveEnd, obstacleBox)) {
                continue;
            }

            int heightDiff = Math.abs(curveEnd.getY() - start.getY());
            int maxHeightForRadius = getMaxHeightDifferenceForRadius(radius);
            if (heightDiff > maxHeightForRadius) {
                continue;
            }

            return new CurveParameterDecider.CurveParameters(
                    AVOIDANCE_CURVE_ANGLE,
                    turnLeft,
                    radius,
                    start,
                    curveEnd,
                    heading
            );
        }

        return null;
    }

    /**
     * Attempts to find an avoidance curve around an obstacle, trying both turn directions.
     *
     * @param level       Server level for terrain queries
     * @param currentPos  Current track position
     * @param expandDir   Direction of track expansion
     * @param obstacleBox Bounding box of the obstacle (should include buffer)
     * @param scan        Terrain scan data
     * @param logContext  Description for logging (e.g., "serviced village abc123")
     * @return Optional containing curve decision if successful, empty otherwise
     */
    public static Optional<PlacementDecision> findCurveAroundObstacleBothDirections(
            ServerLevel level, BlockPos currentPos, Direction expandDir,
            BoundingBox obstacleBox, TerrainScanner.TerrainScan scan,
            String logContext) {
        return findCurveAroundObstacleBothDirections(
                level, currentPos, expandDir, obstacleBox, scan, logContext, null);
    }

    /**
     * Attempts to find an avoidance curve around an obstacle, trying both turn directions.
     * When preferredTurnLeft is non-null, tries the preferred direction first to avoid
     * consecutive same-direction curves that create U-turns.
     */
    public static Optional<PlacementDecision> findCurveAroundObstacleBothDirections(
            ServerLevel level, BlockPos currentPos, Direction expandDir,
            BoundingBox obstacleBox, TerrainScanner.TerrainScan scan,
            String logContext, @Nullable Boolean preferredTurnLeft) {

        boolean[] directions = (preferredTurnLeft != null)
                ? new boolean[]{preferredTurnLeft, !preferredTurnLeft}
                : new boolean[]{true, false};
        for (boolean turnLeft : directions) {
            CurveParameterDecider.CurveParameters params =
                    findCurveAroundObstacle(level, currentPos, expandDir, obstacleBox, turnLeft);

            if (params != null) {
                return Optional.of(PlacementDecision.curve(params, scan));
            }
        }

        // No avoidance path here - the caller falls through to its normal routing; routine, not surfaced.
        return Optional.empty();
    }

    /**
     * Calculates the maximum allowed height difference for a given curve radius.
     *
     * Smaller radii have tighter height constraints to maintain valid bezier curves.
     * Uses linear interpolation between min and max values.
     *
     * @param radius The curve radius in blocks
     * @return Maximum allowed height difference in blocks
     */
    public static int getMaxHeightDifferenceForRadius(int radius) {
        // Clamp radius to our scaling range
        int clampedRadius = Math.max(MIN_RADIUS_FOR_SCALING, Math.min(MAX_RADIUS_FOR_SCALING, radius));

        // Linear interpolation between min and max height differences
        double t = (double) (clampedRadius - MIN_RADIUS_FOR_SCALING)
                / (MAX_RADIUS_FOR_SCALING - MIN_RADIUS_FOR_SCALING);
        return (int) Math.round(MAX_HEIGHT_DIFFERENCE_SMALL_RADIUS
                + t * (MAX_HEIGHT_DIFFERENCE_LARGE_RADIUS - MAX_HEIGHT_DIFFERENCE_SMALL_RADIUS));
    }

    /**
     * Checks if a curve path is clear of obstacles by sampling points along the arc.
     */
    private static boolean isCurvePathClearOfObstacles(BlockPos start, BlockPos end, BoundingBox obstacleBox) {
        int samples = 10;

        BlockPos.MutableBlockPos samplePos = new BlockPos.MutableBlockPos();
        for (int i = 0; i <= samples; i++) {
            double t = (double) i / samples;
            int x = (int) (start.getX() + (end.getX() - start.getX()) * t);
            int z = (int) (start.getZ() + (end.getZ() - start.getZ()) * t);
            int y = (int) (start.getY() + (end.getY() - start.getY()) * t);

            samplePos.set(x, y, z);

            if (obstacleBox.isInside(samplePos)) {
                return false;
            }
        }

        return true;
    }
}
