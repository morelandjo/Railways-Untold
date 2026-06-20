package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.track.SlopeValidator;
import com.vodmordia.railwaysuntold.worldgen.planner.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Permanent regression cases promoted from production {@code [COMPILE]} logs. Each
 * {@code golden/compile/<name>.compile} resource is a pasted record; this test parses it, re-runs
 * {@link PrecisionRouteCompiler#compile} on the exact captured waypoints (no world, no terrain), and
 * asserts the compiled path is well-formed: every segment's end meets the next segment's start
 * (no spatial gap - a disconnected track), no junction kinks past 90° ({@link RouteSeamInspector}),
 * and no curve below the minimum radius. The serialized segments are also pinned against a committed
 * {@code golden/compile/<name>.txt}; a diff is a behavior change for a real reported scenario.
 *
 * To add a case: paste the {@code [COMPILE]} log block around a reported route into a new
 * {@code .compile} file under {@code src/test/resources/golden/compile/} and run the tests; the golden
 * is generated on first run. Regenerate after an intended change with {@code -Dgolden.update=true}.
 */
class CompileCaseTest {

    private static final boolean UPDATE = Boolean.getBoolean("golden.update");
    private static final String GOLDEN_DIR = System.getProperty("golden.source.dir");

    @BeforeAll
    static void setUp() {
        PlannerTestConfig.bootstrapDefaults();
    }

    @TestFactory
    Stream<DynamicTest> compileCases() throws IOException {
        Path compileDir = compileDir();
        if (Files.notExists(compileDir)) {
            return Stream.of(DynamicTest.dynamicTest("no compile cases",
                    () -> { /* nothing committed yet - the harness is exercised by the unit tests below */ }));
        }
        List<Path> cases;
        try (Stream<Path> files = Files.list(compileDir)) {
            cases = files.filter(p -> p.getFileName().toString().endsWith(".compile")).sorted().toList();
        }
        return cases.stream().map(p -> {
            String name = stripExtension(p.getFileName().toString());
            return DynamicTest.dynamicTest(name, () -> runCase(compileDir, name, p));
        });
    }

    private void runCase(Path compileDir, String name, Path compileFile) throws IOException {
        CompileRecord rec = CompileRecord.parse(CompileCases.extractRecord(Files.readString(compileFile)));
        PlannedPath path = PrecisionRouteCompiler.compile(
                rec.waypoints, rec.start, rec.target, rec.headDir, rec.isFromTrackTip);

        String actual = serialize(path);
        Path golden = compileDir.resolve(name + ".txt");
        if (UPDATE || Files.notExists(golden)) {
            Files.writeString(golden, actual);
        }
        assertEquals(Files.readString(golden), actual,
                () -> "Compile golden mismatch for '" + name + "'. If this change is intended, "
                        + "re-run with -Dgolden.update=true and review the diff before committing.");

        // Well-formedness is asserted independently of the golden: a committed golden that itself
        // captured a disconnect must still fail loudly, not be frozen as expected.
        assertTrue(path.valid, () -> "compiled path is invalid: " + path.invalidReason);
        List<String> gaps = spatialGaps(path);
        assertTrue(gaps.isEmpty(), () -> "compiled route has disconnected segment seam(s): " + gaps);
        assertEquals(-1, RouteSeamInspector.findIllegalSeam(path),
                "compiled route has an illegal direction seam (>=90 deg kink with no absorbing curve)");
        List<String> tight = radiusViolations(path);
        assertTrue(tight.isEmpty(),
                () -> "compiled route has curve(s) tighter than the minimum radius "
                        + RailwaysUntoldConfig.getMinCurveRadius() + ": " + tight);
        List<String> steep = slopeViolations(path);
        assertTrue(steep.isEmpty(),
                () -> "compiled route has bezier(s) over the slope limit (the executor would clamp them "
                        + "at placement -> [BEZIER-CLAMP]): " + steep);
    }

    /**
     * One description per BezierSegment whose grade exceeds the config slope limit, measured exactly as
     * {@link com.vodmordia.railwaysuntold.worldgen.placement.handler.BezierTrackPlacementExecutor} does at
     * placement: Manhattan horizontal distance with {@link SlopeValidator#isValidSlope}. A compiled bezier
     * the executor would clamp is an upstream slope-clamp miss - [BEZIER-CLAMP] fires for it in-game.
     */
    private static List<String> slopeViolations(PlannedPath path) {
        List<String> violations = new ArrayList<>();
        for (PathSegment s : path.segments) {
            if (!(s instanceof BezierSegment)) {
                continue;
            }
            BlockPos a = s.getStart();
            BlockPos b = s.getEndPosition();
            if (a == null || b == null) {
                continue;
            }
            int horiz = Math.abs(b.getX() - a.getX()) + Math.abs(b.getZ() - a.getZ());
            int elev = b.getY() - a.getY();
            if (!SlopeValidator.isValidSlope(horiz, elev)) {
                violations.add(s.type + " elev=" + elev + " over " + horiz + " at " + a);
            }
        }
        return violations;
    }

    /** The extractor must recover a clean record from a pasted log with the summary line and trailing noise. */
    @Test
    void extractRecordStripsSurroundingLogNoise() {
        String pasted = """
                [15:33:11] [Server thread/INFO] [co.vo.ra.RailwaysUntold/]: [COMPILE] start 0,72,0 target 0,72,-16 dir NORTH fromTip true waypoints 3 -> segments 1 valid true
                COMPILE v1
                start 0 72 0
                target 0 72 -16
                headDir NORTH
                isFromTrackTip true
                waypoints 3 0,72,0,72,TERRAIN_FOLLOW,PREFERENCE 0,72,-8,72,FLAT_MAINTAIN,CONSTRAINT 0,72,-16,72,TERRAIN_FOLLOW,PREFERENCE
                [15:33:11] [Server thread/INFO] [minecraft/GameTestServer]: next log line, ignored
                """;
        CompileRecord rec = CompileRecord.parse(CompileCases.extractRecord(pasted));
        assertEquals(new BlockPos(0, 72, 0), rec.start);
        assertEquals(new BlockPos(0, 72, -16), rec.target);
        assertEquals(Direction.NORTH, rec.headDir);
        assertTrue(rec.isFromTrackTip);
        assertEquals(3, rec.waypoints.size());
        assertEquals(new BlockPos(0, 72, -8), rec.waypoints.get(1).position());
    }

    /** A serialize -> parse round trip preserves every field the compiler reads. */
    @Test
    void roundTripPreservesInputs() {
        CompileRecord original = CompileRecord.parse(CompileCases.extractRecord("""
                COMPILE v1
                start 10 64 -20
                target 10 64 -100
                headDir SOUTH
                isFromTrackTip false
                waypoints 2 10,64,-20,64,TERRAIN_FOLLOW,PREFERENCE 10,64,-100,64,BRIDGE,CONSTRAINT
                """));
        CompileRecord reparsed = CompileRecord.parse(original.serialize());
        assertEquals(original.start, reparsed.start);
        assertEquals(original.target, reparsed.target);
        assertEquals(original.headDir, reparsed.headDir);
        assertEquals(original.isFromTrackTip, reparsed.isFromTrackTip);
        assertEquals(original.waypoints.size(), reparsed.waypoints.size());
        assertEquals(original.waypoints.get(1).type(), reparsed.waypoints.get(1).type());
        assertEquals(original.waypoints.get(1).yBasis(), reparsed.waypoints.get(1).yBasis());
    }

    /** One description per junction where segment i's end does not coincide with segment i+1's start. */
    private static List<String> spatialGaps(PlannedPath path) {
        List<String> gaps = new ArrayList<>();
        List<PathSegment> segs = path.segments;
        for (int i = 0; i < segs.size() - 1; i++) {
            BlockPos end = segs.get(i).getEndPosition();
            BlockPos next = segs.get(i + 1).getStart();
            if (end != null && next != null && !end.equals(next)) {
                gaps.add("seam " + i + ": " + segs.get(i).type + " ends " + end
                        + " but " + segs.get(i + 1).type + " starts " + next);
            }
        }
        return gaps;
    }

    /** One description per curve whose radius is below the configured minimum. */
    private static List<String> radiusViolations(PlannedPath path) {
        int min = RailwaysUntoldConfig.getMinCurveRadius();
        List<String> violations = new ArrayList<>();
        for (PathSegment s : path.segments) {
            Integer radius = curveRadius(s);
            if (radius != null && radius < min) {
                violations.add(s.type + " r=" + radius + " at " + s.getStart());
            }
        }
        return violations;
    }

    private static Integer curveRadius(PathSegment s) {
        if (s instanceof CurveSegment c) {
            return c.radius;
        }
        if (s instanceof DiagonalCurveSegment d) {
            return d.radius;
        }
        if (s instanceof SCurve45Segment sc) {
            return sc.radius;
        }
        return null;
    }

    private static String serialize(PlannedPath path) {
        StringBuilder sb = new StringBuilder();
        sb.append("== PLANNED PATH ==\n");
        sb.append(String.format("valid=%s finalPos=%s finalDir=%s invalidCode=%s invalidReason=%s\n",
                path.valid, pos(path.finalPosition), dir(path.finalDirection),
                path.invalidReasonCode, path.invalidReason));
        sb.append("== PATH SEGMENTS (").append(path.segments.size()).append(") ==\n");
        for (int i = 0; i < path.segments.size(); i++) {
            PathSegment s = path.segments.get(i);
            sb.append(String.format("SEG[%d] %s type=%s start=%s startDir=%s end=%s endDir=%s | %s\n",
                    i, s.getClass().getSimpleName(), s.type,
                    pos(s.getStart()), dir(s.getStartDirection()),
                    pos(s.getEndPosition()), dir(s.getEndDirection()), s));
        }
        return sb.toString();
    }

    private static String pos(BlockPos p) {
        return p == null ? "null" : "(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")";
    }

    private static String dir(Direction d) {
        return d == null ? "null" : d.name();
    }

    private static Path compileDir() {
        if (GOLDEN_DIR == null) {
            throw new IllegalStateException("golden.source.dir system property not set");
        }
        return Path.of(GOLDEN_DIR, "compile");
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }
}
