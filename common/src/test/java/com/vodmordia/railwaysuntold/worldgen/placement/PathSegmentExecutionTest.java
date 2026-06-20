package com.vodmordia.railwaysuntold.worldgen.placement;

import com.vodmordia.railwaysuntold.util.spatial.DiagonalDirection;
import com.vodmordia.railwaysuntold.worldgen.planner.*;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.PlannerTestConfig;
import com.vodmordia.railwaysuntold.worldgen.terrain.TerrainScanner.TerrainScan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Characterizes the pure decision boundary of the placement layer: {@link com.vodmordia.railwaysuntold.worldgen.planner.PathSegment#execute}
 * turns a planned segment plus a terrain scan into a {@link PlacementDecision} with no world access.
 * This is the layer reaching into placement (everything else is planner-only). It pins how a BEZIER
 * segment chooses between a cheap STRAIGHT run and a full bezier, and the decision each other segment
 * type emits - including the two branchy ones (TUNNEL flat-vs-elevated, DIAGONAL entry-vs-exit).
 *
 * A {@link PathExecutionContext} is a record; execute() reads its scan plus {@code currentPos} /
 * {@code currentDir} (the decision's start). The remaining fields ({@code state}, {@code level}) are
 * unused by these segments and left null.
 */
class PathSegmentExecutionTest {

    @BeforeAll
    static void setUp() {
        // CURVE goes through CreateTrackUtil, whose static init reads BuiltInRegistries for the
        // fake_track block - which needs the vanilla registries bootstrapped. Route through the shared
        // bootstrap the golden tests use rather than calling SharedConstants/Bootstrap directly, so the
        // game version is set exactly once (setVersion throws on a second override). The NeoForge runner
        // bootstraps registries for free on 1.21.1; the 1.20.1 PlannerTestConfig does it explicitly.
        PlannerTestConfig.bootstrapDefaults();
    }

    /** Builds a context whose terrain scan reports the given per-block heights ahead of the segment. */
    private static PathExecutionContext ctx(int[] heightProfile) {
        TerrainScan scan = heightProfile == null ? null : new TerrainScan(true, heightProfile);
        return new PathExecutionContext(null, BlockPos.ZERO, Direction.EAST, scan, null);
    }

    private static int[] flat(int height, int len) {
        int[] p = new int[len];
        java.util.Arrays.fill(p, height);
        return p;
    }


    @Test
    void flatAlignedRunOverLowTerrainEmitsStraight() {
        // Flat, on-axis, direction-matching segment with terrain at or below track Y -> cheap STRAIGHT.
        BezierSegment seg = new BezierSegment(
                new BlockPos(0, 72, 0), Direction.EAST, new BlockPos(64, 72, 0), Direction.EAST);
        PlacementDecision d = seg.execute(ctx(flat(72, 64)));
        assertEquals(PlacementDecision.Type.STRAIGHT, d.getType());
        assertEquals(64, d.getDistance());
    }

    @Test
    void elevationChangeEmitsBezier() {
        // Any Y change defeats the straight shortcut - must bezier.
        BezierSegment seg = new BezierSegment(
                new BlockPos(0, 72, 0), Direction.EAST, new BlockPos(64, 80, 0), Direction.EAST);
        PlacementDecision d = seg.execute(ctx(flat(72, 64)));
        assertEquals(PlacementDecision.Type.BEZIER, d.getType());
        assertEquals(new BlockPos(64, 80, 0), d.getEnd());
    }

    @Test
    void terrainAboveTrackForcesBezierEvenWhenFlatAndAligned() {
        // Flat and on-axis, but a hill rises above track Y along the run: a straight run would cut
        // into it, so the decision must fall back to bezier.
        int[] profile = flat(72, 64);
        profile[20] = 78;
        BezierSegment seg = new BezierSegment(
                new BlockPos(0, 72, 0), Direction.EAST, new BlockPos(64, 72, 0), Direction.EAST);
        PlacementDecision d = seg.execute(ctx(profile));
        assertEquals(PlacementDecision.Type.BEZIER, d.getType());
    }

    @Test
    void directionMismatchEmitsBezier() {
        // The geometric travel direction (EAST) disagrees with the segment's start direction (NORTH),
        // so the straight shortcut cannot apply.
        BezierSegment seg = new BezierSegment(
                new BlockPos(0, 72, 0), Direction.NORTH, new BlockPos(64, 72, 0), Direction.EAST);
        PlacementDecision d = seg.execute(ctx(flat(72, 64)));
        assertEquals(PlacementDecision.Type.BEZIER, d.getType());
    }

    @Test
    void nullScanTreatsTerrainAsClearAndEmitsStraight() {
        // With no scan the terrain-cut guard is skipped, so a flat aligned run stays STRAIGHT.
        BezierSegment seg = new BezierSegment(
                new BlockPos(0, 72, 0), Direction.EAST, new BlockPos(64, 72, 0), Direction.EAST);
        PlacementDecision d = seg.execute(ctx(null));
        assertEquals(PlacementDecision.Type.STRAIGHT, d.getType());
    }

    @Test
    void bezierDecisionIsPlannerAnchoredNotLiveHead() {
        // The bezier emits the planner's start (here Y=80), even when the live head sits 8 blocks below
        // (Y=72). A Y discrepancy between plan and head therefore surfaces downstream as a drift-halt in
        // BezierTrackPlacementExecutor rather than being silently bridged - see PathExecutorTest.
        BezierSegment seg = new BezierSegment(
                new BlockPos(0, 80, 0), Direction.EAST, new BlockPos(64, 80, 0), Direction.EAST);
        PathExecutionContext liveHeadBelow = new PathExecutionContext(
                null, new BlockPos(0, 72, 0), Direction.EAST, null, null);
        PlacementDecision d = seg.execute(liveHeadBelow);
        assertEquals(new BlockPos(0, 80, 0), d.getStart());
    }

    // --- TunnelSegment: the type is decided by whether the tunnel changes Y ---

    @Test
    void flatTunnelEmitsTunnel() {
        TunnelSegment seg = new TunnelSegment(BlockPos.ZERO, Direction.EAST, new BlockPos(32, 0, 0));
        PlacementDecision d = seg.execute(ctx(flat(0, 32)));
        assertEquals(PlacementDecision.Type.TUNNEL, d.getType());
    }

    @Test
    void elevatedTunnelEmitsElevatedTunnelCarryingTheRise() {
        TunnelSegment seg = new TunnelSegment(BlockPos.ZERO, Direction.EAST, new BlockPos(32, 8, 0));
        PlacementDecision d = seg.execute(ctx(flat(0, 32)));
        assertEquals(PlacementDecision.Type.ELEVATED_TUNNEL, d.getType());
        assertEquals(8, d.getElevationChange());
        assertEquals(new BlockPos(32, 8, 0), d.getEnd());
    }

    // --- BridgeSegment: a pass-through from the context start to the segment's end ---

    @Test
    void bridgeEmitsBridgeFromContextStartToSegmentEnd() {
        BridgeSegment seg = new BridgeSegment(BlockPos.ZERO, Direction.EAST, new BlockPos(48, 0, 0));
        PlacementDecision d = seg.execute(ctx(flat(0, 4)));
        assertEquals(PlacementDecision.Type.BRIDGE, d.getType());
        assertEquals(BlockPos.ZERO, d.getStart());
        assertEquals(new BlockPos(48, 0, 0), d.getEnd());
        assertEquals(Direction.EAST, d.getDirection());
    }

    // --- CurveSegment: a CURVE carrying the computed parameters ---

    @Test
    void curveEmitsCurveWithParams() {
        CurveSegment seg = new CurveSegment(BlockPos.ZERO, Direction.EAST, 20, false, 0);
        PlacementDecision d = seg.execute(ctx(flat(0, 4)));
        assertEquals(PlacementDecision.Type.CURVE, d.getType());
        assertNotNull(d.getCurveParams());
    }

    // --- SCurve45Segment: a pass-through to an SCURVE_45 decision ---

    @Test
    void scurve45EmitsScurve45() {
        SCurve45Segment seg = new SCurve45Segment(BlockPos.ZERO, Direction.EAST, 10, 8, true, 0);
        PlacementDecision d = seg.execute(ctx(flat(0, 4)));
        assertEquals(PlacementDecision.Type.SCURVE_45, d.getType());
    }

    // --- DiagonalStraightSegment: a DIAGONAL_STRAIGHT whose end steps along the diagonal axis ---

    @Test
    void diagonalStraightEmitsDiagonalStraightSteppingAlongTheDiagonal() {
        DiagonalStraightSegment seg = new DiagonalStraightSegment(
                BlockPos.ZERO, Direction.EAST, DiagonalDirection.SOUTHEAST, 8, 0);
        PlacementDecision d = seg.execute(ctx(flat(0, 4)));
        assertEquals(PlacementDecision.Type.DIAGONAL_STRAIGHT, d.getType());
        // SOUTHEAST is (+X, +Z); 8 flat steps land at (8, 0, 8).
        assertEquals(new BlockPos(8, 0, 8), d.getEnd());
    }

    // --- DiagonalCurveSegment: the entry/exit flag selects the decision type ---

    @Test
    void diagonalEntryCurveEmitsDiagonalEntry() {
        DiagonalCurveSegment seg = DiagonalCurveSegment.entry(
                BlockPos.ZERO, Direction.EAST, DiagonalDirection.SOUTHEAST, 10, 0);
        PlacementDecision d = seg.execute(ctx(flat(0, 4)));
        assertEquals(PlacementDecision.Type.DIAGONAL_ENTRY, d.getType());
    }

    @Test
    void diagonalExitCurveEmitsDiagonalExit() {
        DiagonalCurveSegment seg = DiagonalCurveSegment.exit(
                BlockPos.ZERO, Direction.EAST, DiagonalDirection.SOUTHEAST, Direction.SOUTH, 10, 0);
        PlacementDecision d = seg.execute(ctx(flat(0, 4)));
        assertEquals(PlacementDecision.Type.DIAGONAL_EXIT, d.getType());
    }
}
