package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.planner.BezierSegment;
import com.vodmordia.railwaysuntold.worldgen.planner.PathSegment;
import com.vodmordia.railwaysuntold.worldgen.planner.SCurve45Segment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the magic-threshold bands in {@link ClosureHelper#closeResidualGap} (PC6): which residual gap
 * between the compiled tail and the route target takes which closure.
 *
 *   Path 1 - a forward-dominant gap ({@code forward >= 2*perp} and {@code perp <= MAX_RESIDUAL_GAP_PERP=4})
 *       closes with a single bezier straight to the target;
 *   Path 2 - a perpendicular-dominant residual still within reach ({@code perp <= MAX_SCURVE_RESIDUAL_PERP=16}
 *       with forward room) closes with an SCurve45 lateral shift - the case a DiagonalExit leaves;
 *   otherwise the gap is too lateral to close cleanly, and the tail is left to stand (a warn fires).
 *
 * The bands gate real geometry, so PC6 settles by characterizing them rather than deleting: Path 2 is
 * a live, reachable code path (a tail that ends short/lateral, before {@code trimOvershoot} would have
 * absorbed an overshoot). These gates make the thresholds reviewed values - changing one is now a diff.
 */
class ClosureHelperTest {

    @BeforeAll
    static void setUp() {
        PlannerTestConfig.bootstrapDefaults();
    }

    /** A flat straight tail whose end sits at {@code end} facing {@code dir} - the segment we close from. */
    private static List<PathSegment> tailEndingAt(BlockPos end, Direction dir) {
        List<PathSegment> segs = new ArrayList<>();
        segs.add(new BezierSegment(end.relative(dir.getOpposite(), 8), dir, end, dir));
        return segs;
    }

    @Test
    void forwardDominantGapClosesWithASingleBezier() {
        // forward 10, perp 2 (<= 4): Path 1.
        List<PathSegment> segs = tailEndingAt(new BlockPos(0, 72, 0), Direction.EAST);
        ClosureHelper.closeResidualGap(segs, new BlockPos(10, 72, 2));
        assertEquals(2, segs.size());
        PathSegment added = segs.get(1);
        assertTrue(added instanceof BezierSegment, "forward-dominant gap closes with a bezier");
        assertEquals(10, added.getEndPosition().getX());
        assertEquals(2, added.getEndPosition().getZ());
    }

    @Test
    void perpBeyondPath1BandButWithinScurveBandTakesTheScurveFallback() {
        // forward 48, perp 8 (> MAX_RESIDUAL_GAP_PERP=4, <= MAX_SCURVE_RESIDUAL_PERP=16): Path 2.
        List<PathSegment> segs = tailEndingAt(new BlockPos(0, 72, 0), Direction.EAST);
        ClosureHelper.closeResidualGap(segs, new BlockPos(48, 72, 8));
        assertTrue(segs.size() >= 2, "the scurve fallback adds at least the S-curve");
        assertTrue(segs.stream().anyMatch(s -> s instanceof SCurve45Segment),
                "a perpendicular-dominant residual within the band closes via SCurve45");
    }

    @Test
    void perpBeyondTheScurveBandLeavesTheTailStanding() {
        // perp 20 (> MAX_SCURVE_RESIDUAL_PERP=16): neither path fits, the tail stands.
        List<PathSegment> segs = tailEndingAt(new BlockPos(0, 72, 0), Direction.EAST);
        int before = segs.size();
        ClosureHelper.closeResidualGap(segs, new BlockPos(40, 72, 20));
        assertEquals(before, segs.size(), "a gap too lateral for either path is left as-is");
    }

    @Test
    void negligibleGapIsANoOp() {
        // gapDist 1: nothing to close.
        List<PathSegment> segs = tailEndingAt(new BlockPos(0, 72, 0), Direction.EAST);
        ClosureHelper.closeResidualGap(segs, new BlockPos(1, 72, 0));
        assertEquals(1, segs.size());
    }
}
