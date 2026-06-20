package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.worldgen.placement.analyzer.CrossingDetectionService;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.CoarseWaypoint;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.WaypointType;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.RouteConflictAnalyzer.ConflictType;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.RouteConflictResolver.CrossingResolution;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.RouteConflictResolver.Resolution;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.RouteConflictResolver.WaypointAdjustment;
import com.vodmordia.railwaysuntold.worldgen.tracking.BoundarySegmentData.ConnectedSegment;
import com.vodmordia.railwaysuntold.worldgen.tracking.ConnectedBoundaryTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.*;

/**
 * Detects crossings between a freshly-planned coarse route and already-placed track
 * (segments registered in {@link ConnectedBoundaryTracker}). The existing
 * {@link RouteConflictAnalyzer} only compares planned-vs-planned routes
 */
public class PlacedTrackConflictDetector {

    /** Min horizontal distance (in waypoints) between two reported crossings on this route. */
    private static final int MIN_CROSSING_SPACING_WAYPOINTS = 4;

    /** Within this Manhattan distance of the route origin, a crossing is treated as the head's branch
     *  junction (exempt); beyond it, a nearby track is a real crossing to resolve. */
    private static final int ORIGIN_BRANCH_DISTANCE = 32;

    /** Blocks to expand the candidate-collection bounding box, so a crossing track in an adjacent chunk
     *  (which the compiled bezier reaches even when the straight coarse segment does not) is still seen. */
    private static final int CANDIDATE_CHUNK_BUFFER = 16;

    /** A route within this many blocks of the placed track's vertical extent is "at grade" - it merges
     *  into the line at the crossing rather than ramping over/under. */
    private static final int NEAR_GRADE = 2;

    /** Tolerance (blocks) for matching a forced-crossing point to the segment it should ramp. The point
     *  is the runtime collision; the segment's swept span may differ from it by a curve/bezier offset. */
    private static final int FORCE_MATCH_TOL = 16;

    private static int getVerticalSeparation() {
        return RailwaysUntoldConfig.getVerticalExpansion() + 1;
    }


    /**
     * Scans the route's waypoints against placed segments and returns OVERPASS resolutions
     * for any crossings detected. Skips segments belonging to the same head (self-collisions
     * are normal during continuation).
     *
     * @param route  Newly registered route to check
     * @param level  Server level for placed-segment lookups
     * @param headId Owner of the new route - placed segments by the same head are ignored
     * @return Resolutions to apply via {@link CoarseRouteRegistry}'s normal channel
     */
    public static List<Resolution> detect(CoarseRoute route, ServerLevel level, UUID headId) {
        List<Resolution> resolutions = new ArrayList<>();
        List<CoarseWaypoint> waypoints = route.getWaypoints();
        if (waypoints.size() < 2) return resolutions;

        // A pending forced-crossing directive (set when a runtime at-grade merge into a blocking line
        // proved impossible). The crossing nearest its point is ramped clear instead of left at-grade.
        ForcedCrossingRegistry.ForcedCrossing forced = ForcedCrossingRegistry.consume(headId);

        // A pending at-grade directive (set when a crossing can be neither merged nor grade-separated): the
        // crossing nearest this point is left FLAT so the route passes straight through it, and the placement
        // collision gate lets the span cross. Peeked, not consumed - the gate reads it again at placement time.
        BlockPos atGradePoint = AtGradeCrossingRegistry.point(headId);

        // Detect against the route's slope-ACHIEVABLE Y, not the raw advised (terrain-preference) Y.
        // A route descending from a high head toward low terrain is still up near the head's Y at a
        // nearby crossing (the compiler can only drop at the slope limit), so the advised Y can claim a
        // clean under-pass the placed track will actually block. The achievable Y matches what the
        // precision compiler produces, so the runtime collision check and this detector agree.
        int[] achievableY = computeAchievableY(waypoints);

        // Track which placed-segment instances we've already resolved against, so a single
        // long perpendicular track that intersects multiple consecutive waypoint pairs
        // produces a single OVERPASS adjustment instead of stacking them.
        Set<ConnectedSegment> alreadyResolved = new HashSet<>();
        int lastCrossIdx = Integer.MIN_VALUE;

        // A branching head's route emanates from its parent track, so a crossing reported right at the
        // route origin is that junction, not a real over/under to resolve. Past the origin, a nearby track
        // is a genuine crossing - so the branch-proximity exemption is applied only near the origin.
        BlockPos routeOrigin = waypoints.get(0).position();

        for (int i = 0; i < waypoints.size() - 1; i++) {
            BlockPos segStart = atY(waypoints.get(i), achievableY[i]);
            BlockPos segEnd = atY(waypoints.get(i + 1), achievableY[i + 1]);

            Direction segDir = inferSegmentDirection(segStart, segEnd);
            if (segDir == null) continue;

            boolean nearOrigin = Math.abs(segStart.getX() - routeOrigin.getX())
                    + Math.abs(segStart.getZ() - routeOrigin.getZ()) < ORIGIN_BRANCH_DISTANCE;

            // Mark the segment carrying a forced collision so its crossing ramps over/under instead of
            // merging at-grade. The branch-junction exemption still applies: a collinear track at the
            // head's own origin is its emanation point (the parent/anchor it expands from), not a crossing
            // to ramp. A real crossing is off the head's axis, so the exemption never hides it anyway.
            boolean forceThisSeg = forced != null && segmentNearForcedPoint(segStart, segEnd, forced.point());

            for (ConnectedSegment placed : collectCandidates(level, segStart, segEnd)) {
                if (headId.equals(placed.headId)) continue;
                if (alreadyResolved.contains(placed)) continue;

                CrossingDetectionService.CrossingInfo info = CrossingDetectionService.checkSegmentIntersection(
                        segStart, segEnd, segDir, placed, nearOrigin);
                if (info == null) continue;

                // At-grade pass-through: this crossing is permitted to cross flat, so emit no ramp
                // resolution and don't reconsider the segment. The route stays at its achievable Y over
                // the line and the placement gate ({@link AtGradeCrossingRegistry}) lets it through.
                if (atGradePoint != null && segmentNearForcedPoint(segStart, segEnd, atGradePoint)) {
                    alreadyResolved.add(placed);
                    continue;
                }

                int crossIdx = nearestWaypointIndex(waypoints, info.crossingPoint());
                if (crossIdx < 0) continue;
                // Spacing filter - only once a PREVIOUS crossing exists. lastCrossIdx starts at MIN_VALUE and
                // `crossIdx - MIN_VALUE` overflows to a large negative, which is < the threshold, so this
                // guard is needed or the first crossing on each route is skipped and the placed-track
                // resolver never resolves it.
                if (lastCrossIdx != Integer.MIN_VALUE
                        && crossIdx - lastCrossIdx < MIN_CROSSING_SPACING_WAYPOINTS) continue;

                // Resolve against the placed track's LOCAL Y at the crossing point (the same value the
                // runtime collision check uses, CrossingInfo.existingTrackY) - NOT the whole-cluster AABB.
                // A long line that climbs/descends across its run has a huge AABB Y-range; judging clearance
                // against that range demands clearing the cluster's far-away extreme (e.g. lift OVER y103
                // when the rail is locally at y81), which never fits the room before the crossing, so the
                // resolver falls back to an at-grade merge - fatal for a perpendicular line the head
                // can't tangent-join. The local rail Y needs only a small grade-separation the head can ramp
                // to with the room it has, and it agrees with the runtime check so planning and placement
                // no longer disagree about whether the crossing is clear.
                int localTrackY = info.existingTrackY();
                Resolution resolution = buildCrossingResolution(
                        waypoints, achievableY, crossIdx, localTrackY, localTrackY,
                        forceThisSeg, forceThisSeg && forced.preferUnder());
                if (resolution != null) {
                    resolutions.add(resolution);
                    alreadyResolved.add(placed);
                    lastCrossIdx = crossIdx;
                }
            }
        }

        return resolutions;
    }

    private static BlockPos atY(CoarseWaypoint wp, int y) {
        return new BlockPos(wp.position().getX(), y, wp.position().getZ());
    }

    /**
     * Forward pass returning the Y the route can actually reach at each waypoint, clamping the advised
     * Y to the slope limit relative to the previous waypoint (starting from the head's Y). Approximates
     * what the precision compiler produces closely enough for crossing detection.
     */
    static int[] computeAchievableY(List<CoarseWaypoint> waypoints) {
        double maxSlope = RailwaysUntoldConfig.getMaxSlopeRatio();
        int[] y = new int[waypoints.size()];
        y[0] = waypoints.get(0).advisedTrackY();
        for (int i = 1; i < waypoints.size(); i++) {
            BlockPos prev = waypoints.get(i - 1).position();
            BlockPos cur = waypoints.get(i).position();
            int run = Math.abs(cur.getX() - prev.getX()) + Math.abs(cur.getZ() - prev.getZ());
            int maxDelta = maxSlope > 0 ? (int) Math.floor(maxSlope * run) : Integer.MAX_VALUE;
            int target = waypoints.get(i).advisedTrackY();
            y[i] = Math.max(y[i - 1] - maxDelta, Math.min(y[i - 1] + maxDelta, target));
        }
        return y;
    }

    /** Cardinal direction from start to end, or null if mixed/zero. */
    private static Direction inferSegmentDirection(BlockPos start, BlockPos end) {
        int dx = end.getX() - start.getX();
        int dz = end.getZ() - start.getZ();
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        if (Math.abs(dz) > 0) {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return null;
    }

    /** Gathers placed segments that could plausibly intersect the given segment. The segment is expanded by
     *  a buffer before collecting chunks, so a track in a neighbouring chunk - which the compiled bezier
     *  reaches even when the straight coarse segment stops short of that chunk - is still considered. */
    private static List<ConnectedSegment> collectCandidates(ServerLevel level, BlockPos start, BlockPos end) {
        List<ConnectedSegment> candidates = new ArrayList<>();
        BlockPos boxMin = new BlockPos(
                Math.min(start.getX(), end.getX()) - CANDIDATE_CHUNK_BUFFER, 0,
                Math.min(start.getZ(), end.getZ()) - CANDIDATE_CHUNK_BUFFER);
        BlockPos boxMax = new BlockPos(
                Math.max(start.getX(), end.getX()) + CANDIDATE_CHUNK_BUFFER, 0,
                Math.max(start.getZ(), end.getZ()) + CANDIDATE_CHUNK_BUFFER);
        Set<ChunkPos> chunks = ConnectedBoundaryTracker.getChunksSpannedBySegment(boxMin, boxMax);
        Set<ConnectedSegment> seen = new HashSet<>();
        for (ChunkPos chunk : chunks) {
            for (ConnectedSegment seg : ConnectedBoundaryTracker.getSegmentsInChunk(level, chunk)) {
                if (seen.add(seg)) {
                    candidates.add(seg);
                }
            }
        }
        return candidates;
    }

    /** Index of the route waypoint horizontally closest to the given point. */
    private static int nearestWaypointIndex(List<CoarseWaypoint> waypoints, BlockPos pos) {
        int best = -1;
        long bestDistSq = Long.MAX_VALUE;
        for (int i = 0; i < waypoints.size(); i++) {
            BlockPos wpPos = waypoints.get(i).position();
            long dx = wpPos.getX() - pos.getX();
            long dz = wpPos.getZ() - pos.getZ();
            long distSq = dx * dx + dz * dz;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = i;
            }
        }
        return best;
    }

    /**
     * Builds a crossing resolution, judging clearance against the route's slope-achievable Y at the
     * crossing and the placed track's LOCAL rail Y there (passed as a degenerate {@code trackBottomY ==
     * trackTopY} band) - the same value the runtime collision check compares against, so planning and
     * placement agree. If the route is already clear above or below by {@code vertSep}, nothing is adjusted. When
     * the route would pass through the track's clearance band it is lifted OVER: a route caught here is
     * descending from above and cannot dive under within the slope limit, so an overpass is the
     * reachable resolution. The crossing waypoint is pinned to {@code trackTop + vertSep + 1} and the
     * surrounding waypoints ramp to it over a slope-derived span.
     */
    static Resolution buildCrossingResolution(List<CoarseWaypoint> waypoints, int[] achievableY,
                                              int crossIdx, int trackBottomY, int trackTopY,
                                              boolean force, boolean preferUnder) {
        CoarseWaypoint crossWp = waypoints.get(crossIdx);
        int currentY = achievableY[crossIdx];
        int vertSep = getVerticalSeparation();

        // Strict: the runtime AABB overlap is inclusive (a gap of exactly vertSep still collides), so the
        // route is only "clear" when it exceeds the separation.
        if (currentY - trackTopY > vertSep) {
            return new Resolution(ConflictType.CROSSING, CrossingResolution.OVERPASS, List.of(), crossWp.position());
        }
        if (trackBottomY - currentY > vertSep) {
            return new Resolution(ConflictType.CROSSING, CrossingResolution.UNDERPASS, List.of(), crossWp.position());
        }

        int runBefore = runToCrossing(waypoints, crossIdx);
        double maxSlope = RailwaysUntoldConfig.getMaxSlopeRatio();
        int rampRoom = maxSlope > 0 ? (int) Math.floor(maxSlope * runBefore) : Integer.MAX_VALUE;

        if (force) {
            // The at-grade merge into the blocking line proved impossible (a diagonal meeting that no
            // tangent junction can form), so ramp clear instead. Prefer UNDER, fall back to OVER; either
            // returns null if its drop/climb can't fit the run before the crossing at the slope limit.
            Resolution under = rampUnder(waypoints, crossIdx, trackBottomY, vertSep, currentY, rampRoom, crossWp);
            Resolution over = rampOver(waypoints, crossIdx, trackTopY, vertSep, currentY, rampRoom, crossWp);
            return preferUnder ? (under != null ? under : over) : (over != null ? over : under);
        }

        // Collision-shape decision (matches the desired over/under-or-merge tree). At-grade (the route
        // sits within the track's vertical extent ±NEAR_GRADE) -> no ramp; the head merges into the line at
        // the crossing. Vertically separated within the clearance band -> ramp OVER or UNDER, but only if
        // the climb/drop fits the run available BEFORE the crossing at the slope limit; if it doesn't fit,
        // return null so the route stays straight and merges at the collision instead of placing an
        // illegal sub-slope ramp.
        if (currentY <= trackTopY + NEAR_GRADE && currentY >= trackBottomY - NEAR_GRADE) {
            // At-grade. A head whose route continues well past the crossing has its destination on the far
            // side, so merging here would strand it short of its target (it dead-ends into the line). When
            // there is room both before the crossing (to climb) and after it (to return to grade), ramp
            // OVER (else UNDER) so the head crosses and continues. Only merge when the crossing is at or
            // near the route's end - there the line IS the head's destination - or no ramp fits.
            // The after-room must fit the DESCENT back to grade (~vertSep at the slope limit), NOT rampRoom
            // (which is the CLIMB capacity BEFORE the crossing - often huge, wrongly merging crossings that
            // sit a modest distance from the route's end but still have room to come back down).
            int runAfter = runFromCrossing(waypoints, crossIdx);
            boolean roomAfter = maxSlope <= 0 || maxSlope * runAfter > vertSep;
            if (roomAfter) {
                Resolution over = rampOver(waypoints, crossIdx, trackTopY, vertSep, currentY, rampRoom, crossWp);
                if (over != null) return over;
                Resolution under = rampUnder(waypoints, crossIdx, trackBottomY, vertSep, currentY, rampRoom, crossWp);
                if (under != null) return under;
            }
            return null; // at-grade at/near route end, or no ramp room -> merge
        }

        // Route above the track lifts OVER; below it drops UNDER. Each returns null when its ramp can't
        // fit, leaving the route straight to merge at the collision rather than placing a sub-slope ramp.
        return currentY > trackTopY
                ? rampOver(waypoints, crossIdx, trackTopY, vertSep, currentY, rampRoom, crossWp)
                : rampUnder(waypoints, crossIdx, trackBottomY, vertSep, currentY, rampRoom, crossWp);
    }

    /** OVERPASS resolution lifting the crossing to {@code trackTopY + vertSep + 1}, or null if the climb
     *  doesn't fit {@code rampRoom} (the slope-limited run before the crossing). */
    private static Resolution rampOver(List<CoarseWaypoint> waypoints, int crossIdx, int trackTopY,
                                       int vertSep, int currentY, int rampRoom, CoarseWaypoint crossWp) {
        int targetY = trackTopY + vertSep + 1;
        if (targetY - currentY > rampRoom) return null;
        List<WaypointAdjustment> adj = new ArrayList<>();
        CrossingRamp.addAdjustments(adj, waypoints, crossIdx, targetY, WaypointType.CROSSING_OVER, currentY);
        return new Resolution(ConflictType.CROSSING, CrossingResolution.OVERPASS, adj, crossWp.position());
    }

    /** UNDERPASS resolution dropping the crossing to {@code trackBottomY - vertSep - 1}, or null if the
     *  drop doesn't fit {@code rampRoom} (the slope-limited run before the crossing). */
    private static Resolution rampUnder(List<CoarseWaypoint> waypoints, int crossIdx, int trackBottomY,
                                        int vertSep, int currentY, int rampRoom, CoarseWaypoint crossWp) {
        int targetY = trackBottomY - vertSep - 1;
        if (currentY - targetY > rampRoom) return null;
        List<WaypointAdjustment> adj = new ArrayList<>();
        CrossingRamp.addAdjustments(adj, waypoints, crossIdx, targetY, WaypointType.CROSSING_UNDER, currentY);
        return new Resolution(ConflictType.CROSSING, CrossingResolution.UNDERPASS, adj, crossWp.position());
    }

    /** True if {@code point} lies within {@link #FORCE_MATCH_TOL} of the segment's XZ bounding interval. */
    private static boolean segmentNearForcedPoint(BlockPos start, BlockPos end, BlockPos point) {
        int minX = Math.min(start.getX(), end.getX()) - FORCE_MATCH_TOL;
        int maxX = Math.max(start.getX(), end.getX()) + FORCE_MATCH_TOL;
        int minZ = Math.min(start.getZ(), end.getZ()) - FORCE_MATCH_TOL;
        int maxZ = Math.max(start.getZ(), end.getZ()) + FORCE_MATCH_TOL;
        return point.getX() >= minX && point.getX() <= maxX && point.getZ() >= minZ && point.getZ() <= maxZ;
    }

    /** Horizontal blocks the route runs from its start (head tip) to the crossing waypoint - the room
     *  available to ramp over/under before reaching the track. */
    private static int runToCrossing(List<CoarseWaypoint> waypoints, int crossIdx) {
        int run = 0;
        for (int i = 1; i <= crossIdx && i < waypoints.size(); i++) {
            BlockPos a = waypoints.get(i - 1).position();
            BlockPos b = waypoints.get(i).position();
            run += Math.abs(b.getX() - a.getX()) + Math.abs(b.getZ() - a.getZ());
        }
        return run;
    }

    /** Horizontal blocks the route runs from the crossing waypoint to its end - the room to ramp back to
     *  grade after crossing over/under. A crossing with little run beyond it is effectively the route's
     *  destination (merge), not a mid-route crossing to be lifted over. */
    private static int runFromCrossing(List<CoarseWaypoint> waypoints, int crossIdx) {
        int run = 0;
        for (int i = crossIdx + 1; i < waypoints.size(); i++) {
            BlockPos a = waypoints.get(i - 1).position();
            BlockPos b = waypoints.get(i).position();
            run += Math.abs(b.getX() - a.getX()) + Math.abs(b.getZ() - a.getZ());
        }
        return run;
    }
}
