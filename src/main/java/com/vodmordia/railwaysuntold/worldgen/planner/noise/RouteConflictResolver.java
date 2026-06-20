package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.CoarseWaypoint;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.WaypointType;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.RouteConflictAnalyzer.ConflictType;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.RouteConflictAnalyzer.RouteConflict;
import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Resolves route conflicts by adjusting waypoint elevations.
 */
public class RouteConflictResolver {
    private static int getVerticalSeparation() {
        return com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig.getVerticalExpansion() + 1;
    }

    public record Resolution(
            ConflictType conflictType,
            CrossingResolution crossingResolution,
            List<WaypointAdjustment> adjustments,
            @Nullable BlockPos conflictLocation
    ) {}

    public record WaypointAdjustment(int waypointIndex, int newAdvisedY, WaypointType newType) {}

    public enum CrossingResolution {
        OVERPASS,
        UNDERPASS
    }

    /**
     * Resolves all conflicts for a route.
     *
     * @param route     The route to adjust
     * @param headId    Head ID of the route
     * @param conflicts Detected conflicts
     * @param registry  Route registry for lookups
     * @return List of resolutions with waypoint adjustments
     */
    public static List<Resolution> resolveConflicts(
            CoarseRoute route,
            List<RouteConflict> conflicts,
            CoarseRouteRegistry registry) {

        if (conflicts.isEmpty()) return Collections.emptyList();

        List<Resolution> resolutions = new ArrayList<>();

        for (RouteConflict conflict : conflicts) {
            Resolution resolution = resolveConflict(route, conflict, registry);
            if (resolution != null) {
                resolutions.add(resolution);
            }
        }

        return resolutions;
    }

    private static Resolution resolveConflict(
            CoarseRoute route, RouteConflict conflict, CoarseRouteRegistry registry) {

        return switch (conflict.type()) {
            case CROSSING -> resolveCrossing(route, conflict, registry);
            case NEAR_PARALLEL -> resolveNearParallel(route, conflict, registry);
        };
    }

    private static Resolution resolveCrossing(
            CoarseRoute route, RouteConflict conflict, CoarseRouteRegistry registry) {

        List<CoarseWaypoint> waypoints = route.getWaypoints();
        int crossIdx = conflict.overlapStartOffset();

        if (crossIdx < 0 || crossIdx >= waypoints.size()) return null;

        CoarseRoute otherRoute = registry.getRoute(conflict.headId2());
        if (otherRoute == null) return null;

        CoarseWaypoint crossWp = waypoints.get(crossIdx);
        CoarseWaypoint nearestOther = otherRoute.findNearestWaypoint(crossWp.position());
        if (nearestOther == null) return null;

        int yDiff = crossWp.advisedTrackY() - nearestOther.advisedTrackY();

        CrossingResolution crossingType;
        List<WaypointAdjustment> adjustments = new ArrayList<>();

        if (Math.abs(yDiff) <= 2) {
            // Routes are at similar Y. Resolve deterministically on a stable key: the higher-UUID
            // head crosses over, the lower-UUID head crosses under, each placed one vertSep from
            // the other's current Y. This is symmetric and idempotent under re-registration - the
            // resolving head always moves to its own slot, so the pair can't both pick OVER and
            // escalate, and a separated pair stays put (the natural-separation branches below).
            int vertSep = getVerticalSeparation();
            boolean thisOver = conflict.headId1().compareTo(conflict.headId2()) > 0;
            int newY = thisOver ? nearestOther.advisedTrackY() + vertSep
                                : nearestOther.advisedTrackY() - vertSep;
            crossingType = thisOver ? CrossingResolution.OVERPASS : CrossingResolution.UNDERPASS;
            WaypointType crossWpType = thisOver ? WaypointType.CROSSING_OVER : WaypointType.CROSSING_UNDER;

            // Surrounding waypoints ramp back to their advised Y over a slope-derived span (shared
            // with the route-vs-placed-track resolver) so the crossing is slope-legal by construction.
            CrossingRamp.addAdjustments(adjustments, waypoints, crossIdx, newY, crossWpType);

        } else if (yDiff > 0) {
            // This route is already above - maintain as overpass
            crossingType = CrossingResolution.OVERPASS;
        } else {
            // This route is below - maintain as underpass
            crossingType = CrossingResolution.UNDERPASS;
        }

        return new Resolution(ConflictType.CROSSING, crossingType, adjustments, conflict.approximateLocation());
    }

    private static Resolution resolveNearParallel(
            CoarseRoute route, RouteConflict conflict, CoarseRouteRegistry registry) {

        List<CoarseWaypoint> waypoints = route.getWaypoints();
        CoarseRoute otherRoute = registry.getRoute(conflict.headId2());
        if (otherRoute == null) return null;

        List<WaypointAdjustment> adjustments = new ArrayList<>();
        int startIdx = conflict.overlapStartOffset();
        int endIdx = Math.min(conflict.overlapEndOffset(), waypoints.size() - 1);

        for (int i = startIdx; i <= endIdx; i++) {
            CoarseWaypoint wp = waypoints.get(i);
            CoarseWaypoint nearestOther = otherRoute.findNearestWaypoint(wp.position());
            if (nearestOther == null) continue;

            int yDiff = wp.advisedTrackY() - nearestOther.advisedTrackY();
            int vertSep = getVerticalSeparation();
            if (Math.abs(yDiff) < vertSep) {
                // Push this route up to create vertical separation
                int newY = nearestOther.advisedTrackY() + vertSep;
                adjustments.add(new WaypointAdjustment(i, newY, WaypointType.TERRAIN_FOLLOW));
            }
        }

        return new Resolution(ConflictType.NEAR_PARALLEL, null, adjustments, conflict.approximateLocation());
    }
}
