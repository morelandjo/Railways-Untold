package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoutePlanner.PlanResult;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.RouteObstacleAvoider.ExistingTrackCluster;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseStructureScanner.PredictedStructure;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.IntBinaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the replay-from-logs core: capture everything the pure planner sampled (heights, ocean/water,
 * the avoidable structures and existing-track clusters it routed around), serialize it to a
 * {@link ReplayRecord} string, parse it back, rebuild a sampler from the capture, re-run the planner,
 * and assert the route is byte-for-byte identical to the original. If this holds, a production log
 * carrying the same record reproduces a user's route deterministically with no world.
 */
class ReplayRoundTripTest {

    private static final UUID HEAD_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_HEAD = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final int SEA = StubTerrainSampler.SEA_LEVEL;
    private static final BlockPos START = new BlockPos(0, 72, 0);
    private static final BlockPos TARGET = new BlockPos(256, 72, 0);

    private static final ReplaySamplers.XZPredicate NEVER = (x, z) -> false;

    @BeforeAll
    static void setUp() {
        PlannerTestConfig.bootstrapDefaults();
    }

    @Test
    void capturedTerrainReplaysRouteByteForByte() {
        // A 40-high ridge across the path (rise > the 25 tunnel threshold) so the route is non-trivial
        // and the planner samples a varied terrain profile worth capturing.
        IntBinaryOperator ridge = (x, z) -> {
            int d = Math.abs(x - 128);
            return d < 60 ? 72 + (int) Math.round(40.0 * (1.0 - d / 60.0)) : 72;
        };
        ReplayRecord parsed = assertReplayReproducesRoute(
                ridge, NEVER, NEVER, List.of(), List.of(), Direction.EAST);
        assertTrue(parsed.heights.size() > 8,
                "expected the planner to sample many heights across the ridge, got " + parsed.heights.size());
        assertTrue(parsed.structures.isEmpty() && parsed.tracks.isEmpty(), "this scenario has no structures/tracks");
        assertTrue(parsed.oceanCoords.isEmpty() && parsed.waterCoords.isEmpty(), "this scenario has no water");
    }

    @Test
    void avoidableStructureReplaysRouteByteForByte() {
        PredictedStructure fortress = new PredictedStructure(
                new ChunkPos(8, 0), new BlockPos(128, 72, 0), "test:fortress",
                List.of("test:fortress"), false);
        ReplayRecord parsed = assertReplayReproducesRoute(
                flat(72), NEVER, NEVER, List.of(fortress), List.of(), Direction.EAST);
        assertEquals(1, parsed.structures.size(), "the avoidable structure must round-trip");
        PredictedStructure s = parsed.structures.get(0);
        assertEquals(new ChunkPos(8, 0), s.chunkPos());
        assertEquals(new BlockPos(128, 72, 0), s.approximateCenter());
        assertEquals("test:fortress", s.structureSetName());
        assertEquals(List.of("test:fortress"), s.possibleStructures());
        assertFalse(s.isVillage());
    }

    @Test
    void villageFootprintReplaysRouteByteForByte() {
        // A village laid alongside the corridor with a real (long-thin) footprint: the box-aware
        // avoidance reads the footprint, and the captured record must round-trip those piece boxes so a
        // replayed plan sees the exact same geometry.
        List<BoundingBox> footprint = List.of(
                new BoundingBox(112, 64, 20, 176, 80, 36),
                new BoundingBox(120, 64, 24, 140, 78, 32));
        PredictedStructure village = new PredictedStructure(
                new ChunkPos(8, 1), new BlockPos(144, 72, 28), "minecraft:villages",
                List.of("minecraft:village"), true, footprint);
        ReplayRecord parsed = assertReplayReproducesRoute(
                flat(72), NEVER, NEVER, List.of(village), List.of(), Direction.EAST);

        assertEquals(1, parsed.structures.size(), "the village must round-trip");
        PredictedStructure s = parsed.structures.get(0);
        assertTrue(s.isVillage());
        assertEquals(footprint.size(), s.footprint().size(), "footprint box count must round-trip");
        for (int i = 0; i < footprint.size(); i++) {
            BoundingBox e = footprint.get(i);
            BoundingBox a = s.footprint().get(i);
            assertEquals(e.minX(), a.minX());
            assertEquals(e.minY(), a.minY());
            assertEquals(e.minZ(), a.minZ());
            assertEquals(e.maxX(), a.maxX());
            assertEquals(e.maxY(), a.maxY());
            assertEquals(e.maxZ(), a.maxZ());
        }
    }

    @Test
    void existingTrackClusterReplaysRouteByteForByte() {
        ExistingTrackCluster cluster = new ExistingTrackCluster(OTHER_HEAD,
                new BlockPos(112, 70, -16), new BlockPos(144, 74, 16));
        ReplayRecord parsed = assertReplayReproducesRoute(
                flat(72), NEVER, NEVER, List.of(), List.of(cluster), Direction.EAST);
        assertEquals(1, parsed.tracks.size(), "the existing-track cluster must round-trip");
        ExistingTrackCluster t = parsed.tracks.get(0);
        assertEquals(OTHER_HEAD, t.headId());
        assertEquals(new BlockPos(112, 70, -16), t.boundingMin());
        assertEquals(new BlockPos(144, 74, 16), t.boundingMax());
    }

    @Test
    void waterCrossingReplaysRouteByteForByte() {
        // A river band the path crosses: a heightmap dip below sea level plus water present, so the
        // planner's water-bridge pass queries isLikelyWater and marks the band as a bridge.
        ReplaySamplers.XZPredicate riverBand = (x, z) -> x >= 100 && x <= 160;
        IntBinaryOperator dip = (x, z) -> riverBand.test(x, z) ? SEA - 3 : 72;
        ReplayRecord parsed = assertReplayReproducesRoute(
                dip, NEVER, riverBand, List.of(), List.of(), Direction.EAST);
        assertFalse(parsed.waterCoords.isEmpty(), "the water crossing must be captured and round-trip");
        assertTrue(parsed.oceanCoords.isEmpty(), "a river is not ocean");
    }

    /**
     * Runs the full capture -> serialize -> parse -> replay loop and asserts the replayed route matches
     * the original byte-for-byte. Returns the parsed record so callers can assert what was captured.
     */
    private ReplayRecord assertReplayReproducesRoute(
            IntBinaryOperator heightFn, ReplaySamplers.XZPredicate oceanFn, ReplaySamplers.XZPredicate waterFn,
            List<PredictedStructure> structures, List<ExistingTrackCluster> tracks, Direction headDir) {

        Map<Long, Integer> heights = new TreeMap<>();
        Set<Long> oceans = new TreeSet<>();
        Set<Long> waters = new TreeSet<>();
        NoiseTerrainSampler recSampler = ReplaySamplers.recording(heightFn, oceanFn, waterFn, SEA, heights, oceans, waters);
        PlanResult original = CoarseRoutePlanner.planRouteOffThread(
                recSampler, START, TARGET, HEAD_ID, headDir, null,
                structures, null, tracks, -1, null, null, List.of(), false);
        String originalSnap = GoldenSnapshot.serialize(original);

        ReplayRecord record = new ReplayRecord(START, TARGET, HEAD_ID, headDir, null, -1, false,
                SEA, RailwaysUntoldConfig.getMaxSlopeRatio(), heights, structures, tracks, oceans, waters);
        ReplayRecord parsed = ReplayRecord.parse(record.serialize());
        assertEquals(heights.size(), parsed.heights.size(), "height map lost entries through serialize/parse");

        NoiseTerrainSampler replaySampler = ReplaySamplers.fromCapture(
                parsed.heights, parsed.oceanCoords, parsed.waterCoords, parsed.seaLevel);
        PlanResult replay = CoarseRoutePlanner.planRouteOffThread(
                replaySampler, parsed.start, parsed.target, parsed.headId, parsed.headDir, parsed.arrivalDir,
                parsed.structures, null, parsed.tracks, parsed.descentHintY, null, null, List.of(), parsed.isFromTrackTip);
        String replaySnap = GoldenSnapshot.serialize(replay);

        assertEquals(originalSnap, replaySnap, "replay from the captured record did not reproduce the route");
        return parsed;
    }

    private static IntBinaryOperator flat(int y) {
        return (x, z) -> y;
    }
}
