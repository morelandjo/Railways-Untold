package com.vodmordia.railwaysuntold.worldgen.tracking;

import net.minecraft.core.BlockPos;

/**
 * Pure geometry utility functions for track segment calculations.
 */
public final class SegmentGeometryUtil {

    private SegmentGeometryUtil() {
    }

    public static boolean sharesEndpoint(BlockPos a1, BlockPos a2, BlockPos b1, BlockPos b2) {
        int horizontalTolerance = 5;
        int verticalTolerance = 3;
        return isNear3D(a1, b1, horizontalTolerance, verticalTolerance) ||
               isNear3D(a1, b2, horizontalTolerance, verticalTolerance) ||
               isNear3D(a2, b1, horizontalTolerance, verticalTolerance) ||
               isNear3D(a2, b2, horizontalTolerance, verticalTolerance);
    }

    public static boolean isNear3D(BlockPos a, BlockPos b, int horizontalTolerance, int verticalTolerance) {
        return Math.abs(a.getX() - b.getX()) <= horizontalTolerance &&
               Math.abs(a.getZ() - b.getZ()) <= horizontalTolerance &&
               Math.abs(a.getY() - b.getY()) <= verticalTolerance;
    }

}
