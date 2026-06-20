package com.vodmordia.railwaysuntold.util.track;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;

/**
 * Utility for validating track slopes.
 *
 */
public final class SlopeValidator {

    private SlopeValidator() {}

    /**
     * Number of blocks at bezier endpoints that render as straight/flat.
     */
    public static final int STRAIGHT_ENDPOINT_BLOCKS = 2;

    /**
     * Validates if a bezier configuration has an acceptable slope.
     *
     * @param horizontalDistance Horizontal run distance in blocks
     * @param elevationChange Height difference (absolute value will be used)
     * @return true if the slope is within acceptable limits
     */
    public static boolean isValidSlope(int horizontalDistance, int elevationChange) {
        if (horizontalDistance <= 0) {
            return elevationChange == 0;
        }

        int absElevation = Math.abs(elevationChange);

        // No elevation change is always valid
        if (absElevation == 0) {
            return true;
        }

        // Account for straight endpoint blocks - only middle blocks show slope
        int effectiveDistance = horizontalDistance - STRAIGHT_ENDPOINT_BLOCKS;
        if (effectiveDistance <= 0) {
            // Not enough distance for any angled blocks
            return false;
        }

        double slopeRatio = (double) absElevation / effectiveDistance;
        return slopeRatio <= RailwaysUntoldConfig.getMaxSlopeRatio();
    }

    /**
     * Calculates the minimum horizontal distance required for a given elevation change.
     *
     * @param elevationChange Height difference (absolute value will be used)
     * @return Minimum horizontal distance in blocks
     */
    public static int getMinimumDistance(int elevationChange) {
        int absElevation = Math.abs(elevationChange);
        if (absElevation == 0) {
            return 0;
        }
        int angledBlocksNeeded = (int) Math.ceil(absElevation / RailwaysUntoldConfig.getMaxSlopeRatio());
        return angledBlocksNeeded + STRAIGHT_ENDPOINT_BLOCKS;
    }

    /**
     * Clamps endY so the segment from startY over horizontalDistance respects the
     * configured max slope ratio. Returns endY unchanged when already valid; otherwise
     * the steepest slope-legal endY in the same direction. The single clamp every
     * slope-enforcement site shares (precision emit sites, the placement executor,
     * and the path planner) so a too-steep request lands short rather than being
     * rejected.
     */
    public static int clampEndYToSlopeLimit(int startY, int endY, int horizontalDistance) {
        if (horizontalDistance <= 0) return startY;
        int elev = endY - startY;
        if (isValidSlope(horizontalDistance, elev)) {
            return endY;
        }
        int sign = elev > 0 ? 1 : -1;
        int eff = Math.max(0, horizontalDistance - STRAIGHT_ENDPOINT_BLOCKS);
        int maxElev = (int) Math.floor(eff * RailwaysUntoldConfig.getMaxSlopeRatio());
        // Walk down until isValidSlope returns true - guards against rounding edge
        // cases where floor(eff * ratio) still fails the validator's ratio compare.
        while (maxElev > 0 && !isValidSlope(horizontalDistance, sign * maxElev)) {
            maxElev--;
        }
        return startY + sign * maxElev;
    }
}
