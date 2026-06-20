package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.planner.PlannedPath;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.CoarseWaypoint;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.WaypointType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the lateral-closure invariant: a cardinal run whose coarse waypoints drift a large distance
 * perpendicular to the travel axis must compile to a path that actually reaches that lateral offset - not
 * drop the drift and land short. This is the village-approach failure isolated from terrain: the coarse
 * route runs east while shifting ~96 blocks north onto the runway, and the compiled final pose must end on
 * that runway line, not 30-40 blocks short inside the village.
 */
class PrecisionRouteLateralDriftTest {

    @BeforeAll
    static void setUp() {
        PlannerTestConfig.bootstrapDefaults();
    }

    @Test
    void aLargeLateralDriftOnAUniformRunIsRealizedNotDropped() {
        // East-travelling run drifting from z=0 to z=-96 over 240 blocks (ratio 0.4), uniform type, then a
        // cardinal tangent east at z=-96. A single-type run already compiles correctly.
        List<CoarseWaypoint> waypoints = new ArrayList<>();
        for (int x = 0; x <= 240; x += 8) {
            waypoints.add(new CoarseWaypoint(new BlockPos(x, 72, -(x * 96 / 240)), 72, WaypointType.TERRAIN_FOLLOW));
        }
        for (int x = 248; x <= 320; x += 8) {
            waypoints.add(new CoarseWaypoint(new BlockPos(x, 72, -96), 72, WaypointType.TERRAIN_FOLLOW));
        }

        PlannedPath path = PrecisionRouteCompiler.compile(
                waypoints, new BlockPos(0, 72, 0), new BlockPos(320, 72, -96), Direction.EAST, false);
        assertTrue(path.valid, "compile produced an invalid path: " + path.invalidReason);
        assertTrue(path.finalPosition.getZ() <= -88,
                "uniform-run drift undershot: " + path.finalPosition);
    }

    @Test
    void aTypeFragmentedLateralDriftIsRealizedNotDropped() {
        // The real village-approach shape captured from seed 8554805573629861447, head 1: an east run that
        // drifts ~96 blocks north onto the runway, but the drift is fragmented by waypoint type (a TUNNEL
        // span, then TERRAIN runs split by interspersed FLAT_MAINTAIN). The fragments are each too short to
        // classify as a diagonal run, so the compiler dropped the lateral and landed at z=-35 (inside the
        // village) instead of the runway line z=-73. Y is flattened to 65 - only the lateral (Z) matters here.
        int[][] wp = {
            {104,24,0},{106,24,1},{107,24,1},{109,24,1},{110,24,1},{112,24,0},{120,24,0},{129,24,0},
            {137,24,0},{145,24,0},{149,24,1},{153,24,0},{162,24,0},{170,24,0},{178,24,0},{182,24,1},
            {186,24,2},{191,24,1},{195,24,3},{203,24,3},{211,24,3},{219,24,3},{228,24,3},{236,24,3},
            {244,24,2},{250,18,2},{255,13,2},{261,7,2},{267,1,2},{273,-5,2},{278,-10,2},{284,-16,2},
            {290,-22,2},{295,-27,2},{301,-33,2},{307,-39,2},{312,-44,0},{318,-50,0},{321,-53,1},{324,-56,0},
            {330,-62,0},{333,-64,1},{335,-67,0},{342,-71,0},{345,-73,1},{349,-73,0},{354,-73,1},{358,-73,0},
            {366,-73,3},{375,-73,3},{383,-73,3},{392,-73,3},{400,-73,3},{405,-73,1},{409,-73,0},{417,-73,0}
        };
        WaypointType[] types = {
            WaypointType.TERRAIN_FOLLOW, WaypointType.FLAT_MAINTAIN, WaypointType.TUNNEL, WaypointType.BRIDGE
        };
        List<CoarseWaypoint> waypoints = new ArrayList<>();
        for (int[] w : wp) {
            waypoints.add(new CoarseWaypoint(new BlockPos(w[0], 65, w[1]), 65, types[w[2]]));
        }

        PlannedPath path = PrecisionRouteCompiler.compile(
                waypoints, new BlockPos(104, 65, 24), new BlockPos(417, 65, -73), Direction.EAST, false);

        assertTrue(path.valid, "compile produced an invalid path: " + path.invalidReason);
        assertTrue(path.finalPosition.getZ() <= -65,
                "type-fragmented drift undershot: compiled final pose " + path.finalPosition
                        + " did not reach the runway line z=-73 (lateral dropped across fragmented runs)");
    }
}
