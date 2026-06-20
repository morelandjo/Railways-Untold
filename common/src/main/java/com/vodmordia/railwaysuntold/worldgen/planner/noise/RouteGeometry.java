package com.vodmordia.railwaysuntold.worldgen.planner.noise;

/**
 * Shared geometry primitives for the route planner. Callers opt in per call
 * site. Other similar-looking direction, perpendicular, projection and slope
 * formulas elsewhere in the planner are intentionally not routed through here
 * because their sign convention, rounding or truncation differs.
 */
final class RouteGeometry {

    private RouteGeometry() {}

    /**
     * Snaps a 2D direction vector to the nearest octagonal direction
     * (cardinal: 0°/90°/180°/270° or diagonal: 45°/135°/225°/315°).
     * Returns a normalized [dx, dz] pair.
     */
    static double[] snapToOctagonal(double dx, double dz) {
        double angle = Math.atan2(dz, dx);
        // Snap to nearest 45° increment
        double snapped = Math.round(angle / (Math.PI / 4)) * (Math.PI / 4);
        return new double[]{Math.cos(snapped), Math.sin(snapped)};
    }

    /**
     * Returns a perpendicular to the 2D direction (dx, dz). In Minecraft
     * coordinates (X east, Z south), sign +1 gives the left perpendicular
     * (dz, -dx) - travel direction rotated 90° counterclockwise - and sign -1
     * gives the right perpendicular. Each caller passes the sign for the side
     * it treats as positive.
     */
    static double[] perpendicular(double dx, double dz, double sign) {
        return new double[]{sign * dz, sign * -dx};
    }

    /**
     * 2D dot product of (ax, az) and (bx, bz). Callers that project onto a unit
     * direction use the result directly; callers projecting onto a finite segment
     * divide by the segment's squared length and clamp the parameter themselves.
     */
    static double dot(double ax, double az, double bx, double bz) {
        return ax * bx + az * bz;
    }
}
