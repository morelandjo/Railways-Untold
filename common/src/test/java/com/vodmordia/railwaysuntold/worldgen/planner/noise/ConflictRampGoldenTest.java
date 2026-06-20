package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.CoarseWaypoint;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.WaypointType;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.RouteConflictAnalyzer.ConflictType;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.RouteConflictAnalyzer.RouteConflict;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.RouteConflictResolver.Resolution;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.RouteConflictResolver.WaypointAdjustment;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Characterization (golden) tests for the two crossing-ramp sites:
 * RouteConflictResolver.resolveCrossing (route-vs-route) and
 * PlacedTrackConflictDetector.buildCrossingResolution (route-vs-placed-track).
 *
 * Both are pure given their inputs - the live world only decides which crossings occur, not
 * how the ramp Y is computed - so the ramp arithmetic is exercised here with synthetic routes
 * and crossing indices that hit every branch (overpass/underpass, naturally-separated, and
 * ramp clamping at route ends).
 */
class ConflictRampGoldenTest {

    private static final UUID MAIN = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID HIGH = UUID.fromString("00000000-0000-0000-0000-000000000009");
    private static final boolean UPDATE = Boolean.getBoolean("golden.update");
    private static final String GOLDEN_DIR = System.getProperty("golden.source.dir");

    @BeforeAll
    static void setUp() {
        PlannerTestConfig.bootstrapDefaults();
    }

    /** Straight east-bound route at constant Y, SAMPLE_INTERVAL spacing. */
    private static CoarseRoute straightRoute(UUID id, int count, int y) {
        List<CoarseWaypoint> wps = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            wps.add(new CoarseWaypoint(new BlockPos(i * 8, y, 0), y, WaypointType.TERRAIN_FOLLOW));
        }
        return new CoarseRoute(id, wps);
    }

    private static RouteConflict crossing(int crossIdx) {
        return new RouteConflict(new BlockPos(crossIdx * 8, 72, 0), MAIN, OTHER,
                ConflictType.CROSSING, crossIdx, crossIdx);
    }

    @Test
    void resolverRamp() throws IOException {
        StringBuilder sb = new StringBuilder();

        // |yDiff| <= 2 -> deterministic over/under by UUID. MAIN < OTHER, so the resolving MAIN
        // route crosses UNDER with a downward ramp around the crossing.
        sb.append(resolverCase("similar-Y lower-UUID under+ramp (crossIdx=4)", straightRoute(MAIN, 9, 72),
                straightRoute(OTHER, 9, 72), crossing(4)));
        // Same crossing resolved by the higher-UUID head -> crosses OVER with an upward ramp.
        sb.append(resolverCase("similar-Y higher-UUID over+ramp (crossIdx=4)", straightRoute(HIGH, 9, 72),
                straightRoute(OTHER, 9, 72),
                new RouteConflict(new BlockPos(32, 72, 0), HIGH, OTHER, ConflictType.CROSSING, 4, 4)));
        // This route already above -> naturally separated, no adjustments.
        sb.append(resolverCase("above -> natural overpass (crossIdx=4)", straightRoute(MAIN, 9, 72),
                straightRoute(OTHER, 9, 60), crossing(4)));
        // This route below -> underpass, no adjustments.
        sb.append(resolverCase("below -> natural underpass (crossIdx=4)", straightRoute(MAIN, 9, 72),
                straightRoute(OTHER, 9, 85), crossing(4)));
        // Crossing near the route start exercises ramp clamping (rampStart=0).
        sb.append(resolverCase("similar-Y lower-UUID under, ramp clamped at start (crossIdx=1)", straightRoute(MAIN, 9, 72),
                straightRoute(OTHER, 9, 72), crossing(1)));

        verify("conflict-resolver-ramp", sb.toString());
    }

    @Test
    void placedDetectorRamp() throws IOException {
        StringBuilder sb = new StringBuilder();
        CoarseRoute route = straightRoute(MAIN, 9, 72); // y=72, waypoints every 8 blocks (crossIdx*8 = run before crossing)

        // The collision-shape tree: at-grade -> merge (no resolution); separated-within-band -> ramp
        // over/under if it fits the run before the crossing, else merge; comfortably clear -> no-op marker.

        // At grade (route Y within the track's vertical extent ±2). A mid-route crossing - the head
        // continues well past it - ramps OVER/UNDER when a ramp fits, so the head crosses instead of
        // dead-ending into the line; if no ramp fits it merges. buildCrossingResolution -> ramp or null.
        sb.append(placedCase("at-grade mid-route, no ramp room -> merge (crossIdx=4, track 70-74)", route, 4, 70, 74));
        sb.append(placedCase("at-grade mid-route, under-ramp fits -> underpass (crossIdx=4, track 73-77)", route, 4, 73, 77));
        // Separated within the clearance band WITH room to ramp -> lift OVER / drop UNDER (adj > 0).
        sb.append(placedCase("separated above, room -> overpass ramp (crossIdx=4, track 60-68)", route, 4, 60, 68));
        sb.append(placedCase("separated below, room -> underpass ramp (crossIdx=4, track 76-84)", route, 4, 76, 84));
        // Comfortably clear of the AABB -> resolution marker, no adjustment.
        sb.append(placedCase("sufficient clearance above (crossIdx=4, track 55-60)", route, 4, 55, 60));
        sb.append(placedCase("sufficient clearance below (crossIdx=4, track 85-90)", route, 4, 85, 90));
        // Separated but the crossing is too close to ramp clear within slope -> merge (null).
        sb.append(placedCase("separated, no room -> merge (crossIdx=1, track 60-68)", route, 1, 60, 68));

        verify("conflict-placed-ramp", sb.toString());
    }

    @Test
    void placedDetectorForcedRamp() throws IOException {
        StringBuilder sb = new StringBuilder();
        CoarseRoute route = straightRoute(MAIN, 13, 72);

        // Forced crossing: the at-grade merge proved impossible at placement (e.g. the blocking line is
        // met at a 45° diagonal that no tangent junction can join), so ramp clear instead of merging.
        // Route y72 vs track 70-74 is at-grade; the ramp must clear by vertSep (7), so it drops to 62
        // (under) or climbs to 82 (over) - a 10-block change needing run 40+ at the 1:4 slope. crossIdx=7
        // (run 56) has room; crossIdx=1 (run 8) does not. Prefer UNDER, fall back to OVER.
        sb.append(placedCase("forced at-grade, under-first, room -> underpass (crossIdx=7, track 70-74)",
                route, 7, 70, 74, true, true));
        sb.append(placedCase("forced at-grade, over preferred, room -> overpass (crossIdx=7, track 70-74)",
                route, 7, 70, 74, true, false));
        sb.append(placedCase("forced at-grade, no room -> no resolution (crossIdx=1, track 70-74)",
                route, 1, 70, 74, true, true));

        verify("conflict-placed-forced-ramp", sb.toString());
    }

    // --- helpers ---

    private static String resolverCase(String label, CoarseRoute route, CoarseRoute other,
                                       RouteConflict conflict) {
        CoarseRouteRegistry registry = new CoarseRouteRegistry();
        registry.registerRoute(OTHER, other);
        List<Resolution> resolutions =
                RouteConflictResolver.resolveConflicts(route, List.of(conflict), registry);
        return serialize(label, resolutions);
    }

    private static String placedCase(String label, CoarseRoute route, int crossIdx,
                                     int trackBottom, int trackTop) {
        return placedCase(label, route, crossIdx, trackBottom, trackTop, false, false);
    }

    private static String placedCase(String label, CoarseRoute route, int crossIdx,
                                     int trackBottom, int trackTop, boolean force, boolean preferUnder) {
        int[] achievableY = PlacedTrackConflictDetector.computeAchievableY(route.getWaypoints());
        Resolution r = PlacedTrackConflictDetector.buildCrossingResolution(
                route.getWaypoints(), achievableY, crossIdx, trackBottom, trackTop, force, preferUnder);
        return serialize(label, r == null ? List.of() : List.of(r));
    }

    private static String serialize(String label, List<Resolution> resolutions) {
        StringBuilder sb = new StringBuilder();
        sb.append("== ").append(label).append(" (").append(resolutions.size()).append(") ==\n");
        for (Resolution r : resolutions) {
            sb.append(String.format("RES type=%s crossing=%s loc=%s adj=%d\n",
                    r.conflictType(), r.crossingResolution(), pos(r.conflictLocation()),
                    r.adjustments().size()));
            for (WaypointAdjustment a : r.adjustments()) {
                sb.append(String.format("  ADJ idx=%d newY=%d type=%s\n",
                        a.waypointIndex(), a.newAdvisedY(), a.newType()));
            }
        }
        return sb.toString();
    }

    private static String pos(BlockPos p) {
        return p == null ? "null" : "(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")";
    }

    private void verify(String scenario, String actual) throws IOException {
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
                () -> "Golden mismatch for '" + scenario + "'. If intended, re-run with "
                        + "-Dgolden.update=true and review the diff before committing.");
    }
}
