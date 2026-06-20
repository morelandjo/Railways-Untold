package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.CoarseWaypoint;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.WaypointType;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.RouteConflictResolver.CrossingResolution;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.RouteConflictResolver.Resolution;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.RouteConflictResolver.WaypointAdjustment;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gates the placed-track crossing resolver - the planner-level grade separation that lifts a route over
 * an existing track. The regression it pins: a route descending from a high head toward low terrain is
 * still near the head's Y at a nearby crossing (slope-limited), so judging by the advised terrain Y
 * wrongly reports a clean under-pass while the placed track actually blocks it. Detection therefore uses
 * the slope-ACHIEVABLE Y and the placed track's LOCAL rail Y at the crossing (matching the runtime
 * collision check), and forces an overpass when the route is in the band.
 */
class PlacedTrackConflictDetectorTest {

    // verticalExpansion 6 + 1 = 7 separation; a track top at y85 needs the route at >= 85+7 = 92, so the
    // overpass target is 85 + 7 + 1 = 93.
    private static final int TRACK_BOTTOM = 71;
    private static final int TRACK_TOP = 85;

    @BeforeAll
    static void setUp() {
        PlannerTestConfig.bootstrapDefaults();
    }

    /** A straight south-running route, one waypoint every 8 blocks, all at the given advised Y. */
    private static List<CoarseWaypoint> route(int advisedY) {
        List<CoarseWaypoint> wps = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            wps.add(new CoarseWaypoint(new BlockPos(1952, advisedY, 3326 + i * 8), advisedY, WaypointType.TERRAIN_FOLLOW));
        }
        return wps;
    }

    private static int adjustmentFor(Resolution r, int idx) {
        for (WaypointAdjustment a : r.adjustments()) {
            if (a.waypointIndex() == idx) return a.newAdvisedY();
        }
        return Integer.MIN_VALUE;
    }

    @Test
    void routeCaughtInTheTrackBandIsLiftedOverIt() {
        List<CoarseWaypoint> wps = route(78);
        int crossIdx = 5;
        // The route is at y92 at the crossing (descending from a high head; can't be at the low terrain
        // Y yet). 92 is within the track band's clearance envelope (top 85 + 7 = 92), so it collides.
        int[] achievableY = new int[wps.size()];
        java.util.Arrays.fill(achievableY, 92);

        Resolution r = PlacedTrackConflictDetector.buildCrossingResolution(
                wps, achievableY, crossIdx, TRACK_BOTTOM, TRACK_TOP, false, false);

        assertEquals(CrossingResolution.OVERPASS, r.crossingResolution(), "descending-through-band -> overpass");
        assertEquals(93, adjustmentFor(r, crossIdx),
                "crossing waypoint must be lifted to trackTop + vertSep + 1 = 93 to clear the track");
        assertTrue(adjustmentFor(r, crossIdx) - TRACK_TOP >= 7, "lifted Y clears the track by the separation");
    }

    @Test
    void routeComfortablyAboveIsLeftAlone() {
        List<CoarseWaypoint> wps = route(99);
        int[] achievableY = new int[wps.size()];
        java.util.Arrays.fill(achievableY, 99); // 99 - 85 = 14 >= 7

        Resolution r = PlacedTrackConflictDetector.buildCrossingResolution(
                wps, achievableY, 5, TRACK_BOTTOM, TRACK_TOP, false, false);

        assertEquals(CrossingResolution.OVERPASS, r.crossingResolution());
        assertTrue(r.adjustments().isEmpty(), "already clear above -> no ramp");
    }

    @Test
    void routeComfortablyBelowIsLeftAlone() {
        List<CoarseWaypoint> wps = route(60);
        int[] achievableY = new int[wps.size()];
        java.util.Arrays.fill(achievableY, 60); // 71 - 60 = 11 >= 7

        Resolution r = PlacedTrackConflictDetector.buildCrossingResolution(
                wps, achievableY, 5, TRACK_BOTTOM, TRACK_TOP, false, false);

        assertEquals(CrossingResolution.UNDERPASS, r.crossingResolution());
        assertTrue(r.adjustments().isEmpty(), "already clear below -> no ramp");
    }

    @Test
    void perpendicularCrossingResolvesAgainstLocalRailYNotClusterAabb() {
        // Head 1's scenario (seed -3475865192283585385): a south head at ~y83 meets a long east-west line
        // that descends across its run - whole-cluster AABB y66..103, but locally only ~y81 at the crossing.
        // The head is ~32 blocks out (crossIdx 4, 8-block spacing -> rampRoom = 0.25*32 = 8).
        List<CoarseWaypoint> wps = route(83);
        int crossIdx = 4;
        int[] achievableY = new int[wps.size()];
        java.util.Arrays.fill(achievableY, 83);

        // Old behavior: judged against the whole-cluster AABB - clearing y103 (climb 28) or y66 (drop 25)
        // can't fit 32 blocks -> null -> the route stays at-grade and dead-ends merging a perpendicular line.
        Resolution viaAabb = PlacedTrackConflictDetector.buildCrossingResolution(
                wps, achievableY, crossIdx, 66, 103, false, false);
        assertNull(viaAabb, "whole-cluster AABB makes grade separation look infeasible -> at-grade merge (the bug)");

        // Fixed behavior: judged against the LOCAL rail Y (81), a small overpass to 81+7+1=89 (climb 6)
        // fits the 32-block run, so the head grade-separates instead of dead-ending.
        Resolution viaLocal = PlacedTrackConflictDetector.buildCrossingResolution(
                wps, achievableY, crossIdx, 81, 81, false, false);
        assertEquals(CrossingResolution.OVERPASS, viaLocal.crossingResolution(),
                "local rail Y makes a small overpass feasible -> grade-separate, not merge");
        assertEquals(89, adjustmentFor(viaLocal, crossIdx), "overpass clears the local rail by the separation");
    }

    @Test
    void achievableYClampsAnUnreachableTerrainDive() {
        // Head at y99, then the advised Y dives to terrain ~63 within ~33 blocks (slope > 1.0). The
        // achievable Y can only drop at the slope limit, so at the crossing it is far above 63 - which
        // is exactly why judging by the advised Y missed the collision.
        List<CoarseWaypoint> wps = new ArrayList<>();
        wps.add(new CoarseWaypoint(new BlockPos(1952, 99, 3326), 99, WaypointType.TERRAIN_FOLLOW));
        for (int i = 1; i < 8; i++) {
            wps.add(new CoarseWaypoint(new BlockPos(1952, 63, 3326 + i * 8), 63, WaypointType.TERRAIN_FOLLOW));
        }
        int[] achievableY = PlacedTrackConflictDetector.computeAchievableY(wps);

        assertEquals(99, achievableY[0]);
        // At the 2nd waypoint (8 blocks on), the drop is capped near maxSlope*8, nowhere near 63.
        assertTrue(achievableY[1] > 90, "8 blocks from a y99 head the route cannot already be at y63, got " + achievableY[1]);
        assertTrue(achievableY[1] >= achievableY[2], "the route descends monotonically toward the terrain target");
    }
}
