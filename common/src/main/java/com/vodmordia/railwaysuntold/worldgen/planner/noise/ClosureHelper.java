package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.placement.SCurve45Geometry;
import com.vodmordia.railwaysuntold.util.track.SlopeValidator;
import com.vodmordia.railwaysuntold.worldgen.planner.BezierSegment;
import com.vodmordia.railwaysuntold.worldgen.planner.PathSegment;
import com.vodmordia.railwaysuntold.worldgen.planner.SCurve45Segment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.List;

/**
 * Route-termination helpers for the precision compiler: trimming segments that overshoot
 * the target and closing a residual gap to the target with a bezier or SCurve45.
 */
final class ClosureHelper {

    /** Absolute cap on the perpendicular component of a residual gap we're willing
     *  to close with a single bezier - beyond this, no ratio makes the curve clean. */
    private static final int MAX_RESIDUAL_GAP_PERP = 4;
    /** Cap on the perpendicular component for the SCurve45 fallback. Beyond this the
     *  S-curve's diagonal segment grows long enough that an alternative routing
     *  (cardinal + 90° curves) is preferable; let the warn fire instead. */
    private static final int MAX_SCURVE_RESIDUAL_PERP = 16;

    private ClosureHelper() {}

    /**
     * Reconciles the compiled tail to the route target in one terminal pass, so callers use a single
     * entry point rather than ordering the steps themselves. trimOvershoot handles a tail that ran
     * past routeEnd: it trims the overshooting segments and reconnects to routeEnd's exact X/Z.
     * closeResidualGap handles a tail that ended short of or lateral to routeEnd. Because trimOvershoot
     * reconnects onto routeEnd's X/Z, the planar gap is zero afterward, so closeResidualGap is a no-op
     * whenever a trim fired - at most one of the two ever changes the path; they never stack.
     * snapTerminalY then lifts the final segment's end to the exact target Y when the conservative
     * per-segment slope clamp left it a block or two short.
     */
    static void reconcileToTarget(List<PathSegment> segments, BlockPos routeEnd, Direction dir) {
        if (segments.isEmpty()) return;
        trimOvershoot(segments, routeEnd, dir);
        closeResidualGap(segments, routeEnd);
        snapTerminalY(segments, routeEnd);
    }

    /**
     * Closes a residual gap between the final segment's end and routeEnd. Tries a
     * single forward-dominant bezier first; falls back to an SCurve45 when the gap
     * is perpendicular-dominant but the cardinal corridor still has room for the
     * S-curve's footprint. If neither fits, logs a warn and lets the tail stand -
     * "track lands where it lands" is better than broken geometry.
     */
    static void closeResidualGap(List<PathSegment> allSegments, BlockPos routeEnd) {
        PathSegment tail = allSegments.get(allSegments.size() - 1);
        BlockPos tailEnd = tail.getEndPosition();
        int gapX = routeEnd.getX() - tailEnd.getX();
        int gapZ = routeEnd.getZ() - tailEnd.getZ();
        int gapDist = Math.abs(gapX) + Math.abs(gapZ);
        if (gapDist <= 1) return;

        Direction dir = tail.getEndDirection();
        int forwardDist = Math.abs(gapX * dir.getStepX() + gapZ * dir.getStepZ());
        int perpDist = Math.abs(gapX * dir.getStepZ() - gapZ * dir.getStepX());

        double maxSlope = RailwaysUntoldConfig.getMaxSlopeRatio();
        int yDelta = Math.abs(routeEnd.getY() - tailEnd.getY());
        int effectiveForward = Math.max(1, forwardDist - SlopeValidator.STRAIGHT_ENDPOINT_BLOCKS);
        int maxYOverGap = (int) Math.floor(effectiveForward * maxSlope);

        // Path 1: single forward-dominant bezier. Cheapest closure when geometry
        // permits. A forward >= perp threshold admits 1:1 gaps, which Create renders
        // with inverted tangent axes - geometry the track physics can't traverse.
        // Require forward >= 2*perp and perp <= MAX_RESIDUAL_GAP_PERP.
        if (forwardDist >= 2 * perpDist && perpDist <= MAX_RESIDUAL_GAP_PERP) {
            if (yDelta > maxYOverGap) {
                return; // too steep to close in one bezier - leave the residual; the head re-plans next pass
            }
            int gapEndY = RouteMath.clampEndYToSlopeLimit(tailEnd.getY(), routeEnd.getY(), forwardDist);
            BlockPos gapEnd = new BlockPos(routeEnd.getX(), gapEndY, routeEnd.getZ());
            allSegments.add(new BezierSegment(tailEnd, dir, gapEnd, dir));
            return; // gap closed (Y clamped to the slope limit if needed) - routine, not surfaced
        }

        // Path 2: SCurve45 fallback for perpendicular-dominant gaps. Common after a
        // DiagonalExit when the diagonal's natural endpoint leaves a small lateral
        // residual. SCurve45 (curve->diagonal->curve) is purpose-built for this lateral
        // shift and avoids the broken-tangent geometry of a sharp single bezier.
        if (perpDist > 0 && perpDist <= MAX_SCURVE_RESIDUAL_PERP) {
            int radius = RailwaysUntoldConfig.getMinCurveRadius();
            int diagLen = SCurve45Geometry.calculateDiagonalLengthForLateralShift(perpDist, radius);
            int scurveForward = SCurve45Geometry.calculateForwardDistance(radius, diagLen);
            if (scurveForward <= forwardDist && yDelta <= maxYOverGap) {
                int signedLateral = RouteMath.lateralOffset(tailEnd, routeEnd, dir);
                boolean shiftLeft = signedLateral < 0;
                SCurve45Segment scurve = new SCurve45Segment(tailEnd, dir, radius, diagLen, shiftLeft, 0);
                allSegments.add(scurve);
                BlockPos scurveEnd = scurve.getEndPosition();

                int remainingForward = forwardDist - scurveForward;
                if (remainingForward > 0 || scurveEnd.getY() != routeEnd.getY()) {
                    int finalDist = Math.max(remainingForward, 1);
                    int finalEndY = RouteMath.clampEndYToSlopeLimit(scurveEnd.getY(), routeEnd.getY(), finalDist);
                    BlockPos finalEnd = new BlockPos(routeEnd.getX(), finalEndY, routeEnd.getZ());
                    allSegments.add(new BezierSegment(scurveEnd, dir, finalEnd, dir));
                }
                return;
            }
        }

        // Neither closure geometry fits (not forward-dominant, not a small lateral residual) - leave the
        // gap; the route ends at its tail and the head re-plans toward the target from there next pass.
    }

    /**
     * Trims overshoot past the route target.
     */
    static void trimOvershoot(List<PathSegment> segments, BlockPos routeEnd, Direction dir) {
        if (segments.isEmpty()) return;

        PathSegment last = segments.get(segments.size() - 1);
        BlockPos finalPos = last.getEndPosition();

        // Guard against a coarse wiggle leaving the final heading pointing AWAY from the target. The
        // overshoot/trim below is measured along dir (the final segment's heading); if dir points backward
        // relative to the overall route (start -> target), that measure is meaningless and the loop would
        // cascade-trim the whole route back to the entry, stamping a phantom station: a westbound
        // route ending on an EAST-pointing segment gets trimmed 24 segments -> 1. dir aligned with the
        // route (the normal case, including a converging head) passes and trims exactly as before.
        BlockPos routeStart = segments.get(0).getStart();
        int towardTarget = (routeEnd.getX() - routeStart.getX()) * dir.getStepX()
                + (routeEnd.getZ() - routeStart.getZ()) * dir.getStepZ();
        if (towardTarget < 0) return;

        // Check if we overshot: final position is past routeEnd along travel direction
        int overshoot = (finalPos.getX() - routeEnd.getX()) * dir.getStepX()
                + (finalPos.getZ() - routeEnd.getZ()) * dir.getStepZ();

        if (overshoot <= 0) return; // No overshoot


        // Remove segments from the end until we're before the target
        while (segments.size() > 1) {
            PathSegment tail = segments.get(segments.size() - 1);
            BlockPos tailStart = tail.getStart();
            int startDist = (routeEnd.getX() - tailStart.getX()) * dir.getStepX()
                    + (routeEnd.getZ() - tailStart.getZ()) * dir.getStepZ();
            if (startDist >= 0) {
                // This segment's start is before the target - replace it with a direct
                // connection. The residual to routeEnd may lie in a perpendicular axis (a
                // diagonal chain that hits the target Z but misses the X), so a bezier is
                // still needed to close any planar gap. The slope clamp uses the cardinal
                // forward run - the axis Create renders the grade over - not the Manhattan
                // total; a perpendicular-only residual has zero forward run and so closes flat.
                segments.remove(segments.size() - 1);
                int planarDist = Math.abs(routeEnd.getX() - tailStart.getX())
                        + Math.abs(routeEnd.getZ() - tailStart.getZ());
                if (planarDist > 0) {
                    // Close any planar residual with a bezier (Y clamped to the slope limit if needed).
                    Direction segDir = tail.getStartDirection();
                    int forwardDist = RouteMath.horizontalDistance(tailStart, routeEnd, segDir);
                    int endY = RouteMath.clampEndYToSlopeLimit(tailStart.getY(), routeEnd.getY(), forwardDist);
                    BlockPos trimmedEnd = new BlockPos(routeEnd.getX(), endY, routeEnd.getZ());
                    segments.add(new BezierSegment(tailStart, segDir, trimmedEnd, segDir));
                }
                return;
            }
            segments.remove(segments.size() - 1);
        }
    }

    /**
     * Terminal-Y reconciliation. A route into a fixed-Y target can land a block or two short of
     * it: the per-segment slope clamp is conservative (it discounts STRAIGHT_ENDPOINT_BLOCKS and
     * floors), and that shortfall accumulates across segments - so e.g. a climb planned at exactly
     * the config slope lands under the target. A train track must meet the target exactly or it
     * derails. When the final segment ends at the target's X/Z but short in Y, snap its end to the
     * exact target Y, provided reaching it stays within the slope limit as the placement executor
     * measures it (so a genuinely too-steep target is left for the slope machinery, not forced).
     * The marginally steeper final bezier connects cleanly; a gap does not.
     */
    static void snapTerminalY(List<PathSegment> segments, BlockPos routeEnd) {
        if (segments.isEmpty()) return;
        int lastIdx = segments.size() - 1;
        if (!(segments.get(lastIdx) instanceof BezierSegment bez)) return;

        BlockPos end = bez.getEndPosition();
        if (end.getX() != routeEnd.getX() || end.getZ() != routeEnd.getZ()) return;
        if (end.getY() == routeEnd.getY()) return;

        // Only snap when the placement executor would accept the snapped grade - the same
        // SlopeValidator check (Manhattan horizontal, endpoint-discounted) it runs in
        // BezierTrackPlacementExecutor. Snapping past it is futile: the placement backstop re-clamps
        // the bezier to exactly this limit anyway, so the only effect would be a spurious [BEZIER-CLAMP]
        // and the same landed Y. When the exact target Y is out of slope reach, leave the segment as the
        // per-segment clamp placed it and let the head replan from there.
        int horizDist = Math.abs(routeEnd.getX() - bez.getStart().getX())
                + Math.abs(routeEnd.getZ() - bez.getStart().getZ());
        int neededElev = routeEnd.getY() - bez.getStart().getY();
        if (!SlopeValidator.isValidSlope(horizDist, neededElev)) return;

        BlockPos snapped = new BlockPos(end.getX(), routeEnd.getY(), end.getZ());
        segments.set(lastIdx, new BezierSegment(bez.getStart(), bez.getStartDirection(), snapped, bez.getEndDirection()));
    }
}
