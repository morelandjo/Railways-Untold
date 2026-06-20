package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoutePlanner.PlanResult;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainSampler;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Permanent regression cases promoted from production {@code [REPLAY]} logs. Each
 * {@code golden/replay/<name>.replay} resource is a pasted record; this test parses it, rebuilds the
 * sampler with {@link ReplaySamplers#fromCapture}, re-runs {@link CoarseRoutePlanner#planRouteOffThread},
 * and asserts the route snapshot matches the committed {@code golden/replay/<name>.txt}. A diff is a
 * behavior change for a real reported scenario - a bug to investigate, not an improvement.
 *
 * To add a case: paste the log block around a reported route into a new {@code .replay} file under
 * {@code src/test/resources/golden/replay/} and run the tests; the golden is generated on first run.
 * Regenerate after an intended change with {@code -Dgolden.update=true} and review the diff.
 */
class ReplayCaseTest {

    private static final boolean UPDATE = Boolean.getBoolean("golden.update");
    private static final String GOLDEN_DIR = System.getProperty("golden.source.dir");

    @BeforeAll
    static void setUp() {
        PlannerTestConfig.bootstrapDefaults();
    }

    @TestFactory
    Stream<DynamicTest> replayCases() throws IOException {
        Path replayDir = replayDir();
        if (Files.notExists(replayDir)) {
            return Stream.of(DynamicTest.dynamicTest("no replay cases",
                    () -> { /* nothing committed yet - the harness is still exercised by the unit tests below */ }));
        }
        List<Path> cases;
        try (Stream<Path> files = Files.list(replayDir)) {
            cases = files.filter(p -> p.getFileName().toString().endsWith(".replay")).sorted().toList();
        }
        return cases.stream().map(p -> {
            String name = stripExtension(p.getFileName().toString());
            return DynamicTest.dynamicTest(name, () -> runCase(replayDir, name, p));
        });
    }

    private void runCase(Path replayDir, String name, Path replayFile) throws IOException {
        String record = ReplayCases.extractRecord(Files.readString(replayFile));
        ReplayRecord rec = ReplayRecord.parse(record);
        NoiseTerrainSampler sampler = ReplaySamplers.fromCapture(
                rec.heights, rec.oceanCoords, rec.waterCoords, rec.seaLevel);
        PlanResult result = CoarseRoutePlanner.planRouteOffThread(
                sampler, rec.start, rec.target, rec.headId, rec.headDir, rec.arrivalDir,
                rec.structures, null, rec.tracks, rec.descentHintY, null, null, List.of(), rec.isFromTrackTip);
        String actual = GoldenSnapshot.serialize(result);

        Path golden = replayDir.resolve(name + ".txt");
        if (UPDATE || Files.notExists(golden)) {
            Files.writeString(golden, actual);
        }
        assertEquals(Files.readString(golden), actual,
                () -> "Replay golden mismatch for '" + name + "'. If this change is intended, "
                        + "re-run with -Dgolden.update=true and review the diff before committing.");

        assertWellFormed(name, rec, result);
    }

    /**
     * Connectivity invariants asserted independently of the golden snapshot: a committed golden that
     * itself captured a disconnect must still fail loudly, not be frozen as expected. Every consecutive
     * segment pair must meet (no spatial gap), and a route off a track tip must open with a segment whose
     * start direction equals the head's direction - the first emitted segment is always built at the head
     * pose before any advance, so it connects tangentially even when it is a curve (the geometry behind
     * the retired {@code first-curve-off-tip} warn: a head that must turn off its tip can only do so with
     * a minimum-radius curve as its first segment, which is correct, not a defect).
     */
    private static void assertWellFormed(String name, ReplayRecord rec, PlanResult result) {
        List<com.vodmordia.railwaysuntold.worldgen.planner.PathSegment> segs =
                result.route().getPrecisionPath().segments;
        if (segs == null || segs.isEmpty()) {
            return;
        }
        for (int i = 0; i < segs.size() - 1; i++) {
            BlockPos end = segs.get(i).getEndPosition();
            BlockPos next = segs.get(i + 1).getStart();
            if (end != null && next != null) {
                assertEquals(end, next,
                        () -> "Replay '" + name + "' has a disconnected seam between segments "
                                + segs.get(0).type + " chain (gap at seam)");
            }
        }
        if (rec.isFromTrackTip) {
            assertEquals(rec.headDir, segs.get(0).getStartDirection(),
                    () -> "Replay '" + name + "' first segment does not connect tangentially to the track tip");
        }
    }

    /** The extractor must recover a clean record from a pasted log with the summary line and trailing noise. */
    @Test
    void extractRecordStripsSurroundingLogNoise() {
        String pasted = """
                [15:55:44] [Server thread/INFO] [co.vo.ra.RailwaysUntold/]: [REPLAY] head 3 start 0,72,0 target 8,72,0 (2 heights, 0 structures, 0 tracks, 0 ocean, 1 water)
                REPLAY v2
                start 0 72 0
                target 8 72 0
                headId 00000000-0000-0000-0000-000000000001
                headDir EAST
                arrivalDir -
                descentHintY -1
                isFromTrackTip false
                seaLevel 63
                maxSlopeRatio 0.25
                structures 0
                tracks 0
                oceans 0
                waters 1 4,0
                heights 2 0,0,72 8,0,72
                [15:55:44] [Server thread/INFO] [minecraft/GameTestServer]: next log line, ignored
                """;
        ReplayRecord rec = ReplayRecord.parse(ReplayCases.extractRecord(pasted));
        assertEquals(new BlockPos(0, 72, 0), rec.start);
        assertEquals(new BlockPos(8, 72, 0), rec.target);
        assertEquals(63, rec.seaLevel);
        assertEquals(Set.of(ReplayRecord.packKey(4, 0)), rec.waterCoords);
        assertTrue(rec.structures.isEmpty() && rec.tracks.isEmpty());
    }

    private static Path replayDir() {
        if (GOLDEN_DIR == null) {
            throw new IllegalStateException("golden.source.dir system property not set");
        }
        return Path.of(GOLDEN_DIR, "replay");
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }
}
