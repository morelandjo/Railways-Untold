package com.vodmordia.railwaysuntold.util.spatial;

import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Shared utility for testing whether a 2D line segment intersects an axis-aligned bounding box.
 */
public final class TrajectoryIntersection {

    private TrajectoryIntersection() {}

    /**
     * Tests if a 2D line segment from (x0,z0) to (x1,z1) intersects the XZ footprint of the box.
     * Uses the parametric slab intersection algorithm.
     */
    public static boolean lineIntersectsBox(int x0, int z0, int x1, int z1, BoundingBox box) {
        double dx = x1 - x0;
        double dz = z1 - z0;

        double tMin = 0.0;
        double tMax = 1.0;

        // X slab
        if (dx == 0) {
            if (x0 < box.minX() || x0 > box.maxX()) return false;
        } else {
            double invD = 1.0 / dx;
            double t1 = (box.minX() - x0) * invD;
            double t2 = (box.maxX() - x0) * invD;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return false;
        }

        // Z slab
        if (dz == 0) {
            if (z0 < box.minZ() || z0 > box.maxZ()) return false;
        } else {
            double invD = 1.0 / dz;
            double t1 = (box.minZ() - z0) * invD;
            double t2 = (box.maxZ() - z0) * invD;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return false;
        }

        return true;
    }

}
