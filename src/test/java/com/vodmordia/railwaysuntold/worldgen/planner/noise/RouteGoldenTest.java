package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.planner.PlannedPath;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.CoarseWaypoint;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.WaypointType;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoutePlanner.PlanResult;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseStructureScanner.PredictedStructure;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Characterization (golden) tests for the off-thread route plan path. Each
 * scenario exercises a distinct branch of the 14-step pipeline and asserts the
 * serialized route is byte-for-byte identical to a committed snapshot. A diff is
 * a behavior change - a bug to investigate, not an improvement.
 *
 * Snapshots live under src/test/resources/golden/. To (re)generate after an
 * intended change, run with -Dgolden.update=true and eyeball the diff before
 * committing. A missing snapshot is generated automatically on first run.
 */
class RouteGoldenTest {

    private static final UUID HEAD_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final boolean UPDATE = Boolean.getBoolean("golden.update");
    private static final String GOLDEN_DIR = System.getProperty("golden.source.dir");

    @BeforeAll
    static void setUp() {
        PlannerTestConfig.bootstrapDefaults();
    }

    @Test
    void flatStraight() throws IOException {
        verify("flat-straight", plan(StubTerrainSampler.flat(72),
                bp(0, 72, 0), bp(256, 72, 0), Direction.EAST));
    }

    @Test
    void flatDiagonal() throws IOException {
        verify("flat-diagonal", plan(StubTerrainSampler.flat(72),
                bp(0, 72, 0), bp(256, 72, 256), Direction.EAST));
    }

    @Test
    void flatOffAxis() throws IOException {
        verify("flat-offaxis", plan(StubTerrainSampler.flat(72),
                bp(0, 72, 0), bp(256, 72, 40), Direction.EAST));
    }

    @Test
    void slopeUpSteep() throws IOException {
        NoiseTerrainSampler s = StubTerrainSampler.withHeights(
                (x, z) -> 72 + Math.max(0, x) / 4, StubTerrainSampler.SEA_LEVEL);
        verify("slope-up-steep", plan(s, bp(0, 72, 0), bp(256, 136, 0), Direction.EAST));
    }

    @Test
    void slopeDownSteep() throws IOException {
        NoiseTerrainSampler s = StubTerrainSampler.withHeights(
                (x, z) -> 72 - Math.max(0, x) / 8, StubTerrainSampler.SEA_LEVEL);
        verify("slope-down-steep", plan(s, bp(0, 72, 0), bp(256, 40, 0), Direction.EAST));
    }

    @Test
    void bridgeRavine() throws IOException {
        NoiseTerrainSampler s = StubTerrainSampler.withHeights(
                (x, z) -> (x > 100 && x < 156) ? 50 : 72, StubTerrainSampler.SEA_LEVEL);
        verify("bridge-ravine", plan(s, bp(0, 72, 0), bp(256, 72, 0), Direction.EAST));
    }

    @Test
    void tunnelRidge() throws IOException {
        NoiseTerrainSampler s = StubTerrainSampler.withHeights(
                (x, z) -> (x > 100 && x < 156) ? 110 : 72, StubTerrainSampler.SEA_LEVEL);
        verify("tunnel-ridge", plan(s, bp(0, 72, 0), bp(256, 72, 0), Direction.EAST));
    }

    @Test
    void mountainTall() throws IOException {
        NoiseTerrainSampler s = StubTerrainSampler.withHeights(
                (x, z) -> (x > 80 && x < 176) ? 150 : 72, StubTerrainSampler.SEA_LEVEL);
        verify("mountain-curve-around", plan(s, bp(0, 72, 0), bp(256, 72, 0), Direction.EAST));
    }

    @Test
    void descentHint() throws IOException {
        // Station entry (target Y) sits below the flat approach terrain; the planner
        // ramps the tail down toward the hint Y and marks sub-surface waypoints TUNNEL.
        verify("descent-hint", CoarseRoutePlanner.planRouteOffThread(
                StubTerrainSampler.flat(72), bp(0, 72, 0), bp(256, 55, 0), HEAD_ID,
                Direction.EAST, null, List.of(), null, List.of(), 55,
                null, null, List.of(), false));
    }

    @Test
    void slopedTunnel() throws IOException {
        // Direct compile of a TUNNEL run on a gentle slope, exercising the elevated-tunnel branch
        // of TunnelSegment (segEndY != currentPos.getY()) in isolation, like ConflictRampGoldenTest does for
        // the ramp helpers. The full-pipeline descent-hint scenario also reaches this branch, now
        // that lowerTowardPins tunnels a descent down toward a below-terrain station target.
        List<CoarseWaypoint> wps = new ArrayList<>();
        int y = 84;
        for (int i = 0; i <= 8; i++) {
            wps.add(new CoarseWaypoint(bp(i * 8, y, 0), y, WaypointType.TUNNEL));
            y -= 1; // 1 per 8 blocks, within the max slope so it stays one sloped run
        }
        BlockPos start = wps.get(0).position();
        BlockPos target = wps.get(wps.size() - 1).position();
        PlannedPath path = PrecisionRouteCompiler.compile(wps, start, target, Direction.EAST, false);
        PrecisionRoute route = new PrecisionRoute(new CoarseRoute(HEAD_ID, wps), path);
        verify("sloped-tunnel", new PlanResult(route, null));
    }

    @Test
    void trackTipPrefix() throws IOException {
        // isFromTrackTip prepends an on-axis forward waypoint so the first run matches head dir.
        verify("track-tip-prefix", CoarseRoutePlanner.planRouteOffThread(
                StubTerrainSampler.flat(72), bp(0, 72, 0), bp(256, 72, 80), HEAD_ID,
                Direction.EAST, null, List.of(), null, List.of(), -1,
                null, null, List.of(), true));
    }

    @Test
    void stationFitArrivalAxis() throws IOException {
        // arrivalDirection pins the tail to a cardinal axis via injectArrivalAxisSuffix.
        verify("station-fit-arrival-axis", CoarseRoutePlanner.planRouteOffThread(
                StubTerrainSampler.flat(72), bp(0, 72, 0), bp(256, 72, 0), HEAD_ID,
                Direction.EAST, Direction.SOUTH, List.of(), null, List.of(), -1,
                null, null, List.of(), false));
    }

    @Test
    void structureDetour() throws IOException {
        PredictedStructure fortress = new PredictedStructure(
                new ChunkPos(8, 0), bp(128, 72, 0), "test:fortress",
                List.of("test:fortress"), false);
        verify("structure-detour", plan(StubTerrainSampler.flat(72),
                bp(0, 72, 0), bp(256, 72, 0), Direction.EAST, List.of(fortress)));
    }

    @Test
    void multiStructureCluster() throws IOException {
        PredictedStructure a = new PredictedStructure(
                new ChunkPos(7, 0), bp(120, 72, 8), "test:fortress",
                List.of("test:fortress"), false);
        PredictedStructure b = new PredictedStructure(
                new ChunkPos(8, 0), bp(136, 72, -8), "test:fortress",
                List.of("test:fortress"), false);
        verify("multi-structure-cluster", plan(StubTerrainSampler.flat(72),
                bp(0, 72, 0), bp(256, 72, 0), Direction.EAST, List.of(a, b)));
    }

    // --- helpers ---

    private static BlockPos bp(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    private PlanResult plan(NoiseTerrainSampler sampler, BlockPos start, BlockPos target,
                            Direction headDir) {
        return plan(sampler, start, target, headDir, List.of());
    }

    private PlanResult plan(NoiseTerrainSampler sampler, BlockPos start, BlockPos target,
                            Direction headDir, List<PredictedStructure> structures) {
        return CoarseRoutePlanner.planRouteOffThread(
                sampler, start, target, HEAD_ID, headDir, null,
                structures, null, List.of(), -1, null, null, List.of(), false);
    }

    private void verify(String scenario, PlanResult result) throws IOException {
        assertNotNull(result, "planRouteOffThread returned null");
        assertNotNull(result.route(), "PlanResult.route was null");
        // Every known-good golden route must be seam-clean - this both validates the seam detector
        // against the full corpus (no false positives) and guards that goldens stay traversable.
        if (result.route().getPrecisionPath().valid) {
            int seam = RouteSeamInspector.findIllegalSeam(result.route().getPrecisionPath());
            assertEquals(-1, seam, "scenario '" + scenario + "' compiled an illegal seam at junction " + seam);
        }
        String actual = GoldenSnapshot.serialize(result);

        if (GOLDEN_DIR == null) {
            throw new IllegalStateException("golden.source.dir system property not set");
        }
        Path golden = Path.of(GOLDEN_DIR, scenario + ".txt");
        if (UPDATE || Files.notExists(golden)) {
            Files.createDirectories(golden.getParent());
            Files.writeString(golden, actual);
        }
        String expected = Files.readString(golden);
        assertEquals(expected, actual,
                () -> "Golden mismatch for '" + scenario + "'. If this change is intended, "
                        + "re-run with -Dgolden.update=true and review the diff before committing.");
    }
}
