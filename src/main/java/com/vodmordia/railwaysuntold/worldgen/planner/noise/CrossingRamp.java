package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.CoarseWaypoint;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.WaypointType;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.RouteConflictResolver.WaypointAdjustment;

import java.util.List;

/**
 * Shared slope-derived crossing ramp. A crossing pushes one waypoint up (overpass) or down
 * (underpass) for vertical clearance; the surrounding waypoints ramp back to their advised Y over
 * enough coarse waypoints that no segment exceeds the rise/run limit. Both the route-vs-placed-track
 * resolver and the route-vs-route resolver build the ramp this way so a crossing is slope-legal by
 * construction (nothing re-validates slope between here and the precision compile).
 */
final class CrossingRamp {

    private CrossingRamp() {}

    /**
     * Number of coarse waypoints the ramp spans on each side of the crossing, derived from the
     * configured max slope so the elevation change spreads gently enough to stay within the limit.
     */
    static int rampWaypoints(int elevationChange) {
        double maxSlopeRatio = RailwaysUntoldConfig.getMaxSlopeRatio();
        if (maxSlopeRatio <= 0) return 3;
        int rampBlocks = (int) Math.ceil(Math.abs(elevationChange) / maxSlopeRatio);
        return Math.max(1, (int) Math.ceil((double) rampBlocks / CoarseRoutePlanner.SAMPLE_INTERVAL));
    }

    /**
     * Appends the crossing-point adjustment (to {@code targetY} with {@code crossingType}) plus a
     * linear ramp on each side, from each surrounding waypoint's advised Y toward {@code targetY},
     * over a slope-derived number of waypoints. Adjustments are added crossing-point first.
     */
    static void addAdjustments(List<WaypointAdjustment> out, List<CoarseWaypoint> waypoints,
                               int crossIdx, int targetY, WaypointType crossingType) {
        addAdjustments(out, waypoints, crossIdx, targetY, crossingType,
                waypoints.get(crossIdx).advisedTrackY());
    }

    /**
     * Variant that sizes the ramp from an explicit {@code crossingCurrentY} rather than the crossing
     * waypoint's advised Y. The placed-track detector passes the route's slope-achievable Y here, which
     * can differ sharply from the advised (terrain-preference) Y when the route is mid-descent - sizing
     * the ramp off the advised Y would otherwise produce a wildly over-long ramp.
     */
    static void addAdjustments(List<WaypointAdjustment> out, List<CoarseWaypoint> waypoints,
                               int crossIdx, int targetY, WaypointType crossingType, int crossingCurrentY) {
        int rampWaypoints = rampWaypoints(Math.abs(targetY - crossingCurrentY));

        out.add(new WaypointAdjustment(crossIdx, targetY, crossingType));

        int rampStart = Math.max(0, crossIdx - rampWaypoints);
        for (int i = rampStart; i < crossIdx; i++) {
            int oldY = waypoints.get(i).advisedTrackY();
            double rampT = (double) (i - rampStart + 1) / (crossIdx - rampStart + 1);
            int rampY = (int) Math.round(oldY + rampT * (targetY - oldY));
            out.add(new WaypointAdjustment(i, rampY, WaypointType.TERRAIN_FOLLOW));
        }

        int rampEnd = Math.min(waypoints.size() - 1, crossIdx + rampWaypoints);
        for (int i = crossIdx + 1; i <= rampEnd; i++) {
            int oldY = waypoints.get(i).advisedTrackY();
            double rampT = 1.0 - (double) (i - crossIdx) / (rampEnd - crossIdx + 1);
            int rampY = (int) Math.round(oldY + rampT * (targetY - oldY));
            out.add(new WaypointAdjustment(i, rampY, WaypointType.TERRAIN_FOLLOW));
        }
    }
}
