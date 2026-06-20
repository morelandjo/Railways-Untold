package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Analyzes registered coarse routes for proximity conflicts.
 * Detects crossings and near-parallel segments.
 */
public class RouteConflictAnalyzer {

    private static final int NEAR_PARALLEL_MIN_OVERLAP = 3;

    /**
     * Minimum clearance between routes: two expansion envelopes must not overlap.
     */
    static int getMinRouteClearance() {
        return RailwaysUntoldConfig.getHorizontalExpansion() * 2;
    }


    public record RouteConflict(
            BlockPos approximateLocation,
            UUID headId1,
            UUID headId2,
            ConflictType type,
            int overlapStartOffset,
            int overlapEndOffset
    ) {}

    public enum ConflictType {
        CROSSING,
        NEAR_PARALLEL
    }

    /**
     * Detects all conflicts between a new route and all existing registered routes.
     *
     * @param newRoute             The new route to analyze
     * @param headId               Head ID of the new route
     * @param registry             Registry of all existing routes
     * @param horizontalTolerance  Distance threshold for conflict detection
     * @return List of detected conflicts
     */
    public static List<RouteConflict> analyzeConflicts(
            CoarseRoute newRoute, UUID headId,
            CoarseRouteRegistry registry, int horizontalTolerance) {

        List<RouteConflict> conflicts = new ArrayList<>();
        List<CoarseRoute.CoarseWaypoint> newWaypoints = newRoute.getWaypoints();

        if (newWaypoints.size() < 2) return conflicts;

        for (CoarseRoute existingRoute : registry.getAllRoutes()) {
            if (existingRoute.getHeadId().equals(headId)) continue;

            List<CoarseRoute.CoarseWaypoint> existingWaypoints = existingRoute.getWaypoints();
            if (existingWaypoints.size() < 2) continue;

            List<ProximityMatch> matches = findProximityMatches(
                    newWaypoints, existingWaypoints, horizontalTolerance);

            if (matches.isEmpty()) continue;

            classifyAndAddConflicts(conflicts, matches, headId, existingRoute.getHeadId(),
                    newWaypoints, existingWaypoints);
        }

        return conflicts;
    }

    private record ProximityMatch(int newIndex, int existingIndex, double horizontalDist) {}

    private static List<ProximityMatch> findProximityMatches(
            List<CoarseRoute.CoarseWaypoint> newWps,
            List<CoarseRoute.CoarseWaypoint> existingWps,
            int tolerance) {

        List<ProximityMatch> matches = new ArrayList<>();

        for (int i = 0; i < newWps.size(); i++) {
            BlockPos newPos = newWps.get(i).position();

            for (int j = 0; j < existingWps.size(); j++) {
                BlockPos existPos = existingWps.get(j).position();

                double dx = newPos.getX() - existPos.getX();
                double dz = newPos.getZ() - existPos.getZ();
                double distSq = dx * dx + dz * dz;

                if (distSq <= tolerance * tolerance) {
                    matches.add(new ProximityMatch(i, j, Math.sqrt(distSq)));
                }
            }
        }

        return matches;
    }

    private static void classifyAndAddConflicts(
            List<RouteConflict> conflicts, List<ProximityMatch> matches,
            UUID headId1, UUID headId2,
            List<CoarseRoute.CoarseWaypoint> newWps,
            List<CoarseRoute.CoarseWaypoint> existingWps) {

        if (matches.size() < NEAR_PARALLEL_MIN_OVERLAP) {
            // Few matches = a crossing point. Only a near-perpendicular touch needs
            // vertical separation; a glancing (non-perpendicular) touch needs no action.
            ProximityMatch closest = matches.stream()
                    .min((a, b) -> Double.compare(a.horizontalDist, b.horizontalDist))
                    .orElseThrow(); // matches is non-empty (caller skips empty match lists)

            boolean perpendicular = areRoutesPerpendicular(
                    newWps, closest.newIndex, existingWps, closest.existingIndex);

            if (perpendicular) {
                BlockPos location = newWps.get(closest.newIndex).position();
                conflicts.add(new RouteConflict(location, headId1, headId2,
                        ConflictType.CROSSING, closest.newIndex, closest.newIndex));
            }
        } else {
            // Many matches = routes run close together for a stretch. Separate them,
            // unless their ends nearly coincide (merging to the same target - leave them).
            int startIdx = matches.stream().mapToInt(m -> m.newIndex).min().orElse(0);
            int endIdx = matches.stream().mapToInt(m -> m.newIndex).max().orElse(0);

            BlockPos newEnd = newWps.get(newWps.size() - 1).position();
            BlockPos existEnd = existingWps.get(existingWps.size() - 1).position();
            double endDist = Math.sqrt(
                    Math.pow(newEnd.getX() - existEnd.getX(), 2)
                  + Math.pow(newEnd.getZ() - existEnd.getZ(), 2));

            if (endDist >= getMinRouteClearance() * 4) {
                BlockPos location = newWps.get((startIdx + endIdx) / 2).position();
                conflicts.add(new RouteConflict(location, headId1, headId2,
                        ConflictType.NEAR_PARALLEL, startIdx, endIdx));
            }
        }
    }

    private static boolean areRoutesPerpendicular(
            List<CoarseRoute.CoarseWaypoint> route1, int idx1,
            List<CoarseRoute.CoarseWaypoint> route2, int idx2) {

        double dx1 = getRouteDx(route1, idx1);
        double dz1 = getRouteDz(route1, idx1);
        double dx2 = getRouteDx(route2, idx2);
        double dz2 = getRouteDz(route2, idx2);

        // Dot product of direction vectors - near zero = perpendicular
        double dot = dx1 * dx2 + dz1 * dz2;
        double mag1 = Math.sqrt(dx1 * dx1 + dz1 * dz1);
        double mag2 = Math.sqrt(dx2 * dx2 + dz2 * dz2);

        if (mag1 < 0.001 || mag2 < 0.001) return false;

        double cosAngle = Math.abs(dot / (mag1 * mag2));
        return cosAngle < 0.5; // Less than 60 degrees from perpendicular
    }

    private static double getRouteDx(List<CoarseRoute.CoarseWaypoint> route, int idx) {
        int prev = Math.max(0, idx - 1);
        int next = Math.min(route.size() - 1, idx + 1);
        return route.get(next).position().getX() - route.get(prev).position().getX();
    }

    private static double getRouteDz(List<CoarseRoute.CoarseWaypoint> route, int idx) {
        int prev = Math.max(0, idx - 1);
        int next = Math.min(route.size() - 1, idx + 1);
        return route.get(next).position().getZ() - route.get(prev).position().getZ();
    }
}
