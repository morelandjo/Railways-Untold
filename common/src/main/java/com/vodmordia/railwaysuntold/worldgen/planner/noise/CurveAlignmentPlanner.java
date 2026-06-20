package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.planner.BezierSegment;
import com.vodmordia.railwaysuntold.worldgen.planner.BridgeSegment;
import com.vodmordia.railwaysuntold.worldgen.planner.PathSegment;
import com.vodmordia.railwaysuntold.worldgen.planner.TunnelSegment;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.CoarseWaypoint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Plans how a run's leading 90° alignment curve lands on the run's lateral corridor:
 * computes the ideal turn-start, and trims or extends the cardinal tail so the curve's
 * endpoint matches. Operates on the running compile position/direction passed in as
 * parameters; mutates the segment list in place.
 */
final class CurveAlignmentPlanner {

    private CurveAlignmentPlanner() {}

    /**
     * Returns the median lateral coord of the run's waypoints. Lateral is x for a
     * N/S run, z for an E/W run. Median (not first/last) is robust to a single
     * drifting waypoint at the run's boundary.
     */
    static int targetLateralForRun(WaypointRun run, Direction runDir) {
        boolean lateralIsX = (runDir == Direction.NORTH || runDir == Direction.SOUTH);
        int n = run.waypoints().size();
        int[] laterals = new int[n];
        for (int i = 0; i < n; i++) {
            BlockPos p = run.waypoints().get(i).position();
            laterals[i] = lateralIsX ? p.getX() : p.getZ();
        }
        java.util.Arrays.sort(laterals);
        return laterals[n / 2];
    }

    /**
     * Computes the position the 90° curve must START from for its endpoint to land
     * on {@code targetLateral} (a coord on runDir's lateral axis = stateDir's travel axis).
     * Result differs from statePos only along stateDir; Y is preserved from statePos.
     *
     * Geometry: a 90° quarter-circle of radius r displaces the head +r along stateDir
     * and ±r along runDir. The displacement along stateDir's axis equals
     * {@code stateDir.getStepX/Z() * radius}.
     */
    static BlockPos idealTurnStart(BlockPos statePos, Direction stateDir,
                                   Direction runDir, int radius, int targetLateral) {
        boolean lateralIsX = (runDir == Direction.NORTH || runDir == Direction.SOUTH);
        int signedLateralShift = lateralIsX
                ? stateDir.getStepX() * radius
                : stateDir.getStepZ() * radius;
        int desiredStartLateral = targetLateral - signedLateralShift;
        if (lateralIsX) {
            return new BlockPos(desiredStartLateral, statePos.getY(), statePos.getZ());
        } else {
            return new BlockPos(statePos.getX(), statePos.getY(), desiredStartLateral);
        }
    }

    /**
     * Signed forward overshoot of {@code from} past {@code to} along {@code dir}.
     * Positive when {@code from} is ahead of {@code to} (overshot); negative when
     * {@code from} is behind {@code to} (undershot); zero when aligned.
     */
    static int forwardOvershoot(BlockPos from, BlockPos to, Direction dir) {
        return (from.getX() - to.getX()) * dir.getStepX()
             + (from.getZ() - to.getZ()) * dir.getStepZ();
    }

    /**
     * Returns true if {@code seg} is a cardinal-straight segment going along {@code dir}
     * (start and end direction both equal dir). Trim cascades only across these.
     */
    private static boolean isCardinalStraightAlong(PathSegment seg, Direction dir) {
        if (seg.getStartDirection() != dir || seg.getEndDirection() != dir) return false;
        return (seg instanceof BridgeSegment) || (seg.type == PathSegment.Type.TUNNEL) || (seg instanceof BezierSegment);
    }

    /** Forward span of a segment along {@code dir} (assumes seg is cardinal-straight along dir). */
    private static int forwardSpanAlong(PathSegment seg, Direction dir) {
        BlockPos s = seg.getStart();
        BlockPos e = seg.getEndPosition();
        return (e.getX() - s.getX()) * dir.getStepX() + (e.getZ() - s.getZ()) * dir.getStepZ();
    }

    /**
     * Returns a replacement segment of the same type, shortened to end at {@code newEnd}.
     * Returns null for unsupported types (caller falls back).
     */
    private static PathSegment shortenedSegment(PathSegment original, BlockPos newEnd) {
        if (original instanceof BridgeSegment) {
            return new BridgeSegment(original.getStart(), original.getStartDirection(), newEnd);
        }
        if (original.type == PathSegment.Type.TUNNEL) {
            return new TunnelSegment(original.getStart(), original.getStartDirection(), newEnd);
        }
        if (original instanceof BezierSegment) {
            return new BezierSegment(original.getStart(), original.getStartDirection(), newEnd, original.getEndDirection());
        }
        return null;
    }

    /**
     * Trims the tail of allSegments by {@code forwardDelta} blocks along stateDir
     * (cascades across multiple cardinal-straight tail segments if needed). Y on
     * any partially-trimmed segment is recomputed linearly along the original slope.
     * Returns false if the tail hits a non-cardinal-straight segment or the trim
     * would exhaust the entire segment list - caller falls back to today's behavior.
     *
     * The trim is all-or-nothing: when it cannot absorb the full delta the segment
     * list is left exactly as it was. The caller's fallback places the alignment
     * curve at the unchanged running position, so any tail segments consumed during a
     * failed attempt must be restored - otherwise the curve starts past a now-missing
     * connector and the route has a disconnected seam.
     */
    static boolean tryTrimTail(List<PathSegment> allSegments, Direction stateDir, int forwardDelta) {
        if (forwardDelta <= 0) return true;
        List<PathSegment> snapshot = new ArrayList<>(allSegments);
        int remaining = forwardDelta;
        boolean failed = false;
        while (remaining > 0 && !allSegments.isEmpty()) {
            PathSegment last = allSegments.get(allSegments.size() - 1);
            if (!isCardinalStraightAlong(last, stateDir)) { failed = true; break; }
            int segLen = forwardSpanAlong(last, stateDir);
            if (segLen <= 0) {
                allSegments.remove(allSegments.size() - 1);
                continue;
            }
            if (segLen <= remaining) {
                allSegments.remove(allSegments.size() - 1);
                remaining -= segLen;
            } else {
                int newLen = segLen - remaining;
                BlockPos newEndXZ = last.getStart().relative(stateDir, newLen);
                int origStartY = last.getStart().getY();
                int origEndY = last.getEndPosition().getY();
                int newEndY = origStartY + (int) Math.round((double) (origEndY - origStartY) * newLen / segLen);
                BlockPos newEnd = new BlockPos(newEndXZ.getX(), newEndY, newEndXZ.getZ());
                PathSegment replacement = shortenedSegment(last, newEnd);
                if (replacement == null) { failed = true; break; }
                allSegments.set(allSegments.size() - 1, replacement);
                remaining = 0;
            }
        }
        if (failed || remaining != 0) {
            allSegments.clear();
            allSegments.addAll(snapshot);
            return false;
        }
        return true;
    }

    /**
     * Appends a single flat BezierSegment from statePos to idealStart along stateDir.
     * Used when statePos is laterally short of the ideal turn point (undershoot).
     * Returns false if the requested extension isn't strictly forward along stateDir.
     */
    static boolean tryExtendStraight(List<PathSegment> allSegments, BlockPos statePos,
                                     BlockPos idealStart, Direction stateDir) {
        // Caller invokes only when overshoot < 0 (statePos behind idealStart); guard
        // defensively against direct callers passing in degenerate or reversed inputs.
        int overshoot = forwardOvershoot(statePos, idealStart, stateDir);
        if (overshoot >= 0) return false;
        BlockPos extendEnd = new BlockPos(idealStart.getX(), statePos.getY(), idealStart.getZ());
        allSegments.add(new BezierSegment(statePos, stateDir, extendEnd, stateDir));
        return true;
    }

    /**
     * Rewrites a run's waypoints with their perpendicular coordinate snapped to
     * {@code axisPos}'s lateral. For a west/east run the lateral axis is z; for
     * north/south it's x. Y and the dominant axis are preserved.
     *
     */
    static WaypointRun snapRunLateralToAxis(WaypointRun run, BlockPos axisPos, Direction runDir) {
        if (run.waypoints().isEmpty()) return run;
        boolean horizontalAxis = (runDir == Direction.EAST || runDir == Direction.WEST);
        int axisLateral = horizontalAxis ? axisPos.getZ() : axisPos.getX();

        List<CoarseWaypoint> snapped = new ArrayList<>(run.waypoints().size());
        boolean anyChanged = false;
        for (CoarseWaypoint wp : run.waypoints()) {
            BlockPos pos = wp.position();
            int currentLateral = horizontalAxis ? pos.getZ() : pos.getX();
            if (currentLateral == axisLateral) {
                snapped.add(wp);
                continue;
            }
            anyChanged = true;
            BlockPos newPos = horizontalAxis
                    ? new BlockPos(pos.getX(), pos.getY(), axisLateral)
                    : new BlockPos(axisLateral, pos.getY(), pos.getZ());
            snapped.add(new CoarseWaypoint(newPos, wp.advisedTrackY(), wp.type(), wp.yBasis()));
        }
        return anyChanged ? new WaypointRun(run.type(), snapped) : run;
    }
}
