package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.CoarseWaypoint;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.WaypointType;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Characterizes {@link RoutePostProcessor#enforceSlopeConstraints} - the pin-aware elevation
 * band (raise toward higher neighbours, lower toward low pins) plus ramp-waypoint insertion -
 * directly on coarse waypoint lists. A diff is a behavior change to review.
 */
class SlopeConstraintGoldenTest {

    private static final boolean UPDATE = Boolean.getBoolean("golden.update");
    private static final String GOLDEN_DIR = System.getProperty("golden.source.dir");

    @BeforeAll
    static void setUp() {
        PlannerTestConfig.bootstrapDefaults();
    }

    @Test
    void steepClimb() throws IOException {
        // Flat approach then an 18-block cliff up at the final waypoint.
        verify("slope-constraint-climb", run(new int[]{72, 72, 72, 72, 72, 72, 72, 72, 90}));
    }

    @Test
    void steepDescent() throws IOException {
        // Flat approach then an 18-block drop at the final waypoint.
        verify("slope-constraint-descent", run(new int[]{90, 90, 90, 90, 90, 90, 90, 90, 72}));
    }

    @Test
    void twoCliffs() throws IOException {
        // Up cliff then a down cliff - exercises both spread directions in one route.
        verify("slope-constraint-two-cliffs", run(new int[]{72, 72, 72, 88, 88, 88, 88, 72, 72}));
    }

    // --- helpers ---

    private List<CoarseWaypoint> run(int[] ys) {
        List<CoarseWaypoint> wps = new ArrayList<>();
        for (int i = 0; i < ys.length; i++) {
            wps.add(new CoarseWaypoint(new BlockPos(i * CoarseRoutePlanner.SAMPLE_INTERVAL, ys[i], 0),
                    ys[i], WaypointType.TERRAIN_FOLLOW));
        }
        return RoutePostProcessor.enforceSlopeConstraints(wps);
    }

    private void verify(String scenario, List<CoarseWaypoint> waypoints) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("== WAYPOINTS (").append(waypoints.size()).append(") ==\n");
        for (int i = 0; i < waypoints.size(); i++) {
            CoarseWaypoint wp = waypoints.get(i);
            sb.append(String.format("WP[%d] (%d,%d,%d) advisedY=%d type=%s\n",
                    i, wp.position().getX(), wp.position().getY(), wp.position().getZ(),
                    wp.advisedTrackY(), wp.type()));
        }
        String actual = sb.toString();

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
