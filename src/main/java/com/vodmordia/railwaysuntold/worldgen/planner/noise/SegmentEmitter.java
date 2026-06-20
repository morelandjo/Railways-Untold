package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.placement.SCurve45Geometry;
import com.vodmordia.railwaysuntold.worldgen.planner.*;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.CoarseWaypoint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Emits the cardinal-straight segments for a run via tolerance-based path simplification,
 * inserting SCurve45 corrections when lateral drift exceeds the configured offset. The run
 * type only changes which segment subtype is emitted at each breakpoint - terrain runs emit
 * beziers, bridge runs emit bridge decking, tunnel runs emit tunnels (elevated when the span
 * changes Y) - so the three share one body parameterized by {@link SegmentKind}.
 */
final class SegmentEmitter {

    /** Which concrete segment subtype a run emits. */
    enum SegmentKind { TERRAIN, BRIDGE, TUNNEL }

    /**
     * Max length (blocks) of a single emitted segment. Lower means shorter segments: each placement
     * touches fewer chunks (smaller per-tick spike, smoother generation) and the chunk-hold release
     * distance can stay tight. A long run becomes several collinear sub-segments, which join
     * seamlessly. Applies to every kind (terrain/bridge/tunnel), so a long bridge is chopped too.
     * Must stay at 64 or above: lower values break parallel-converge (the head merges at its start
     * offset instead of driving laterally into the line first), and 64 already fits the chunk-hold
     * release distance (<=4 chunks vs release 6), so there's no reason to go tighter.
     */
    private static final int MAX_SEGMENT_LENGTH = 64;
    /** Max deviation of intermediate advisedTrackY from the linear Y interpolation.
     *  Higher values produce fewer, longer segments (smoother slopes). */
    private static final int Y_TOLERANCE = 8;
    /** Max lateral/forward ratio for gentle sweeping beziers (below this, laterals are allowed). */
    private static final double MAX_GENTLE_LATERAL_RATIO = 1.0 / 6.0;

    private SegmentEmitter() {}

    static List<PathSegment> createSegments(
            List<CoarseWaypoint> waypoints, BlockPos startPos, Direction dir, SegmentKind kind) {

        List<PathSegment> segments = new ArrayList<>();
        if (waypoints.isEmpty()) return segments;

        // A single-waypoint run has no second breakpoint for the simplify loop below to target, so it
        // would emit nothing and the waypoint's Y would be silently dropped - the route would pass
        // through at the previous run's Y. That is exactly a lone CROSSING_OVER/CROSSING_UNDER apex
        // (CrossingRamp marks only the apex as the crossing type; its ramp neighbours stay
        // TERRAIN_FOLLOW), so dropping it laid the route at the climb run's Y and the over/under
        // clearance was lost at the actual crossing. Emit the one segment from the entry to it.
        if (waypoints.size() == 1) {
            emitSingleWaypointSegment(segments, waypoints.get(0), startPos, dir, kind);
            return segments;
        }

        List<Integer> breakpoints = simplifyRun(waypoints);

        BlockPos currentPos = startPos;
        for (int b = 0; b < breakpoints.size() - 1; b++) {
            int endIdx = breakpoints.get(b + 1);
            CoarseWaypoint endWp = waypoints.get(endIdx);
            int forwardDist = RouteMath.horizontalDistance(currentPos, endWp.position(), dir);
            if (forwardDist < 1) continue;

            int segEndY = RouteMath.clampEndYToSlopeLimit(currentPos.getY(), endWp.advisedTrackY(), forwardDist);

            BlockPos segEndPos = new BlockPos(endWp.position().getX(), segEndY, endWp.position().getZ());
            int lateral = RouteMath.lateralOffset(currentPos, segEndPos, dir);

            if (Math.abs(lateral) > RailwaysUntoldConfig.getLateralOffset()) {
                // Lateral drift exceeds correction threshold - insert SCurve45 to realign.
                // Look ahead through subsequent breakpoints to find enough forward distance.
                if (tryPlaceSCurveCorrection(segments, breakpoints, waypoints, currentPos, dir,
                        b, lateral)) {
                    // Find which breakpoint the SCurve reached
                    BlockPos lastPos = segments.get(segments.size() - 1).getEndPosition();
                    // Advance b to the last merged breakpoint
                    for (int scan = b; scan < breakpoints.size() - 1; scan++) {
                        CoarseWaypoint scanWp = waypoints.get(breakpoints.get(scan + 1));
                        if (RouteMath.horizontalDistance(lastPos, scanWp.position(), dir) <= 0) {
                            b = scan;
                        } else {
                            break;
                        }
                    }
                    currentPos = lastPos;
                    continue;
                }
            }

            // Gentle lateral ratio? Follow the coarse waypoint naturally; otherwise go
            // straight along the cardinal axis. Both yield the same end position per run type.
            BlockPos end;
            if (Math.abs(lateral) <= forwardDist * MAX_GENTLE_LATERAL_RATIO) {
                end = segEndPos;
            } else {
                end = currentPos.relative(dir, forwardDist);
                end = new BlockPos(end.getX(), segEndY, end.getZ());
            }

            switch (kind) {
                case TERRAIN -> segments.add(new BezierSegment(currentPos, dir, end, dir));
                case BRIDGE -> segments.add(new BridgeSegment(currentPos, dir, end));
                case TUNNEL -> segments.add(new TunnelSegment(currentPos, dir, end));
            }
            currentPos = end;
        }

        return segments;
    }

    /**
     * Emits one segment from {@code startPos} to a lone run waypoint, slope-clamping the Y and using the
     * same gentle-lateral/cardinal-straight choice and per-kind subtype as the main loop body. Used for
     * a single-waypoint run, which the simplify loop cannot target.
     */
    private static void emitSingleWaypointSegment(List<PathSegment> segments, CoarseWaypoint wp,
                                                  BlockPos startPos, Direction dir, SegmentKind kind) {
        int forwardDist = RouteMath.horizontalDistance(startPos, wp.position(), dir);
        if (forwardDist < 1) return;

        int segEndY = RouteMath.clampEndYToSlopeLimit(startPos.getY(), wp.advisedTrackY(), forwardDist);
        BlockPos segEndPos = new BlockPos(wp.position().getX(), segEndY, wp.position().getZ());
        int lateral = RouteMath.lateralOffset(startPos, segEndPos, dir);

        BlockPos end;
        if (Math.abs(lateral) <= forwardDist * MAX_GENTLE_LATERAL_RATIO) {
            end = segEndPos;
        } else {
            end = startPos.relative(dir, forwardDist);
            end = new BlockPos(end.getX(), segEndY, end.getZ());
        }

        switch (kind) {
            case TERRAIN -> segments.add(new BezierSegment(startPos, dir, end, dir));
            case BRIDGE -> segments.add(new BridgeSegment(startPos, dir, end));
            case TUNNEL -> segments.add(new TunnelSegment(startPos, dir, end));
        }
    }

    /**
     * Attempts to place an SCurve45 correction for accumulated lateral drift.
     * Looks ahead through breakpoints to find enough forward distance for the S-curve.
     * Returns true if successful (segments list is modified), false otherwise.
     */
    private static boolean tryPlaceSCurveCorrection(
            List<PathSegment> segments, List<Integer> breakpoints,
            List<CoarseWaypoint> waypoints, BlockPos currentPos, Direction dir,
            int startB, int lateral) {

        int radius = RailwaysUntoldConfig.getMinCurveRadius();

        for (int tryB = startB; tryB < breakpoints.size() - 1; tryB++) {
            int tryEndIdx = breakpoints.get(tryB + 1);
            CoarseWaypoint tryEndWp = waypoints.get(tryEndIdx);
            BlockPos tryEndPos = new BlockPos(tryEndWp.position().getX(),
                    tryEndWp.advisedTrackY(), tryEndWp.position().getZ());

            int mergedForward = RouteMath.horizontalDistance(currentPos, tryEndWp.position(), dir);
            int mergedLateral = RouteMath.lateralOffset(currentPos, tryEndPos, dir);
            int mergedEndY = tryEndWp.advisedTrackY();

            // Stop if lateral direction reverses
            if (tryB > startB && ((mergedLateral > 0) != (lateral > 0))) break;

            int diagLen = SCurve45Geometry.calculateDiagonalLengthForLateralShift(
                    Math.abs(mergedLateral), radius);
            int forwardNeeded = SCurve45Geometry.calculateForwardDistance(radius, diagLen);

            if (forwardNeeded <= mergedForward) {
                boolean shiftLeft = mergedLateral < 0;
                // Keep SCurve flat - elevation changes create visible bumps at the
                // internal phase transitions (curve->diagonal->curve). Let the adjacent
                // straight bezier segments handle elevation smoothly instead.
                SCurve45Segment scurve = new SCurve45Segment(
                        currentPos, dir, radius, diagLen, shiftLeft, 0);
                segments.add(scurve);
                BlockPos scurveEnd = scurve.getEndPosition();

                // Bridge remaining forward distance to the merged endpoint,
                // carrying elevation change clamped to the configured slope limit.
                int remainingForward = RouteMath.horizontalDistance(scurveEnd, tryEndWp.position(), dir);
                if (remainingForward > 1 || mergedEndY != scurveEnd.getY()) {
                    int bridgeForward = Math.max(remainingForward, 1);
                    int clampedEndY = RouteMath.clampEndYToSlopeLimit(scurveEnd.getY(), mergedEndY, bridgeForward);
                    // Skip degenerate 1-block flat bridge
                    if (bridgeForward > 1 || clampedEndY != scurveEnd.getY()) {
                        BlockPos straightEnd = scurveEnd.relative(dir, bridgeForward);
                        straightEnd = new BlockPos(straightEnd.getX(), clampedEndY, straightEnd.getZ());
                        segments.add(new BezierSegment(scurveEnd, dir, straightEnd, dir));
                    }
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Path simplifier. Returns waypoint indices to use as segment breakpoints.
     * Extends each candidate segment as far as possible while keeping every skipped
     * intermediate waypoint within the configured lateral offset (X/Z perpendicular distance) and
     * Y_TOLERANCE (Y deviation from linear interpolation) of the fitted line.
     */
    private static List<Integer> simplifyRun(List<CoarseWaypoint> waypoints) {
        List<Integer> breakpoints = new ArrayList<>();
        if (waypoints.isEmpty()) return breakpoints;
        breakpoints.add(0);
        if (waypoints.size() == 1) return breakpoints;
        if (waypoints.size() == 2) { breakpoints.add(1); return breakpoints; }

        int anchor = 0;
        int candidate = 2;
        while (candidate < waypoints.size()) {
            BlockPos aPos = waypoints.get(anchor).position();
            BlockPos cPos = waypoints.get(candidate).position();
            double segLen = euclid2D(aPos, cPos);

            boolean exceedsLength = segLen > MAX_SEGMENT_LENGTH;
            boolean violates = false;

            if (!exceedsLength) {
                int aY = waypoints.get(anchor).advisedTrackY();
                int cY = waypoints.get(candidate).advisedTrackY();
                for (int k = anchor + 1; k < candidate; k++) {
                    BlockPos kPos = waypoints.get(k).position();
                    double perp = perpDistance(aPos, cPos, kPos);
                    if (perp > RailwaysUntoldConfig.getLateralOffset()) { violates = true; break; }
                    double t = segLen > 0 ? euclid2D(aPos, kPos) / segLen : 0.0;
                    double interpY = aY + t * (cY - aY);
                    double yErr = Math.abs(waypoints.get(k).advisedTrackY() - interpY);
                    if (yErr > Y_TOLERANCE) { violates = true; break; }
                }
            }

            if (exceedsLength || violates) {
                int commit = Math.max(candidate - 1, anchor + 1);
                breakpoints.add(commit);
                anchor = commit;
                candidate = anchor + 2;
            } else {
                candidate++;
            }
        }

        int last = waypoints.size() - 1;
        if (breakpoints.get(breakpoints.size() - 1) != last) {
            breakpoints.add(last);
        }
        return breakpoints;
    }

    private static double euclid2D(BlockPos a, BlockPos b) {
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Perpendicular distance from point p to the line through a and b (X/Z plane).
     * Returns Double.MAX_VALUE when a == b to force rejection of degenerate ranges.
     */
    private static double perpDistance(BlockPos a, BlockPos b, BlockPos p) {
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double denom = Math.sqrt(dx * dx + dz * dz);
        if (denom == 0) return Double.MAX_VALUE;
        double num = Math.abs(dx * (a.getZ() - p.getZ()) - (a.getX() - p.getX()) * dz);
        return num / denom;
    }
}
