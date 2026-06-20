package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.planner.PathSegment;
import com.vodmordia.railwaysuntold.worldgen.planner.PlannedPath;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoutePlanner.PlanResult;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the connection invariant a non-derailing track must satisfy: the compiled {@link PlannedPath}
 * joins to itself - each segment's end position/direction is the next segment's start - and the path's
 * finalPosition is exactly the last segment's end. A train derails on any gap or direction kink, so a
 * failure here is a real bug. This is the planner-side half of the connection question; placement-time
 * continuity (the executor's clamp fallback) is tracked separately under Cluster PL / E6.
 */
class PrecisionPathInvariantTest {

    private static final UUID HEAD_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeAll
    static void setUp() {
        PlannerTestConfig.bootstrapDefaults();
    }

    @Test
    void flatStraightConnects() {
        assertConnected("flat-straight",
                plan(StubTerrainSampler.flat(72), bp(0, 72, 0), bp(256, 72, 0), Direction.EAST, -1));
    }

    @Test
    void flatDiagonalConnects() {
        assertConnected("flat-diagonal",
                plan(StubTerrainSampler.flat(72), bp(0, 72, 0), bp(256, 72, 256), Direction.EAST, -1));
    }

    @Test
    void slopeUpSteepConnects() {
        NoiseTerrainSampler s = StubTerrainSampler.withHeights(
                (x, z) -> 72 + Math.max(0, x) / 4, StubTerrainSampler.SEA_LEVEL);
        assertConnected("slope-up-steep", plan(s, bp(0, 72, 0), bp(256, 136, 0), Direction.EAST, -1));
    }

    @Test
    void slopeDownSteepConnects() {
        NoiseTerrainSampler s = StubTerrainSampler.withHeights(
                (x, z) -> 72 - Math.max(0, x) / 8, StubTerrainSampler.SEA_LEVEL);
        assertConnected("slope-down-steep", plan(s, bp(0, 72, 0), bp(256, 40, 0), Direction.EAST, -1));
    }

    @Test
    void bridgeRavineConnects() {
        NoiseTerrainSampler s = StubTerrainSampler.withHeights(
                (x, z) -> (x > 100 && x < 156) ? 50 : 72, StubTerrainSampler.SEA_LEVEL);
        assertConnected("bridge-ravine", plan(s, bp(0, 72, 0), bp(256, 72, 0), Direction.EAST, -1));
    }

    @Test
    void tunnelRidgeConnects() {
        NoiseTerrainSampler s = StubTerrainSampler.withHeights(
                (x, z) -> (x > 100 && x < 156) ? 110 : 72, StubTerrainSampler.SEA_LEVEL);
        assertConnected("tunnel-ridge", plan(s, bp(0, 72, 0), bp(256, 72, 0), Direction.EAST, -1));
    }

    @Test
    void mountainCurveAroundConnects() {
        NoiseTerrainSampler s = StubTerrainSampler.withHeights(
                (x, z) -> (x > 80 && x < 176) ? 150 : 72, StubTerrainSampler.SEA_LEVEL);
        assertConnected("mountain-curve-around", plan(s, bp(0, 72, 0), bp(256, 72, 0), Direction.EAST, -1));
    }

    @Test
    void descentHintConnects() {
        assertConnected("descent-hint",
                plan(StubTerrainSampler.flat(72), bp(0, 72, 0), bp(256, 55, 0), Direction.EAST, 55));
    }

    // --- helpers ---

    private static PlanResult plan(NoiseTerrainSampler sampler, BlockPos start, BlockPos target,
                                   Direction headDir, int descentHintY) {
        return CoarseRoutePlanner.planRouteOffThread(
                sampler, start, target, HEAD_ID, headDir, null,
                List.of(), null, List.of(), descentHintY, null, null, List.of(), false);
    }

    /** Asserts the compiled path joins to itself end-to-start and ends exactly at finalPosition. */
    private static void assertConnected(String scenario, PlanResult result) {
        assertNotNull(result, scenario + ": planRouteOffThread returned null");
        PlannedPath path = result.route().getPrecisionPath();
        assertTrue(path.valid, scenario + ": path invalid (" + path.invalidReason + ")");
        List<PathSegment> segs = path.segments;
        assertTrue(segs.size() >= 1, scenario + ": no segments");

        for (int i = 0; i < segs.size() - 1; i++) {
            PathSegment cur = segs.get(i);
            PathSegment next = segs.get(i + 1);
            assertEquals(cur.getEndPosition(), next.getStart(),
                    scenario + ": gap between segment " + i + " (" + cur.type + ") end and segment "
                            + (i + 1) + " (" + next.type + ") start");
            assertEquals(cur.getEndDirection(), next.getStartDirection(),
                    scenario + ": direction kink between segment " + i + " (" + cur.type + ") and "
                            + (i + 1) + " (" + next.type + ")");
        }

        PathSegment last = segs.get(segs.size() - 1);
        assertEquals(last.getEndPosition(), path.finalPosition,
                scenario + ": finalPosition does not match last segment end");
    }

    private static BlockPos bp(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }
}
