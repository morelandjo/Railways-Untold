package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.worldgen.planner.CurveSegment;
import com.vodmordia.railwaysuntold.worldgen.planner.PathSegment;
import com.vodmordia.railwaysuntold.worldgen.planner.PlannedPath;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.CoarseWaypoint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts coarse route waypoints directly into executable PathSegments.
 */
public class PrecisionRouteCompiler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int MAX_TOTAL_SEGMENTS = 500;

    /**
     * Compiles coarse route waypoints directly into an executable PlannedPath.
     *
     * @param isFromTrackTip True when routeStart is the tip of already-placed track
     *                       (as opposed to a fresh head start). Recorded in the
     *                       [COMPILE] replay record. The first emitted segment is
     *                       always built at the head pose before any advance, so it
     *                       connects tangentially to the tip even when it is a curve -
     *                       a head that must turn off its tip can only do so with a
     *                       minimum-radius curve as its first segment.
     */
    public static PlannedPath compile(List<CoarseWaypoint> waypoints,
                                       BlockPos routeStart, BlockPos routeEnd,
                                       Direction headDirection,
                                       boolean isFromTrackTip) {
        if (waypoints.size() < 2) {
            return PlannedPath.invalid("Too few waypoints");
        }

        // Start from the head's actual current direction - the first waypoint run
        // may go in a different direction, requiring a curve insertion.
        CompileState state = new CompileState(routeStart, headDirection);
        List<PathSegment> allSegments = new ArrayList<>();

        // Group consecutive waypoints by type, then split at direction changes, then re-merge any
        // collinear diagonal fragments the type/direction passes split apart (e.g. a sustained diagonal
        // drift broken by a tunnel span) so the diagonal chain realizes the full lateral shift.
        List<WaypointRun> runs = RunSegmenter.coalesceCollinearDiagonalRuns(
                RunSegmenter.splitRunsAtDirectionChanges(RunSegmenter.groupIntoRuns(waypoints)));

        for (int ri = 0; ri < runs.size(); ri++) {
            WaypointRun run = runs.get(ri);
            if (run.waypoints().size() < 1) continue;

            // The diagonal entry+exit chain consumes ~22 forward and ~10 perpendicular
            // blocks: a +12 forward bias built into the geometry. Mid-route, residuals
            // get absorbed by the next run's cardinal section. On the last run there's
            // nowhere for residuals to go - closeResidualGap rejects perpendicular gaps
            // and the diagonal exit dangles in mid-air. When the last run's geometry
            // can't absorb that built-in bias, route through processStandardRun instead
            // (cardinal + SCurve45 lateral correction); the result is less aesthetic
            // but lands cleanly on the route target.
            boolean isLastRun = (ri == runs.size() - 1);
            boolean useDiagonal = DirectionAnalyzer.isDiagonalRun(run)
                    && (!isLastRun || DiagonalChainCompiler.diagonalChainFitsLastRun(run, state.dir));
            if (useDiagonal) {
                DiagonalChainCompiler.processDiagonalRun(run, ri, runs, state, allSegments);
            } else {
                processStandardRun(run, state, allSegments);
            }

            if (allSegments.size() >= MAX_TOTAL_SEGMENTS) {
                break;
            }
        }

        if (allSegments.isEmpty()) {
            return PlannedPath.invalid("No segments produced");
        }

        // Reconcile the tail to routeEnd: trim any overshoot (diagonal exit curves can run past the
        // last coarse waypoint), close a short/lateral residual, and snap the final segment to the
        // exact target Y (a too-conservative slope clamp would otherwise land short and derail).
        ClosureHelper.reconcileToTarget(allSegments, routeEnd, state.dir);

        PlannedPath compiled = finalizeAndLog(allSegments);
        emitCompileRecord(waypoints, routeStart, routeEnd, headDirection, isFromTrackTip, compiled);
        return compiled;
    }

    /**
     * Emits a machine-parseable [COMPILE] record - the compiler's exact inputs - plus a one-line
     * summary, so a track-geometry bug seen in-game (a disconnected seam, a sub-minimum-radius curve)
     * can be replayed through {@link #compile} in a unit test without a live server. Gated on verbose
     * logging; the record body is unprefixed so a pasted log block extracts cleanly.
     */
    private static void emitCompileRecord(List<CoarseWaypoint> waypoints, BlockPos routeStart,
                                          BlockPos routeEnd, Direction headDirection,
                                          boolean isFromTrackTip, PlannedPath compiled) {
        if (!RailwaysUntoldConfig.isVerboseLoggingEnabled()) {
            return;
        }
        CompileRecord rec = new CompileRecord(routeStart, routeEnd, headDirection, isFromTrackTip, waypoints);
        int segs = (compiled.segments == null) ? 0 : compiled.segments.size();
        LOGGER.info("[COMPILE] start {} target {} dir {} fromTip {} waypoints {} -> segments {} valid {}\n{}",
                routeStart.toShortString(), routeEnd.toShortString(), headDirection, isFromTrackTip,
                waypoints.size(), segs, compiled.valid, rec.serialize());
    }

    /**
     * Handles a non-diagonal run: an optional 90° direction-change curve plus
     * the run-type-specific segments (bridges, tunnels, or terrain beziers).
     */
    private static void processStandardRun(WaypointRun run, CompileState state, List<PathSegment> allSegments) {
        Direction runDir = DirectionAnalyzer.getRunDirection(run, state.dir);

        // Insert curve if direction changed 90 degrees
        if (runDir != state.dir && runDir != state.dir.getOpposite()) {
            boolean turnLeft = (runDir == state.dir.getCounterClockWise());
            // Always use the minimum curve radius. A 90° curve needs that many blocks of forward
            // run; on a shorter run the curve overshoots the run's last waypoint, which the turn
            // alignment and post-curve waypoint skip below absorb. The radius must never be reduced
            // to fit a short run - a curve tighter than the minimum is not traversable by Create
            // trains and breaks train movement.
            int radius = RailwaysUntoldConfig.getMinCurveRadius();

            // Turn alignment: position the curve so its endpoint lands on the
            // new run's lateral corridor, eliminating the mechanical post-curve
            // overshoot that otherwise forces an SCurve45 correction shortly after.
            int targetLateral = CurveAlignmentPlanner.targetLateralForRun(run, runDir);
            BlockPos idealStart = CurveAlignmentPlanner.idealTurnStart(state.pos, state.dir, runDir, radius, targetLateral);
            int forwardDelta = CurveAlignmentPlanner.forwardOvershoot(state.pos, idealStart, state.dir);
            String alignAction;
            boolean aligned;
            if (forwardDelta == 0) {
                alignAction = "noop";
                aligned = true;
            } else if (forwardDelta > 0) {
                aligned = CurveAlignmentPlanner.tryTrimTail(allSegments, state.dir, forwardDelta);
                alignAction = aligned ? "trim" : "trim-failed";
                if (aligned) {
                    BlockPos newPos = allSegments.isEmpty()
                            ? state.pos.relative(state.dir, -forwardDelta)
                            : allSegments.get(allSegments.size() - 1).getEndPosition();
                    state.advanceTo(newPos, state.dir);
                }
            } else {
                aligned = CurveAlignmentPlanner.tryExtendStraight(allSegments, state.pos, idealStart, state.dir);
                alignAction = aligned ? "extend" : "extend-failed";
                if (aligned) {
                    state.advanceTo(idealStart, state.dir);
                }
            }

            CurveSegment curve = new CurveSegment(state.pos, state.dir, radius, turnLeft, 0);
            allSegments.add(curve);
            state.advanceTo(curve.getEndPosition(), curve.getEndDirection());

            // After a 90° curve, skip waypoints that the curve already covered.
            // The curve moves the head 'radius' blocks in the new direction, so
            // early waypoints are now behind the curve endpoint. Without this skip,
            // createBridgeSegments sees large lateral drift (the curve moved the head
            // perpendicular to the waypoints' line) and inserts SCurve45 corrections.
            int skipCount = 0;
            for (int w = 0; w < run.waypoints().size(); w++) {
                int fwd = RouteMath.horizontalDistance(state.pos, run.waypoints().get(w).position(), state.dir);
                if (fwd > 0) break;
                skipCount = w + 1;
            }
            if (skipCount > 0 && skipCount < run.waypoints().size()) {
                run = new WaypointRun(run.type(), List.copyOf(run.waypoints().subList(skipCount, run.waypoints().size())));
            }

            // Snap remaining waypoints' perpendicular coordinate to the curve
            // endpoint's lateral. When alignment succeeded this is a near-no-op
            // (curve end already matches targetLateral); when alignment failed
            // this is the fallback that absorbs the drift into the current run.
            run = CurveAlignmentPlanner.snapRunLateralToAxis(run, state.pos, runDir);
        }

        // Create segments based on run type. Bridges are cardinal-straight with
        // bezier laterals; tunnels split the same way because Create's connector
        // curves through non-cardinal endpoints; terrain runs use beziers.
        List<PathSegment> segs = switch (run.type()) {
            case BRIDGE, CROSSING_OVER -> SegmentEmitter.createSegments(
                    run.waypoints(), state.pos, state.dir, SegmentEmitter.SegmentKind.BRIDGE);
            case TUNNEL, CROSSING_UNDER -> SegmentEmitter.createSegments(
                    run.waypoints(), state.pos, state.dir, SegmentEmitter.SegmentKind.TUNNEL);
            case TERRAIN_FOLLOW, FLAT_MAINTAIN, CURVE_AROUND -> SegmentEmitter.createSegments(
                    run.waypoints(), state.pos, state.dir, SegmentEmitter.SegmentKind.TERRAIN);
        };
        allSegments.addAll(segs);
        if (!segs.isEmpty()) {
            PathSegment last = segs.get(segs.size() - 1);
            state.advanceTo(last.getEndPosition(), last.getEndDirection());
        }
    }


    /**
     * Emits the per-segment dump and returns the success PlannedPath.
     */
    private static PlannedPath finalizeAndLog(List<PathSegment> allSegments) {
        PathSegment lastSeg = allSegments.get(allSegments.size() - 1);
        BlockPos finalPos = lastSeg.getEndPosition();
        Direction finalDir = lastSeg.getEndDirection();

        // Continuity capture: a valid route's segments connect end-to-start (gap <= 1). A larger gap is
        // the drift/loop root - the executor places segment N and lands at its end, but segment N+1
        // starts elsewhere, so the BEZIER drift guard halts and the head replans/loops. When it happens
        // capture the full segment list so the offending route is reproducible at the source (this fires
        // here only if the COMPILER produced the discontinuity; if it never fires while the head still
        // drifts, the gap is introduced downstream in decision derivation). Detection only.
        for (int i = 1; i < allSegments.size(); i++) {
            BlockPos prevEnd = allSegments.get(i - 1).getEndPosition();
            BlockPos segStart = allSegments.get(i).getStart();
            int gap = Math.abs(prevEnd.getX() - segStart.getX())
                    + Math.abs(prevEnd.getY() - segStart.getY())
                    + Math.abs(prevEnd.getZ() - segStart.getZ());
            if (gap > 1) {
                LOGGER.warn("[SEGMENT-DISCONTINUITY] segment {} ({}) ends {} but segment {} ({}) starts {} (gap {}) - drift/loop root. segments: {}",
                        i - 1, allSegments.get(i - 1).getClass().getSimpleName(), prevEnd,
                        i, allSegments.get(i).getClass().getSimpleName(), segStart, gap,
                        segmentsDump(allSegments));
                break;
            }
        }

        // Reject a route with an illegal seam: a >=90° kink between consecutive segments that no curve
        // absorbs is track a train cannot traverse, so it must not be placed. The segments are retained
        // (invalidWithSegments) so a replayed capture stays inspectable. Both the off-thread plan and the
        // authoritative on-thread recompile route through here, so this guards placement on both paths.
        int seam = RouteSeamInspector.findIllegalSeam(allSegments);
        if (seam >= 0) {
            LOGGER.warn("[PRECISION-ROUTE] Rejecting route: illegal seam at junction {} ({} -> {})",
                    seam, allSegments.get(seam).getEndPosition(), allSegments.get(seam + 1).getStart());
            return PlannedPath.invalidWithSegments(PlannedPath.InvalidReason.ILLEGAL_SEAM,
                    "illegal seam at junction " + seam, allSegments);
        }

        return PlannedPath.success(allSegments, finalPos, finalDir);
    }

    /** Compact "idx:TYPE sx,sy,sz->ex,ey,ez" dump of every segment, for the discontinuity capture. */
    private static String segmentsDump(List<PathSegment> segments) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            PathSegment s = segments.get(i);
            if (i > 0) sb.append("; ");
            BlockPos st = s.getStart();
            BlockPos en = s.getEndPosition();
            sb.append(i).append(':').append(s.getClass().getSimpleName()).append(' ')
                    .append(st.getX()).append(',').append(st.getY()).append(',').append(st.getZ())
                    .append("->")
                    .append(en.getX()).append(',').append(en.getY()).append(',').append(en.getZ());
        }
        return sb.toString();
    }

    /** Mutable compile-time state threaded through each run's processing. */
    static final class CompileState {
        BlockPos pos;
        Direction dir;

        CompileState(BlockPos pos, Direction dir) {
            this.pos = pos;
            this.dir = dir;
        }

        void advanceTo(BlockPos newPos, Direction newDir) {
            this.pos = newPos;
            this.dir = newDir;
        }
    }


}
