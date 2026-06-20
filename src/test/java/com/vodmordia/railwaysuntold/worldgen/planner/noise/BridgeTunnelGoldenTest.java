package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.CoarseWaypoint;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.WaypointType;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainProfile;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainSampler;
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
 * Characterization (golden) test for RouteBridgeTunnelDetector's Y assignment over a span.
 * applyBridgeDetection / applyTunnelDetection mutate a waypoint list in place given a profile
 * built from it, so the resulting advised-Y sequence is snapshotted directly. Scenarios use
 * dips/peaks with differing rim heights so the BT2 change (interpolate a grade between the rims
 * vs flatten to the higher/lower rim) is a reviewed diff.
 */
class BridgeTunnelGoldenTest {

    private static final boolean UPDATE = Boolean.getBoolean("golden.update");
    private static final String GOLDEN_DIR = System.getProperty("golden.source.dir");
    private static final int INTERVAL = CoarseRoutePlanner.SAMPLE_INTERVAL;

    @BeforeAll
    static void setUp() {
        PlannerTestConfig.bootstrapDefaults();
    }

    @Test
    void bridgeTunnelSpans() throws IOException {
        StringBuilder sb = new StringBuilder();

        // Dip between rims of differing height (80 left, 90 right) -> bridge span.
        sb.append(bridgeCase("bridge over dip, rims 80/90", 80,
                80, 80, 64, 56, 64, 90, 90));

        // Peak whose far portal exits lower than the entry (descending through a mountain),
        // so the two portals differ (70 in, 55 out) -> tunnel span with a real grade.
        sb.append(tunnelCase("tunnel through peak, portals 70/55", 70,
                70, 70, 100, 110, 100, 60, 55));

        verify("bridge-tunnel-spans", sb.toString());
    }

    @Test
    void waterBridgeSpan() throws IOException {
        // A river dip (y=60, below sea level 63) at waypoints 5-7 (x=40..56). applyWaterBridgeDetection
        // raises the water columns to seaLevel + bridgeWaterDistance, and - the ±2 shoreline margin BT3
        // protects - also bridges the two dry waypoints on each side, so a coarse-sampled shoreline isn't
        // left as underwater track. (The water pass keys off isLikelyWater, not heights, hence withWater.)
        List<CoarseWaypoint> wps = waypoints(72, 72, 72, 72, 72, 60, 60, 60, 72, 72, 72, 72);
        NoiseTerrainSampler sampler = StubTerrainSampler.withWater(
                (x, z) -> StubTerrainSampler.SEA_LEVEL, StubTerrainSampler.SEA_LEVEL,
                (x, z) -> x >= 40 && x <= 56);
        RouteBridgeTunnelDetector.applyWaterBridgeDetection(wps, sampler);
        verify("water-bridge-span", serialize("WATER river crossing", wps));
    }

    // --- helpers ---

    private static List<CoarseWaypoint> waypoints(int... advisedYs) {
        List<CoarseWaypoint> wps = new ArrayList<>(advisedYs.length);
        for (int i = 0; i < advisedYs.length; i++) {
            wps.add(new CoarseWaypoint(
                    new BlockPos(i * INTERVAL, advisedYs[i], 0), advisedYs[i], WaypointType.TERRAIN_FOLLOW));
        }
        return wps;
    }

    private static String bridgeCase(String label, int currentTrackY, int... advisedYs) {
        List<CoarseWaypoint> wps = waypoints(advisedYs);
        NoiseTerrainProfile profile = RouteBridgeTunnelDetector.buildProfileFromWaypoints(wps);
        RouteBridgeTunnelDetector.applyBridgeDetection(wps, profile, currentTrackY);
        return serialize("BRIDGE " + label, wps);
    }

    private static String tunnelCase(String label, int currentTrackY, int... advisedYs) {
        List<CoarseWaypoint> wps = waypoints(advisedYs);
        NoiseTerrainProfile profile = RouteBridgeTunnelDetector.buildProfileFromWaypoints(wps);
        RouteBridgeTunnelDetector.applyTunnelDetection(wps, profile, currentTrackY);
        return serialize("TUNNEL " + label, wps);
    }

    private static String serialize(String label, List<CoarseWaypoint> wps) {
        StringBuilder sb = new StringBuilder();
        sb.append("== ").append(label).append(" ==\n");
        for (CoarseWaypoint wp : wps) {
            sb.append(String.format("  y=%d type=%s%n", wp.advisedTrackY(), wp.type()));
        }
        return sb.toString();
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
