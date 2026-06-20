package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.planner.PlannedPath;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.CoarseWaypoint;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.WaypointType;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseStructureScanner.PredictedStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit coverage for the structure-merge helper extracted from
 * CoarseRouteFactory.createAndAttachInternal. The surrounding async retry / generation-gate
 * flow is not unit-tested here: it crosses a thread hop and a tick boundary and depends on a
 * live ServerLevel, and the routeGenerations gate is intentionally left untouched, so it is
 * covered by in-game soak rather than a unit test.
 */
class CoarseRouteFactoryTest {

    private static final UUID HEAD = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeAll
    static void setUp() {
        PlannerTestConfig.bootstrapDefaults();
    }

    private static PredictedStructure atChunk(int cx, int cz) {
        return new PredictedStructure(
                new ChunkPos(cx, cz),
                new BlockPos(cx * 16, 72, cz * 16),
                "test:structure",
                List.of("test:structure"),
                false);
    }

    @Test
    void mergeAddsExtrasFromNewChunks() {
        List<PredictedStructure> into = new ArrayList<>(List.of(atChunk(0, 0)));
        List<PredictedStructure> extra = List.of(atChunk(1, 0), atChunk(2, 0));

        CoarseRouteFactory.mergeStructuresDedupedByChunk(into, extra);

        assertEquals(3, into.size());
    }

    @Test
    void mergeSkipsExtrasWhoseChunkAlreadyPresent() {
        List<PredictedStructure> into = new ArrayList<>(List.of(atChunk(0, 0), atChunk(1, 0)));
        List<PredictedStructure> extra = List.of(atChunk(1, 0), atChunk(2, 0));

        CoarseRouteFactory.mergeStructuresDedupedByChunk(into, extra);

        // Only the chunk (2,0) is new; (1,0) is already represented.
        assertEquals(3, into.size());
    }

    @Test
    void mergeDedupesAmongExtrasThemselves() {
        List<PredictedStructure> into = new ArrayList<>();
        List<PredictedStructure> extra = List.of(atChunk(5, 5), atChunk(5, 5));

        CoarseRouteFactory.mergeStructuresDedupedByChunk(into, extra);

        assertEquals(1, into.size());
    }

    @Test
    void mergeWithEmptyExtraLeavesIntoUnchanged() {
        List<PredictedStructure> into = new ArrayList<>(List.of(atChunk(0, 0)));

        CoarseRouteFactory.mergeStructuresDedupedByChunk(into, List.of());

        assertEquals(1, into.size());
    }

    private static CoarseRoute route(int waypointCount) {
        List<CoarseWaypoint> wps = new ArrayList<>();
        for (int i = 0; i < waypointCount; i++) {
            wps.add(new CoarseWaypoint(new BlockPos(i * 8, 72, 0), 72, WaypointType.TERRAIN_FOLLOW));
        }
        return new CoarseRoute(HEAD, wps);
    }

    @Test
    void validRecompileInstallsAdjustedRoute() {
        PrecisionRoute offThread = new PrecisionRoute(route(1), PlannedPath.invalid("off-thread"));
        CoarseRoute adjusted = route(3);
        PlannedPath recompiled = PlannedPath.success(List.of(), new BlockPos(0, 72, 0), Direction.EAST);

        PrecisionRoute installed = CoarseRouteFactory.selectInstalledPrecisionRoute(
                offThread, adjusted, recompiled, 1);

        assertSame(adjusted, installed.getCoarseRoute());
        assertSame(recompiled, installed.getPrecisionPath());
    }

    @Test
    void invalidRecompileKeepsOffThreadRoute() {
        PrecisionRoute offThread = new PrecisionRoute(route(1),
                PlannedPath.success(List.of(), new BlockPos(0, 72, 0), Direction.EAST));
        CoarseRoute adjusted = route(3);
        PlannedPath recompiled = PlannedPath.invalid("recompile failed");

        PrecisionRoute installed = CoarseRouteFactory.selectInstalledPrecisionRoute(
                offThread, adjusted, recompiled, 1);

        assertSame(offThread, installed);
    }

    @Test
    void anomalyReplayGateEmitsOncePerStuckEpisode() {
        UUID head = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        CoarseRouteFactory.lastReplayByHead.remove(head);
        CoarseRouteFactory.replayAnomalyGate.remove(head);

        // No stored record yet: the anomaly emit is a no-op and does not arm the gate.
        CoarseRouteFactory.emitReplayOnAnomaly(head, 1, "EXECUTE-HALT");
        assertFalse(CoarseRouteFactory.replayAnomalyGate.contains(head));

        // With a record stored, the first anomaly arms the gate (emits); a storm of further anomalies is
        // deduped to that one record.
        CoarseRouteFactory.lastReplayByHead.put(head, minimalRecord(head));
        CoarseRouteFactory.emitReplayOnAnomaly(head, 1, "EXECUTE-HALT");
        assertTrue(CoarseRouteFactory.replayAnomalyGate.contains(head));
        CoarseRouteFactory.emitReplayOnAnomaly(head, 1, "EXECUTE-HALT");
        assertTrue(CoarseRouteFactory.replayAnomalyGate.contains(head));

        // Real forward progress re-arms the gate, so a later independent anomaly emits again.
        CoarseRouteFactory.notifyHeadProgress(head);
        assertFalse(CoarseRouteFactory.replayAnomalyGate.contains(head));
        CoarseRouteFactory.emitReplayOnAnomaly(head, 1, "EXECUTE-HALT");
        assertTrue(CoarseRouteFactory.replayAnomalyGate.contains(head));

        CoarseRouteFactory.lastReplayByHead.remove(head);
        CoarseRouteFactory.replayAnomalyGate.remove(head);
    }

    private static ReplayRecord minimalRecord(UUID head) {
        return new ReplayRecord(new BlockPos(0, 72, 0), new BlockPos(64, 72, 0), head, Direction.EAST,
                null, -1, false, 63, 0.25, new java.util.TreeMap<>(), List.of(), List.of(),
                new java.util.TreeSet<>(), new java.util.TreeSet<>());
    }
}
