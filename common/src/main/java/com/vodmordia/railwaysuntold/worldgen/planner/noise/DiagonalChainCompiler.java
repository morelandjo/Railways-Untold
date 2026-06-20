package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.placement.SCurve45Geometry;
import com.vodmordia.railwaysuntold.util.spatial.DiagonalDirection;
import com.vodmordia.railwaysuntold.worldgen.planner.CurveSegment;
import com.vodmordia.railwaysuntold.worldgen.planner.DiagonalCurveSegment;
import com.vodmordia.railwaysuntold.worldgen.planner.DiagonalStraightSegment;
import com.vodmordia.railwaysuntold.worldgen.planner.PathSegment;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.CoarseWaypoint;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.PrecisionRouteCompiler.CompileState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Compiles a diagonal (~45°) run into an optional 90° alignment curve, a DiagonalEntry,
 * the diagonal straights, and a DiagonalExit, skipping entry/exit where an adjacent run
 * continues the same diagonal. Mutates the shared CompileState and segment list by reference.
 */
final class DiagonalChainCompiler {

    private DiagonalChainCompiler() {}

    /**
     * Max length of one diagonal straight segment, in diagonal steps (= blocks per axis). A diagonal
     * segment's trio-pin force-loads its full N×N bounding box, so the chunk count grows with the
     * SQUARE of this - at 90 that's a ~6×6 ≈ 36-chunk box the head must wait to generate before the
     * segment places, causing a long re-attempt burst near spawn. Kept small (≈2×2-chunk box) so a
     * diagonal run becomes several collinear sub-segments that each place quickly; mirrors the
     * cardinal {@code SegmentEmitter.MAX_SEGMENT_LENGTH} cap.
     */
    private static final int MAX_DIAGONAL_SEGMENT_STEPS = 32;
    /** Horizontal blocks spanned per diagonal step - the √2 hypotenuse of a unit diagonal. */
    private static final double DIAGONAL_STEP_LENGTH = 1.414;

    /**
     * Handles a diagonal run: optional 90° alignment curve, a DiagonalEntry
     * (cardinal -> diagonal), the diagonal straights themselves, and a
     * DiagonalExit (diagonal -> cardinal). Entry/exit are skipped when the
     * previous / next run continues the same diagonal.
     */
    static void processDiagonalRun(WaypointRun run, int ri, List<WaypointRun> runs,
                                   CompileState state, List<PathSegment> allSegments) {
        var diagonal = DirectionAnalyzer.getDiagonalRunDirection(run);
        int radius = RailwaysUntoldConfig.getMinCurveRadius();

        // Chain continuity: if the previous run was a diagonal sharing the same
        // DiagonalDirection, we skipped its exit - we're already on this diagonal,
        // so skip the 90° curve alignment and the DiagonalEntry.
        boolean continuingFromPrev = (ri > 0)
                && DirectionAnalyzer.isDiagonalRun(runs.get(ri - 1))
                && DirectionAnalyzer.getDiagonalRunDirection(runs.get(ri - 1)) == diagonal;

        if (!continuingFromPrev) {
            // Insert 90° curve first if needed to align cardinal direction
            Direction neededCardinal = DirectionAnalyzer.getCardinalForDiagonalEntry(diagonal, state.dir);
            if (neededCardinal != state.dir && neededCardinal != state.dir.getOpposite()) {
                boolean turnLeft = (neededCardinal == state.dir.getCounterClockWise());
                CurveSegment curve = new CurveSegment(state.pos, state.dir, radius, turnLeft, 0);
                allSegments.add(curve);
                state.advanceTo(curve.getEndPosition(), curve.getEndDirection());
            }

            // Insert diagonal entry curve (cardinal -> diagonal)
            DiagonalCurveSegment entry = DiagonalCurveSegment.entry(state.pos, state.dir, diagonal, radius, 0);
            allSegments.add(entry);
            state.advanceTo(entry.getEndPosition(), state.dir);
        }

        // Check chain continuity early: if the next run continues the same diagonal,
        // no exit will be emitted, so no reservation is needed.
        boolean continuesToNext = (ri + 1 < runs.size())
                && DirectionAnalyzer.isDiagonalRun(runs.get(ri + 1))
                && DirectionAnalyzer.getDiagonalRunDirection(runs.get(ri + 1)) == diagonal;

        // Reserve room for the DiagonalExit curve so it lands within the run rather
        // than overshooting. The exit consumes more along the cardinal exit axis than
        // on the perpendicular - reserve each axis independently. Subtracting a single
        // reserve from min(dx,dz) over-reserves the perpendicular axis by ~6 blocks,
        // leaving the diagonal short of its last waypoint and the gap-closer rejecting
        // the perpendicular residual.
        int exitForwardReserve = continuesToNext ? 0 : curveForwardFootprint(radius);
        int exitPerpReserve = continuesToNext ? 0 : curvePerpFootprint(radius);

        List<PathSegment> diagSegs = createDiagonalSegments(
                run.waypoints(), state.pos, state.dir, diagonal,
                exitForwardReserve, exitPerpReserve);
        allSegments.addAll(diagSegs);
        if (!diagSegs.isEmpty()) {
            state.advanceTo(diagSegs.get(diagSegs.size() - 1).getEndPosition(), state.dir);
        }

        if (!continuesToNext) {
            // Determine exit cardinal direction from next run
            Direction exitDir = state.dir;
            if (ri + 1 < runs.size()) {
                exitDir = DirectionAnalyzer.getRunDirection(runs.get(ri + 1), state.dir);
            }

            // A diagonal only connects to its two spanning cardinals (SE spans
            // EAST and SOUTH, etc.). If the next run's direction isn't one of
            // those, clamp to the compatible cardinal nearest to it - the next
            // run's processStandardRun will then insert a normal 90° curve to
            // reach the actually-desired heading. Emitting a DiagonalExit with
            // an incompatible cardinal asks Create's bezier connector to pack a
            // 135° turn into a 45° arc, which renders as a Y / hook.
            if (!diagonal.supportsCardinal(exitDir)) {
                // Clamp to the nearest spanning cardinal; the next run inserts a 90° redirect. Routine.
                exitDir = diagonal.nearestCardinal(exitDir);
            }

            DiagonalCurveSegment exit = DiagonalCurveSegment.exit(state.pos, state.dir, diagonal, exitDir, radius, 0);
            allSegments.add(exit);
            state.advanceTo(exit.getEndPosition(), exit.getEndDirection());
        }
    }

    /**
     * Creates diagonal straight segments for a run traveling at 45 degrees.
     * Segments are measured in diagonal steps (1 block X + 1 block Z per step).
     *
     * @param reserveForwardSteps Blocks reserved on the cardinal-exit (forward) axis
     *                            for a trailing DiagonalExit curve. Pass 0 when no exit
     *                            follows. Typical: ceil(R*sin45).
     * @param reservePerpSteps    Blocks reserved on the perpendicular axis for the same
     *                            exit curve. The exit's perpendicular travel is smaller
     *                            than its forward travel, so this is typically less than
     *                            reserveForwardSteps. Pass 0 when no exit follows.
     *                            Typical: ceil(R*(1-cos45)).
     */
    private static List<PathSegment> createDiagonalSegments(
            List<CoarseWaypoint> waypoints, BlockPos startPos, Direction cardinalDir,
            DiagonalDirection diagonal,
            int reserveForwardSteps, int reservePerpSteps) {

        List<PathSegment> segments = new ArrayList<>();
        if (waypoints.size() < 2) return segments;

        BlockPos currentPos = startPos;
        BlockPos lastWpPos = waypoints.get(waypoints.size() - 1).position();
        int lastWpY = waypoints.get(waypoints.size() - 1).advisedTrackY();

        int totalDiagSteps = diagonalStepCount(
                currentPos, lastWpPos, cardinalDir, reserveForwardSteps, reservePerpSteps);

        if (totalDiagSteps < 2) return segments;

        // Split into segments respecting max length
        int stepsRemaining = totalDiagSteps;
        int totalElev = lastWpY - currentPos.getY();

        while (stepsRemaining > 0) {
            int segLength = Math.min(stepsRemaining, MAX_DIAGONAL_SEGMENT_STEPS);
            int segElev = (totalDiagSteps > 0)
                    ? (int) Math.round((double) totalElev * segLength / totalDiagSteps)
                    : 0;

            // Clamp elevation to slope limit (diagonal covers sqrt(2) * segLength horizontal)
            segElev = RouteMath.clampEndYToSlopeLimit(currentPos.getY(),
                    currentPos.getY() + segElev, (int)(segLength * DIAGONAL_STEP_LENGTH)) - currentPos.getY();

            segments.add(new DiagonalStraightSegment(currentPos, cardinalDir, diagonal, segLength, segElev));
            currentPos = currentPos.offset(
                    diagonal.getStepX() * segLength, segElev, diagonal.getStepZ() * segLength);
            stepsRemaining -= segLength;
            totalElev -= segElev;
        }

        return segments;
    }

    /**
     * For the last run of a route, returns true if the diagonal entry+exit chain
     * can land cleanly on the run's last waypoint. The chain consumes ~22 blocks
     * along the cardinal exit axis but only ~10 along the perpendicular - a built-in
     * forward bias of ~12 blocks.
     */
    static boolean diagonalChainFitsLastRun(WaypointRun run, Direction headDir) {
        DiagonalDirection diagonal = DirectionAnalyzer.getDiagonalRunDirection(run);
        Direction cardinal = DirectionAnalyzer.getCardinalForDiagonalEntry(diagonal, headDir);

        BlockPos first = run.firstWaypoint().position();
        BlockPos last = run.lastWaypoint().position();
        int absDx = Math.abs(last.getX() - first.getX());
        int absDz = Math.abs(last.getZ() - first.getZ());
        boolean forwardIsX = (cardinal.getStepX() != 0);
        int forwardSpan = forwardIsX ? absDx : absDz;
        int perpSpan = forwardIsX ? absDz : absDx;

        int radius = RailwaysUntoldConfig.getMinCurveRadius();
        int chainForward = 2 * curveForwardFootprint(radius);
        int chainPerp = 2 * curvePerpFootprint(radius);
        int chainBias = chainForward - chainPerp;

        return forwardSpan - perpSpan >= chainBias;
    }

    /**
     * Forward-axis footprint of a single 45° entry or exit curve: the cardinal-axis
     * distance it consumes. Larger than {@link #curvePerpFootprint} - a 45° curve
     * travels farther along its heading than it shifts laterally.
     */
    private static int curveForwardFootprint(int radius) {
        return (int) Math.ceil(radius * SCurve45Geometry.SIN_45);
    }

    /**
     * Perpendicular-axis footprint of a single 45° entry or exit curve: the lateral
     * shift it produces.
     */
    private static int curvePerpFootprint(int radius) {
        return (int) Math.ceil(radius * (1 - SCurve45Geometry.COS_45));
    }

    /**
     * Number of diagonal steps that fit between a chain's start (the post-entry
     * position) and the run's last waypoint, after reserving the trailing exit
     * curve's footprint on each axis. Each diagonal step consumes one block on both
     * cardinal axes, so the chain (entry + N steps + exit) covers N + reserve on each
     * axis; N is bounded by whichever axis runs out first.
     */
    private static int diagonalStepCount(BlockPos startPos, BlockPos lastWpPos,
            Direction cardinalDir, int reserveForwardSteps, int reservePerpSteps) {
        int totalDx = Math.abs(lastWpPos.getX() - startPos.getX());
        int totalDz = Math.abs(lastWpPos.getZ() - startPos.getZ());
        boolean forwardIsX = (cardinalDir.getStepX() != 0);
        int forwardAxisDist = forwardIsX ? totalDx : totalDz;
        int perpAxisDist = forwardIsX ? totalDz : totalDx;
        return Math.max(0, Math.min(
                forwardAxisDist - reserveForwardSteps,
                perpAxisDist - reservePerpSteps));
    }
}
