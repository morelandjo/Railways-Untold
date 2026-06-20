package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * A coarse-grained route from track head to village target, sampled from noise terrain.
 */
public class CoarseRoute {

    private final UUID headId;
    private final List<CoarseWaypoint> waypoints;

    public CoarseRoute(UUID headId, List<CoarseWaypoint> waypoints) {
        this.headId = headId;
        this.waypoints = new ArrayList<>(waypoints);
    }

    public record CoarseWaypoint(
            BlockPos position,
            int advisedTrackY,
            WaypointType type,
            YBasis yBasis
    ) {
        /** Builds a waypoint whose Y is a terrain preference. */
        public CoarseWaypoint(BlockPos position, int advisedTrackY, WaypointType type) {
            this(position, advisedTrackY, type, YBasis.PREFERENCE);
        }
    }

    /** Marks whether a waypoint's advisedTrackY is an adjustable terrain preference or a fixed constraint. */
    public enum YBasis {
        /** advisedTrackY follows the sampled terrain and slope passes may move it. */
        PREFERENCE,
        /** advisedTrackY is fixed by a terminal anchor, descent hint, bridge/tunnel grade, or crossing ramp. */
        CONSTRAINT
    }

    public enum WaypointType {
        TERRAIN_FOLLOW,
        BRIDGE,
        TUNNEL,
        FLAT_MAINTAIN,
        CURVE_AROUND,
        /** Bridge needed to cross over existing track (not terrain). */
        CROSSING_OVER,
        /** Tunnel needed to cross under existing track (not terrain). */
        CROSSING_UNDER
    }

    public UUID getHeadId() {
        return headId;
    }

    public List<CoarseWaypoint> getWaypoints() {
        return Collections.unmodifiableList(waypoints);
    }

    public int size() {
        return waypoints.size();
    }

    /** Finds the nearest waypoint by horizontal distance. */
    public CoarseWaypoint findNearestWaypoint(BlockPos pos) {
        if (waypoints.isEmpty()) return null;

        CoarseWaypoint nearest = null;
        double minDistSq = Double.MAX_VALUE;

        for (CoarseWaypoint wp : waypoints) {
            double dx = pos.getX() - wp.position.getX();
            double dz = pos.getZ() - wp.position.getZ();
            double distSq = dx * dx + dz * dz;
            if (distSq < minDistSq) {
                minDistSq = distSq;
                nearest = wp;
            }
        }

        return nearest;
    }

    /**
     * Updates a waypoint's advised Y and type. Used by conflict resolution, which sets
     * crossing-ramp heights that later passes must not override, so the Y becomes a constraint.
     */
    public void updateWaypoint(int index, int newAdvisedY, WaypointType newType) {
        if (index < 0 || index >= waypoints.size()) return;
        CoarseWaypoint old = waypoints.get(index);
        waypoints.set(index, new CoarseWaypoint(old.position, newAdvisedY, newType, YBasis.CONSTRAINT));
    }

}
