package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.planner.BezierSegment;
import com.vodmordia.railwaysuntold.worldgen.planner.PathSegment;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoutePlanner.PlanResult;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gates the seam detector behind the [REPLAY] illegal-seam capture: it must flag a sharp inter-segment
 * kink (the (995,3388)-style geometry) and, crucially, must NOT false-positive on well-formed routes -
 * otherwise every route would tag itself and the capture would be useless.
 */
class RouteSeamInspectorTest {

    private static final UUID HEAD = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeAll
    static void setUp() {
        PlannerTestConfig.bootstrapDefaults();
    }

    @Test
    void flagsASharpJunctionWhereExitAndEntryDirectionsDiverge() {
        // seg0 exits heading EAST; seg1 enters heading NORTH - a 90° kink at the junction, no curve.
        PathSegment seg0 = new BezierSegment(new BlockPos(0, 72, 0), Direction.EAST,
                new BlockPos(4, 72, 0), Direction.EAST);
        PathSegment seg1 = new BezierSegment(new BlockPos(4, 72, 0), Direction.NORTH,
                new BlockPos(4, 72, -4), Direction.NORTH);
        assertEquals(0, RouteSeamInspector.findIllegalSeam(List.of(seg0, seg1)),
                "a >=90 degree exit/entry mismatch is an illegal seam");
    }

    @Test
    void flagsAReversal() {
        PathSegment seg0 = new BezierSegment(new BlockPos(0, 72, 0), Direction.EAST,
                new BlockPos(4, 72, 0), Direction.EAST);
        PathSegment seg1 = new BezierSegment(new BlockPos(4, 72, 0), Direction.WEST,
                new BlockPos(0, 72, 0), Direction.WEST);
        assertEquals(0, RouteSeamInspector.findIllegalSeam(List.of(seg0, seg1)), "180° reversal is illegal");
    }

    @Test
    void continuousChainIsClean() {
        PathSegment seg0 = new BezierSegment(new BlockPos(0, 72, 0), Direction.EAST,
                new BlockPos(4, 72, 0), Direction.EAST);
        PathSegment seg1 = new BezierSegment(new BlockPos(4, 72, 0), Direction.EAST,
                new BlockPos(8, 72, 0), Direction.EAST);
        assertEquals(-1, RouteSeamInspector.findIllegalSeam(List.of(seg0, seg1)),
                "aligned exit/entry directions are continuous");
    }

    // --- no false positives on real compiled routes ---

    private static PlanResult plan(NoiseTerrainSampler sampler, BlockPos start, BlockPos target, Direction dir) {
        return CoarseRoutePlanner.planRouteOffThread(
                sampler, start, target, HEAD, dir, null,
                List.of(), null, List.of(), -1, null, null, List.of(), false);
    }

    private static void assertNoSeam(String label, NoiseTerrainSampler sampler,
                                     BlockPos start, BlockPos target, Direction dir) {
        PlanResult r = plan(sampler, start, target, dir);
        int seam = RouteSeamInspector.findIllegalSeam(r.route().getPrecisionPath());
        assertTrue(seam < 0, label + ": well-formed route flagged an illegal seam at junction " + seam);
    }

    @Test
    void wellFormedRoutesAreNotFlagged() {
        assertNoSeam("flat straight", StubTerrainSampler.flat(72), new BlockPos(0, 72, 0), new BlockPos(256, 72, 0), Direction.EAST);
        assertNoSeam("flat diagonal", StubTerrainSampler.flat(72), new BlockPos(0, 72, 0), new BlockPos(256, 72, 256), Direction.EAST);
        assertNoSeam("flat off-axis", StubTerrainSampler.flat(72), new BlockPos(0, 72, 0), new BlockPos(256, 72, 64), Direction.EAST);
        assertNoSeam("perpendicular target", StubTerrainSampler.flat(72), new BlockPos(0, 72, 0), new BlockPos(64, 72, 256), Direction.EAST);
        assertNoSeam("back-and-left", StubTerrainSampler.flat(72), new BlockPos(0, 72, 0), new BlockPos(128, 72, -192), Direction.EAST);
    }
}
