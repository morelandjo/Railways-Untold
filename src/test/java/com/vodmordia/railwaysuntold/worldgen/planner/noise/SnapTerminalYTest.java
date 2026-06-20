package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.planner.BezierSegment;
import com.vodmordia.railwaysuntold.worldgen.planner.PathSegment;
import com.vodmordia.railwaysuntold.worldgen.planner.TunnelSegment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Characterizes {@link ClosureHelper#snapTerminalY} - the terminal-Y reconciliation that lifts a
 * final segment ending short of a fixed-Y target up to the exact target Y.
 *
 * It exists because the coarse plan budgets elevation generously (ceil(SAMPLE_INTERVAL * ratio))
 * while the precision clamp is conservative (floor((dist - endpoints) * ratio)), so a route into a
 * fixed-Y target compiles a block or two short - and a train track that ends short of its target
 * derails. It snaps only when reaching the target stays within the slope limit AS THE PLACEMENT
 * EXECUTOR measures it ({@link com.vodmordia.railwaysuntold.util.track.SlopeValidator#isValidSlope}:
 * Manhattan horizontal, endpoint-discounted). Snapping past that is futile - the bezier placement
 * backstop re-clamps the segment to exactly that limit anyway, so the landed Y is no different from
 * declining, only with a spurious [BEZIER-CLAMP]. When the final run is too short to descend the
 * remaining blocks legally (the descent-hint scenario's last 2-over-8), the track lands a block short
 * and the head replans rather than being forced onto a grade the executor would reject. This test
 * pins the decision in isolation: when it snaps, and the guards under which it declines.
 */
class SnapTerminalYTest {

    @BeforeAll
    static void setUp() {
        // snapTerminalY reads RailwaysUntoldConfig.getMaxSlopeRatio().
        PlannerTestConfig.bootstrapDefaults();
    }

    private static BlockPos bp(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    private static List<PathSegment> withFinal(PathSegment segment) {
        List<PathSegment> segments = new ArrayList<>();
        segments.add(segment);
        return segments;
    }

    @Test
    void liftsAFinalBezierThatLandsShortToTheExactTargetY() {
        // Final bezier reaches the target X/Z but 1 short in Y (56 vs 55) over a 24-block run.
        // Reaching 55 needs |55-57|=2 of fall; isValidSlope(24,-2)=2/22=0.09 - well within the limit
        // and what the executor would place, so it snaps to meet the fixed-Y target exactly.
        List<PathSegment> segments = withFinal(
                new BezierSegment(bp(232, 57, 0), Direction.EAST, bp(256, 56, 0), Direction.EAST));
        ClosureHelper.snapTerminalY(segments, bp(256, 55, 0));
        assertEquals(bp(256, 55, 0), segments.get(0).getEndPosition(),
                "a slope-legal short final bezier should snap to the exact target Y");
    }

    @Test
    void declinesAMarginalSnapTheExecutorWouldClampBackAnyway() {
        // Final bezier 1 short in Y (56 vs target 55) over an 8-block run. Snapping to 55 is a
        // 2-over-8 grade = 0.33 - over the executor's endpoint-discounted slope limit (max 1 over 8),
        // so BezierTrackPlacementExecutor would clamp the bezier straight back to 56 and log
        // [BEZIER-CLAMP]. The snap is futile; snapTerminalY declines and leaves the per-segment end.
        List<PathSegment> segments = withFinal(
                new BezierSegment(bp(248, 57, 0), Direction.EAST, bp(256, 56, 0), Direction.EAST));
        ClosureHelper.snapTerminalY(segments, bp(256, 55, 0));
        assertEquals(bp(256, 56, 0), segments.get(0).getEndPosition(),
                "a snap the executor would clamp must be declined");
    }

    @Test
    void declinesWhenReachingTheTargetWouldExceedSlopeOverTheFinalRun() {
        // Target needs |52-60|=8 of fall over a 6-block run; isValidSlope(6,-8) is far over the limit.
        // Genuinely too steep - leave it for the slope machinery, do not force a derailing grade.
        List<PathSegment> segments = withFinal(
                new BezierSegment(bp(250, 60, 0), Direction.EAST, bp(256, 58, 0), Direction.EAST));
        ClosureHelper.snapTerminalY(segments, bp(256, 52, 0));
        assertEquals(bp(256, 58, 0), segments.get(0).getEndPosition(),
                "a too-steep final run should be left untouched");
    }

    @Test
    void leavesANonBezierFinalSegmentUntouched() {
        // Only a final BezierSegment is snapped; a tunnel/straight/diagonal tail is left as placed.
        List<PathSegment> segments = withFinal(
                new TunnelSegment(bp(248, 57, 0), Direction.EAST, bp(256, 56, 0)));
        ClosureHelper.snapTerminalY(segments, bp(256, 55, 0));
        assertEquals(bp(256, 56, 0), segments.get(0).getEndPosition(),
                "a non-bezier final segment must not be snapped");
    }

    @Test
    void leavesAFinalSegmentThatMissesTheTargetXzUntouched() {
        // The X/Z must already match the target - snapTerminalY only reconciles Y, never lateral.
        List<PathSegment> segments = withFinal(
                new BezierSegment(bp(248, 57, 0), Direction.EAST, bp(256, 56, 4), Direction.EAST));
        ClosureHelper.snapTerminalY(segments, bp(256, 55, 0));
        assertEquals(bp(256, 56, 4), segments.get(0).getEndPosition(),
                "a final segment off the target X/Z must not be snapped");
    }

    @Test
    void isANoOpWhenTheFinalSegmentAlreadyReachesTheTargetY() {
        List<PathSegment> segments = withFinal(
                new BezierSegment(bp(248, 56, 0), Direction.EAST, bp(256, 55, 0), Direction.EAST));
        ClosureHelper.snapTerminalY(segments, bp(256, 55, 0));
        assertEquals(bp(256, 55, 0), segments.get(0).getEndPosition(),
                "already at the target Y - nothing to snap");
    }
}
