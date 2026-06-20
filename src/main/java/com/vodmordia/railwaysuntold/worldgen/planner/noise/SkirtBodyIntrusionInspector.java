package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.planner.PathSegment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.List;

/**
 * Guards against a route driving THROUGH the body of the village it is heading to. The skirt obstacle
 * keeps the coarse corridor out of the body, but the final approach maneuver (the tail that reaches the
 * station's arrival pose) is not itself re-checked against the body - so it can curve back and cut
 * across the village it just routed around. The station sits OUTSIDE the body, so any compiled segment
 * whose path passes inside the body's footprint (within a small vertical band of it) is an over-drive,
 * not a legitimate arrival. A route that intrudes is rejected upstream and the village abandoned, which
 * stops the visible "track laid over the houses" bug.
 */
public final class SkirtBodyIntrusionInspector {

    /** The route may pass this far above/below the body's Y extent and still count as "through" it,
     *  so a track skimming just over the rooftops is caught, while a genuinely high viaduct is not. */
    private static final int Y_BAND = 6;

    private SkirtBodyIntrusionInspector() {}

    /** Index of the first segment that intrudes the village body, or -1 if the route stays clear. */
    public static int findBodyIntrusion(List<PathSegment> segments, List<BoundingBox> bodyBoxes) {
        if (segments == null || bodyBoxes == null || bodyBoxes.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < segments.size(); i++) {
            PathSegment s = segments.get(i);
            if (s == null) continue;
            BlockPos start = s.getStart();
            BlockPos end = s.getEndPosition();
            if (start == null || end == null) continue;
            if (segmentIntrudes(start, end, bodyBoxes)) {
                return i;
            }
        }
        return -1;
    }

    /** True if the straight path from {@code a} to {@code b} passes inside any body box. */
    static boolean segmentIntrudes(BlockPos a, BlockPos b, List<BoundingBox> bodyBoxes) {
        int steps = Math.max(Math.max(Math.abs(b.getX() - a.getX()), Math.abs(b.getZ() - a.getZ())), 1);
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            int x = (int) Math.round(a.getX() + t * (b.getX() - a.getX()));
            int y = (int) Math.round(a.getY() + t * (b.getY() - a.getY()));
            int z = (int) Math.round(a.getZ() + t * (b.getZ() - a.getZ()));
            for (BoundingBox box : bodyBoxes) {
                if (x >= box.minX() && x <= box.maxX()
                        && z >= box.minZ() && z <= box.maxZ()
                        && y >= box.minY() - Y_BAND && y <= box.maxY() + Y_BAND) {
                    return true;
                }
            }
        }
        return false;
    }
}
