package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.planner.PathSegment;
import com.vodmordia.railwaysuntold.worldgen.planner.PlannedPath;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.CoarseWaypoint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.List;

/**
 * Serializes a {@link CoarseRoutePlanner.PlanResult} into a stable, human-diffable
 * text snapshot. Captures the cut points the refactor must preserve: coarse
 * waypoints and the compiled PlannedPath segments. Output must depend only on the
 * route data, never on object identity, iteration order of maps, clocks, or hash codes.
 */
final class GoldenSnapshot {

    private GoldenSnapshot() {}

    static String serialize(CoarseRoutePlanner.PlanResult result) {
        StringBuilder sb = new StringBuilder();
        PrecisionRoute route = result.route();
        CoarseRoute coarse = route.getCoarseRoute();
        PlannedPath path = route.getPrecisionPath();

        List<CoarseWaypoint> waypoints = coarse.getWaypoints();
        sb.append("== COARSE WAYPOINTS (").append(waypoints.size()).append(") ==\n");
        for (int i = 0; i < waypoints.size(); i++) {
            CoarseWaypoint wp = waypoints.get(i);
            sb.append(String.format("WP[%d] %s advisedY=%d type=%s\n",
                    i, pos(wp.position()), wp.advisedTrackY(), wp.type()));
        }

        sb.append("== PLANNED PATH ==\n");
        sb.append(String.format("valid=%s finalPos=%s finalDir=%s invalidCode=%s invalidReason=%s\n",
                path.valid, pos(path.finalPosition), dir(path.finalDirection),
                path.invalidReasonCode, path.invalidReason));
        sb.append("== PATH SEGMENTS (").append(path.segments.size()).append(") ==\n");
        for (int i = 0; i < path.segments.size(); i++) {
            PathSegment s = path.segments.get(i);
            sb.append(String.format("SEG[%d] %s type=%s start=%s startDir=%s end=%s endDir=%s | %s\n",
                    i, s.getClass().getSimpleName(), s.type,
                    pos(s.getStart()), dir(s.getStartDirection()),
                    pos(s.getEndPosition()), dir(s.getEndDirection()), s));
        }

        sb.append("== SELECTED ALTERNATIVE ==\n");
        if (result.selectedAlternative() == null) {
            sb.append("none\n");
        } else {
            var alt = result.selectedAlternative();
            sb.append(String.format("arrivalPos=%s arrivalDir=%s\n",
                    pos(alt.arrivalPos()), dir(alt.arrivalDir())));
        }

        return sb.toString();
    }

    private static String pos(BlockPos p) {
        if (p == null) return "null";
        return "(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")";
    }

    private static String dir(Direction d) {
        return d == null ? "null" : d.name();
    }
}
