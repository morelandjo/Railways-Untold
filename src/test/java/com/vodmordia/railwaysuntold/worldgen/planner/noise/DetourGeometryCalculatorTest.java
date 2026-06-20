package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainSampler;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Characterizes {@link DetourGeometryCalculator#createDetourWaypoints} - the corridor-relative detour
 * corners around one obstacle. Travel is EAST (dir = (1,0)); the left perpendicular is then (0,-1), so a
 * left detour steps toward -Z and a right detour toward +Z. All corners carry the corridor-origin Y as a
 * placeholder (the route re-samples Y downstream). obstacleRadius=20, offset=30 throughout.
 */
class DetourGeometryCalculatorTest {

    private static final int RADIUS = 20;
    private static final int OFFSET = 30;

    @Test
    void flatTerrainObstacleAheadDetoursLeftWithAPreApproachCorner() {
        NoiseTerrainSampler flat = StubTerrainSampler.flat(64);
        BlockPos start = new BlockPos(0, 64, 0);
        // Obstacle dead ahead on the corridor: zero lateral offset -> tie -> goLeft (<=) wins.
        List<BlockPos> detour = DetourGeometryCalculator.createDetourWaypoints(
                flat, start, start, 100, 0, RADIUS, OFFSET, 1.0, 0.0);

        assertEquals(List.of(
                new BlockPos(80, 64, 0),    // pre-approach: forward to the approach line
                new BlockPos(80, 64, -30),  // approach: radius behind center, offset to -Z (left)
                new BlockPos(100, 64, -30), // bypass: alongside center, offset left
                new BlockPos(120, 64, -30)  // return: radius ahead of center, offset left
        ), detour);
    }

    @Test
    void higherTerrainOnTheLeftPushesTheDetourToTheRightSide() {
        // Left side-choice sample is (100,-30); make it a steep climb so the route picks the right side.
        NoiseTerrainSampler sampler = StubTerrainSampler.withHeights(
                (x, z) -> (x == 100 && z == -30) ? 90 : 64, StubTerrainSampler.SEA_LEVEL);
        BlockPos start = new BlockPos(0, 64, 0);
        List<BlockPos> detour = DetourGeometryCalculator.createDetourWaypoints(
                sampler, start, start, 100, 0, RADIUS, OFFSET, 1.0, 0.0);

        // Right detour -> bypass on +Z.
        BlockPos bypass = detour.get(detour.size() - 2);
        assertEquals(new BlockPos(100, 64, 30), bypass);
    }

    @Test
    void noPreApproachWhenCurrentIsAlreadyOnTheApproachLine() {
        NoiseTerrainSampler flat = StubTerrainSampler.flat(64);
        BlockPos start = new BlockPos(0, 64, 0);
        // current sits at the approach X (80) already, so forwardToApproach == 0 -> no pre-approach corner.
        BlockPos current = new BlockPos(80, 64, 0);
        List<BlockPos> detour = DetourGeometryCalculator.createDetourWaypoints(
                flat, start, current, 100, 0, RADIUS, OFFSET, 1.0, 0.0);

        assertEquals(List.of(
                new BlockPos(80, 64, -30),
                new BlockPos(100, 64, -30),
                new BlockPos(120, 64, -30)
        ), detour);
    }
}
