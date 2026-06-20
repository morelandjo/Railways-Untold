package com.vodmordia.railwaysuntold.worldgen.placement.decider;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.chunk.ChunkVerificationUtil;
import com.vodmordia.railwaysuntold.util.spatial.DirectionUtil;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.CreateTrackUtil;
import com.vodmordia.railwaysuntold.worldgen.terrain.TerrainScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;

/**
 * Decides curve parameters for train tracks.
 */
public class CurveParameterDecider {

    private static final int CURVE_ANGLE = 90;

    public static boolean validateCurve(ServerLevel level, CurveParameters params, boolean relaxedValidation) {
        if (params.radius < RailwaysUntoldConfig.getMinCurveRadius() || params.radius > RailwaysUntoldConfig.getMaxCurveRadius()) {
            return false;
        }

        return validateCurveTerrainPath(level, params, relaxedValidation);
    }

    /**
     * Validates the terrain along a curve path to prevent curves into dropoffs or cliffs.
     *
     * @param level Server level for terrain access
     * @param params Curve parameters defining the arc
     * @param relaxedValidation If true, allow larger terrain drops (for village-directed curves)
     * @return true if terrain is safe for the curve, false if dropoff detected
     */
    private static boolean validateCurveTerrainPath(ServerLevel level, CurveParameters params, boolean relaxedValidation) {
        // Sample 8-10 points along the curve arc to check terrain
        final int SAMPLE_COUNT = 10;
        // Use relaxed threshold for village-directed curves
        final int MAX_DROPOFF = relaxedValidation ? 15 : 5; // Maximum blocks the terrain can drop below track level

        BlockPos start = params.start;
        int startY = start.getY();
        int elevationChange = params.elevationChange;
        Direction trackDir = params.trackDirection;
        boolean turnLeft = params.turnLeft;
        int radius = params.radius;

        Direction turnDir = DirectionUtil.getPerpendicularDirection(trackDir, turnLeft);

        // For a 90° curve, sample points along the quarter-circle arc
        for (int i = 1; i <= SAMPLE_COUNT; i++) {
            // Progress along arc: 0.0 = start, 1.0 = end
            double progress = (double) i / SAMPLE_COUNT;

            // Calculate position along the arc
            // For a quarter circle (90°): angle ranges from 0° to 90°
            double angleRadians = progress * Math.PI / 2.0; // 0 to π/2

            // Distance traveled along forward direction
            double forwardDist = radius * Math.sin(angleRadians);
            // Distance traveled perpendicular (turn direction)
            double sideDist = radius * (1.0 - Math.cos(angleRadians));

            int dx = (int)(trackDir.getStepX() * forwardDist + turnDir.getStepX() * sideDist);
            int dz = (int)(trackDir.getStepZ() * forwardDist + turnDir.getStepZ() * sideDist);
            BlockPos samplePos = start.offset(dx, 0, dz);

            int terrainHeight = TerrainScanner.getGroundLevelAt(level, samplePos);
            int expectedTrackY = startY + (int)(elevationChange * progress);
            int dropAmount = expectedTrackY - terrainHeight;

            if (dropAmount > MAX_DROPOFF) {
                return false;
            }
        }

        return true;
    }

    /**
     * Creates curve parameters with an exact radius (no clamping) and no validation.
     *
     * @param start Starting position
     * @param trackDirection Current track direction
     * @param radius Exact radius to use (NOT clamped - must match planned value exactly)
     * @param turnLeft Turn direction
     * @param elevationChange Elevation change during curve
     * @return CurveParameters with the exact specified radius
     */
    public static CurveParameters createWithExactRadius(
            BlockPos start,
            Direction trackDirection,
            int radius,
            boolean turnLeft,
            int elevationChange) {

        BlockPos end = CreateTrackUtil.calculateCurveEndpoint(start, trackDirection, turnLeft, CURVE_ANGLE, radius, elevationChange);

        return new CurveParameters(CURVE_ANGLE, turnLeft, radius, start, end, trackDirection, elevationChange);
    }

    /**
     * Creates curve parameters with a specific radius and elevation change.
     *
     * @param level Server level for endpoint calculation and validation
     * @param start Starting position
     * @param trackDirection Current track direction
     * @param radius Exact radius to use (will be clamped to valid range)
     * @param turnLeft Turn direction
     * @param elevationChange Elevation change during curve (positive = up, negative = down, 0 = flat)
     * @param skipValidation If true, skip chunk and ground validation
     * @return CurveParameters with the specified radius and elevation
     */
    @Nullable
    public static CurveParameters createWithRadius(
            ServerLevel level,
            BlockPos start,
            Direction trackDirection,
            int radius,
            boolean turnLeft,
            int elevationChange,
            boolean skipValidation) {

        int clampedRadius = Math.max(RailwaysUntoldConfig.getMinCurveRadius(), Math.min(RailwaysUntoldConfig.getMaxCurveRadius(), radius));

        BlockPos end = CreateTrackUtil.calculateCurveEndpoint(start, trackDirection, turnLeft, CURVE_ANGLE, clampedRadius, elevationChange);

        if (!skipValidation) {
            if (!ChunkVerificationUtil.areEndpointChunksLoaded(level, start, end)) {
                return null;
            }

            if (!TerrainScanner.hasValidGroundForTrack(level, end)) {
                return null;
            }
        }

        return new CurveParameters(CURVE_ANGLE, turnLeft, clampedRadius, start, end, trackDirection, elevationChange);
    }

    /**
     * Parameters for a curve placement.
     */
    public static class CurveParameters {
        public final int angle;          // Curve angle in degrees (45 or 90)
        public final boolean turnLeft;   // Turn direction
        public final int radius;         // Curve radius in blocks
        public final BlockPos start;     // Starting position
        public final BlockPos end;       // Ending position
        public final Direction trackDirection; // Original track direction
        public final int elevationChange; // Elevation change during curve (positive = up, negative = down)

        public CurveParameters(int angle, boolean turnLeft, int radius,
                             BlockPos start, BlockPos end, Direction trackDirection) {
            this(angle, turnLeft, radius, start, end, trackDirection, 0);
        }

        public CurveParameters(int angle, boolean turnLeft, int radius,
                             BlockPos start, BlockPos end, Direction trackDirection, int elevationChange) {
            this.angle = angle;
            this.turnLeft = turnLeft;
            this.radius = radius;
            this.start = start;
            this.end = end;
            this.trackDirection = trackDirection;
            this.elevationChange = elevationChange;
        }

        @Override
        public String toString() {
            if (elevationChange != 0) {
                return String.format("CurveParameters{angle=%d°, turnLeft=%s, radius=%d, elevation=%+d, %s -> %s}",
                    angle, turnLeft, radius, elevationChange, start, end);
            }
            return String.format("CurveParameters{angle=%d°, turnLeft=%s, radius=%d, %s -> %s}",
                angle, turnLeft, radius, start, end);
        }
    }
}
