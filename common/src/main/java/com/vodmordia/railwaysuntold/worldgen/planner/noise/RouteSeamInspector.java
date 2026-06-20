package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.planner.PathSegment;
import com.vodmordia.railwaysuntold.worldgen.planner.PlannedPath;
import net.minecraft.core.Direction;

import java.util.List;

/**
 * Inspects a compiled path for illegal seams between consecutive segments. A well-formed route flows
 * continuously - each segment's exit direction equals the next segment's entry direction. A divergence
 * of 90° or more at a junction is a kink no curve absorbs (e.g. a lateral-correction S-curve grafted
 * onto an approach at a sharp angle): track a Create train cannot traverse.
 *
 * The existing curve-radius guard checks each curve's own radius; it does not look at the junction
 * BETWEEN segments, which is where this class of illegal geometry lives.
 */
public final class RouteSeamInspector {

    private RouteSeamInspector() {}

    /** Junction index {@code i} (between segment i and i+1) of the first illegal seam, or -1 if none. */
    public static int findIllegalSeam(PlannedPath path) {
        if (path == null || !path.valid || path.segments == null) {
            return -1;
        }
        return findIllegalSeam(path.segments);
    }

    static int findIllegalSeam(List<PathSegment> segments) {
        for (int i = 0; i < segments.size() - 1; i++) {
            Direction exit = segments.get(i).getEndDirection();
            Direction entry = segments.get(i + 1).getStartDirection();
            if (exit == null || entry == null) {
                continue;
            }
            if (!exit.getAxis().isHorizontal() || !entry.getAxis().isHorizontal()) {
                continue;
            }
            // dot of the unit step vectors: +1 aligned (continuous), 0 perpendicular, -1 reversed.
            int dot = exit.getStepX() * entry.getStepX() + exit.getStepZ() * entry.getStepZ();
            if (dot <= 0) {
                return i;
            }
        }
        return -1;
    }
}
