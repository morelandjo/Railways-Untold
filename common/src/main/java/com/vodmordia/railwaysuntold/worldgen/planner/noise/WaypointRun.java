package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.CoarseWaypoint;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.WaypointType;

import java.util.List;

/**
 * A maximal run of consecutive coarse waypoints sharing a normalized type, as grouped by
 * the precision compiler before per-run segment emission.
 */
record WaypointRun(WaypointType type, List<CoarseWaypoint> waypoints) {
    CoarseWaypoint firstWaypoint() { return waypoints.get(0); }
    CoarseWaypoint lastWaypoint() { return waypoints.get(waypoints.size() - 1); }
}
