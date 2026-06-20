package com.vodmordia.railwaysuntold.util.placement;

import com.vodmordia.railwaysuntold.util.spatial.DirectionUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Geometry calculations for 45-degree S-curves.
 *
 * A 45Scurve consists of:
 * 1. A 45-degree curve from cardinal to diagonal direction
 * 2. A straight diagonal segment
 * 3. A 45-degree curve from diagonal back to the original cardinal direction
 *
 */
public final class SCurve45Geometry {

    public static final double COS_45 = Math.cos(Math.PI / 4);  // ~0.707
    public static final double SIN_45 = Math.sin(Math.PI / 4);  // ~0.707

    private SCurve45Geometry() {
    }

    /**
     * Calculates the endpoint of the complete S-curve.
     *
     * @param start           Starting position
     * @param heading         Starting (and ending) cardinal direction
     * @param shiftLeft       True to shift left, false to shift right
     * @param radius          Curve radius in blocks
     * @param diagonalLength  Length of diagonal segment in blocks
     * @param elevationChange Total elevation change across the S-curve
     * @return The endpoint position
     */
    public static BlockPos calculateEndpoint(
            BlockPos start,
            Direction heading,
            boolean shiftLeft,
            int radius,
            int diagonalLength,
            int elevationChange) {

        BlockPos diagonalEnd = calculateDiagonalEndFromFinal(start, heading, shiftLeft, radius, diagonalLength, elevationChange);

        int secondCurveForward = (int) Math.ceil(radius * SIN_45);
        int secondCurveLateral = (int) Math.ceil(radius * (1 - COS_45));

        Direction lateralDir = DirectionUtil.getPerpendicularDirection(heading, shiftLeft);

        int dx = heading.getStepX() * secondCurveForward + lateralDir.getStepX() * secondCurveLateral;
        int dz = heading.getStepZ() * secondCurveForward + lateralDir.getStepZ() * secondCurveLateral;

        int secondCurveElev = distributeElevationForSegment(elevationChange, radius, diagonalLength, 2);

        return new BlockPos(diagonalEnd.getX() + dx, diagonalEnd.getY() + secondCurveElev, diagonalEnd.getZ() + dz);
    }

    /**
     * Calculates the position after the first 45-degree curve.
     */
    public static BlockPos calculateFirstCurveEnd(
            BlockPos start,
            Direction heading,
            boolean shiftLeft,
            int radius,
            int elevationForFirstCurve) {

        int forwardDist = (int) Math.ceil(radius * SIN_45);
        int lateralDist = (int) Math.ceil(radius * (1 - COS_45));

        Direction lateralDir = DirectionUtil.getPerpendicularDirection(heading, shiftLeft);

        int dx = heading.getStepX() * forwardDist + lateralDir.getStepX() * lateralDist;
        int dz = heading.getStepZ() * forwardDist + lateralDir.getStepZ() * lateralDist;

        return start.offset(dx, elevationForFirstCurve, dz);
    }

    /**
     * Calculates the position after the diagonal segment (before second curve).
     *
     * @param start           Original starting position
     * @param heading         Cardinal direction of travel
     * @param shiftLeft       Direction of lateral shift
     * @param radius          Curve radius
     * @param diagonalLength  Length of diagonal segment
     * @param elevationChange Total elevation change
     * @return Position at the end of the diagonal segment (before second curve)
     */
    public static BlockPos calculateDiagonalEndFromFinal(
            BlockPos start,
            Direction heading,
            boolean shiftLeft,
            int radius,
            int diagonalLength,
            int elevationChange) {

        int firstCurveElev = distributeElevationForSegment(elevationChange, radius, diagonalLength, 0);
        BlockPos firstCurveEnd = calculateFirstCurveEnd(start, heading, shiftLeft, radius, firstCurveElev);

        int diagForward = (int) Math.ceil(diagonalLength * COS_45);
        int diagLateral = (int) Math.ceil(diagonalLength * SIN_45);

        Direction lateralDir = DirectionUtil.getPerpendicularDirection(heading, shiftLeft);

        int dx = heading.getStepX() * diagForward + lateralDir.getStepX() * diagLateral;
        int dz = heading.getStepZ() * diagForward + lateralDir.getStepZ() * diagLateral;

        int diagonalElev = distributeElevationForSegment(elevationChange, radius, diagonalLength, 1);
        int diagonalEndY = firstCurveEnd.getY() + diagonalElev;

        return new BlockPos(firstCurveEnd.getX() + dx, diagonalEndY, firstCurveEnd.getZ() + dz);
    }

    /**
     * Distributes elevation change across a segment of the S-curve.
     * @param totalElevation Total elevation change
     * @param radius Curve radius
     * @param diagonalLength Diagonal segment length
     * @param segment 0=first curve, 1=diagonal, 2=second curve
     * @return Elevation change for this segment
     */
    public static int distributeElevationForSegment(int totalElevation, int radius, int diagonalLength, int segment) {
        if (totalElevation == 0) return 0;

        int curve1Len = calculate45CurveArcLength(radius);
        int curve2Len = curve1Len;
        int totalLen = curve1Len + diagonalLength + curve2Len;

        int firstCurveElev = (int) Math.round((double) totalElevation * curve1Len / totalLen);
        int diagonalElev = (int) Math.round((double) totalElevation * diagonalLength / totalLen);
        int secondCurveElev = totalElevation - firstCurveElev - diagonalElev;

        return switch (segment) {
            case 0 -> firstCurveElev;
            case 1 -> diagonalElev;
            case 2 -> secondCurveElev;
            default -> 0;
        };
    }

    /**
     * Calculates the approximate arc length of a single 45-degree curve.
     * Arc length = radius * angle_in_radians = radius * (PI/4)
     */
    public static int calculate45CurveArcLength(int radius) {
        return (int) Math.ceil(radius * Math.PI / 4);
    }

    /**
     * Calculates the total path length of the S-curve (for elevation distribution).
     * Total = two 45° arcs + diagonal straight segment
     */
    public static int calculateTotalPathLength(int radius, int diagonalLength) {
        return 2 * calculate45CurveArcLength(radius) + diagonalLength;
    }

    /**
     * Calculates the diagonal length needed to achieve a desired lateral shift.
     * Inverse of: lateral = 2*R*(1-cos45) + diagLen*sin45
     */
    public static int calculateDiagonalLengthForLateralShift(int desiredLateral, int radius) {
        double curveLateral = 2.0 * radius * (1 - COS_45);
        double remaining = Math.abs(desiredLateral) - curveLateral;
        if (remaining <= 0) return 0;
        return (int) Math.ceil(remaining / SIN_45);
    }

    /**
     * Calculates the forward (along cardinal axis) distance consumed by an S-curve.
     * forward = 2*R*sin45 + diagLen*cos45
     */
    public static int calculateForwardDistance(int radius, int diagonalLength) {
        return (int) Math.ceil(2 * radius * SIN_45 + diagonalLength * COS_45);
    }

}
