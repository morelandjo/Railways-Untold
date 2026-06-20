package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import net.minecraft.server.level.ServerLevel;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-level registry of all active coarse routes.
 * Enables route conflict detection and crossing coordination.
 */
public class CoarseRouteRegistry {

    private final Map<UUID, CoarseRoute> headRoutes = new ConcurrentHashMap<>();

    private static final Map<ServerLevel, CoarseRouteRegistry> INSTANCES = new WeakHashMap<>();

    public static synchronized CoarseRouteRegistry forLevel(ServerLevel level) {
        return INSTANCES.computeIfAbsent(level, k -> new CoarseRouteRegistry());
    }

    /** Registers a route and runs conflict analysis/resolution. */
    public void registerRoute(UUID headId, CoarseRoute route) {
        registerRoute(headId, route, null);
    }

    /**
     * Registers a route and runs conflict analysis/resolution. When {@code level} is
     * non-null, also detects crossings against already-placed track via
     * {@link PlacedTrackConflictDetector}
     */
    public void registerRoute(UUID headId, CoarseRoute route, ServerLevel level) {
        headRoutes.put(headId, route);

        List<RouteConflictAnalyzer.RouteConflict> conflicts =
                RouteConflictAnalyzer.analyzeConflicts(route, headId, this, RouteConflictAnalyzer.getMinRouteClearance());

        if (!conflicts.isEmpty()) {
            List<RouteConflictResolver.Resolution> resolutions =
                    RouteConflictResolver.resolveConflicts(route, conflicts, this);
            applyResolutions(route, resolutions);
        }

        if (level != null) {
            List<RouteConflictResolver.Resolution> placedResolutions =
                    PlacedTrackConflictDetector.detect(route, level, headId);
            if (!placedResolutions.isEmpty()) {
                applyResolutions(route, placedResolutions);
            }
        }
    }

    public void removeRoute(UUID headId) {
        headRoutes.remove(headId);
    }

    public CoarseRoute getRoute(UUID headId) {
        return headRoutes.get(headId);
    }

    public Collection<CoarseRoute> getAllRoutes() {
        return Collections.unmodifiableCollection(headRoutes.values());
    }

    private void applyResolutions(CoarseRoute route, List<RouteConflictResolver.Resolution> resolutions) {
        for (RouteConflictResolver.Resolution resolution : resolutions) {
            for (RouteConflictResolver.WaypointAdjustment adj : resolution.adjustments()) {
                route.updateWaypoint(adj.waypointIndex(), adj.newAdvisedY(), adj.newType());
            }
        }
    }
}
