package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.CoarseWaypoint;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.WaypointType;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.YBasis;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Characterizes the two coarse-waypoint smoothing passes directly on synthetic waypoint lists,
 * closing the gap left by the full-route goldens (which exercised these passes only indirectly):
 *
 * - {@link RoutePostProcessor#smoothLateralOscillations}: the X/Z zigzag (4-point) and spike
 *   (3-point) projection, plus the guards that keep intentional curves and constrained / non-
 *   TERRAIN_FOLLOW waypoints untouched.
 * - {@link RoutePostProcessor#smoothElevationOscillations}: the radius-3 weighted-average Y filter,
 *   the ±2 per-pass correction clamp, and that a constrained Y stays fixed while still anchoring
 *   its neighbours' average.
 *
 * A diff is a behavior change to review. No Minecraft bootstrap needed - these passes touch only
 * BlockPos arithmetic.
 */
class SmoothingGoldenTest {

    private static final boolean UPDATE = Boolean.getBoolean("golden.update");
    private static final String GOLDEN_DIR = System.getProperty("golden.source.dir");

    // --- lateral smoothing ---

    @Test
    void lateralZigzag() throws IOException {
        // Two interior points deviate to opposite sides of the a->d line: pass 1 projects both.
        verify("smooth-lateral-zigzag", RoutePostProcessor.smoothLateralOscillations(
                lateral(new int[]{0, 6, -6, 0, 0, 0})));
    }

    @Test
    void lateralSpike() throws IOException {
        // A single z=0,4,0 spike with aligned neighbours: pass 1 misses it, pass 2 projects it.
        verify("smooth-lateral-spike", RoutePostProcessor.smoothLateralOscillations(
                lateral(new int[]{0, 0, 4, 0, 0})));
    }

    @Test
    void lateralPreservesIntentionalCurveAndGuardedPoints() throws IOException {
        // A 20-block deviation exceeds maxDeviationThreshold (10) and is kept; a CURVE_AROUND point
        // and a CONSTRAINT point are both ineligible and are kept regardless of deviation.
        List<CoarseWaypoint> wps = new ArrayList<>();
        wps.add(wp(0, 72, 0, WaypointType.TERRAIN_FOLLOW, YBasis.PREFERENCE));
        wps.add(wp(1, 72, 20, WaypointType.TERRAIN_FOLLOW, YBasis.PREFERENCE));   // big deviation, kept
        wps.add(wp(2, 72, 0, WaypointType.TERRAIN_FOLLOW, YBasis.PREFERENCE));
        wps.add(wp(3, 72, 8, WaypointType.CURVE_AROUND, YBasis.PREFERENCE));      // not smoothable, kept
        wps.add(wp(4, 72, 0, WaypointType.TERRAIN_FOLLOW, YBasis.PREFERENCE));
        wps.add(wp(5, 72, 5, WaypointType.TERRAIN_FOLLOW, YBasis.CONSTRAINT));    // constrained, kept
        wps.add(wp(6, 72, 0, WaypointType.TERRAIN_FOLLOW, YBasis.PREFERENCE));
        verify("smooth-lateral-preserved", RoutePostProcessor.smoothLateralOscillations(wps));
    }

    // --- elevation smoothing ---

    @Test
    void elevationNoise() throws IOException {
        // 2-4 block bounce on otherwise gentle terrain: the weighted average flattens the noise.
        verify("smooth-elevation-noise", RoutePostProcessor.smoothElevationOscillations(
                elevation(new int[]{72, 74, 72, 75, 73, 74, 72})));
    }

    @Test
    void elevationClampsLargeCorrection() throws IOException {
        // An 8-block spike can only be pulled toward the average by the ±2 maxCorrection per pass.
        verify("smooth-elevation-clamp", RoutePostProcessor.smoothElevationOscillations(
                elevation(new int[]{72, 72, 72, 80, 72, 72, 72})));
    }

    @Test
    void elevationKeepsConstraintButAnchorsNeighbours() throws IOException {
        // The index-3 pin stays at 76; its movable neighbours still average against it.
        List<CoarseWaypoint> wps = new ArrayList<>();
        int[] ys = {72, 76, 72, 76, 72, 76, 72};
        for (int i = 0; i < ys.length; i++) {
            YBasis basis = i == 3 ? YBasis.CONSTRAINT : YBasis.PREFERENCE;
            wps.add(wp(i, ys[i], 0, WaypointType.TERRAIN_FOLLOW, basis));
        }
        verify("smooth-elevation-constraint", RoutePostProcessor.smoothElevationOscillations(wps));
    }

    // --- helpers ---

    private static CoarseWaypoint wp(int xStep, int y, int z, WaypointType type, YBasis basis) {
        return new CoarseWaypoint(
                new BlockPos(xStep * CoarseRoutePlanner.SAMPLE_INTERVAL, y, z), y, type, basis);
    }

    /** A flat (Y=72) line advancing along X with the given lateral Z offsets, all TERRAIN_FOLLOW. */
    private List<CoarseWaypoint> lateral(int[] zs) {
        List<CoarseWaypoint> wps = new ArrayList<>();
        for (int i = 0; i < zs.length; i++) {
            wps.add(wp(i, 72, zs[i], WaypointType.TERRAIN_FOLLOW, YBasis.PREFERENCE));
        }
        return wps;
    }

    /** A straight (Z=0) line advancing along X with the given advised Y values, all TERRAIN_FOLLOW. */
    private List<CoarseWaypoint> elevation(int[] ys) {
        List<CoarseWaypoint> wps = new ArrayList<>();
        for (int i = 0; i < ys.length; i++) {
            wps.add(wp(i, ys[i], 0, WaypointType.TERRAIN_FOLLOW, YBasis.PREFERENCE));
        }
        return wps;
    }

    private void verify(String scenario, List<CoarseWaypoint> waypoints) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("== WAYPOINTS (").append(waypoints.size()).append(") ==\n");
        for (int i = 0; i < waypoints.size(); i++) {
            CoarseWaypoint wp = waypoints.get(i);
            sb.append(String.format("WP[%d] (%d,%d,%d) advisedY=%d type=%s basis=%s\n",
                    i, wp.position().getX(), wp.position().getY(), wp.position().getZ(),
                    wp.advisedTrackY(), wp.type(), wp.yBasis()));
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
