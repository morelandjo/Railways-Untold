package com.vodmordia.railwaysuntold.worldgen.placement;

import com.vodmordia.railwaysuntold.worldgen.planner.noise.PlannerTestConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the starting-station origin guard against the spawn-village burial reproduced on seed
 * 6804434896546404492: the origin landed at (104, 56) on top of a plains village whose footprint
 * sprawls x 78..146 / z -54..91, so the head left the station and drove north straight through the
 * village body. The guard shifts the origin perpendicular to the EAST track, clear of the footprint,
 * choosing the side with the lower surface so it does not nudge the station into the mountain south
 * of the village (which buried it ~49 blocks deep when the side was picked blindly).
 */
class StationOriginNudgeTest {

    // Union of the village piece bounds from the [REPLAY] record for that seed.
    private static final BoundingBox SPAWN_VILLAGE = new BoundingBox(78, 62, -54, 146, 100, 91);

    private static final ToIntFunction<BlockPos> FLAT = pos -> 64;

    @BeforeAll
    static void setUp() {
        PlannerTestConfig.bootstrapDefaults();
    }

    @Test
    void picksTheLowerSurfaceSideAwayFromTheMountain() {
        // The real seed: a mountain south of the village (high z), low ground north (low z). The
        // origin sits south of the village center, but the nudge must still exit NORTH because the
        // south side is a mountain it would be buried under.
        BlockPos origin = new BlockPos(104, 100, 56);
        ToIntFunction<BlockPos> terrain = pos -> pos.getZ() > SPAWN_VILLAGE.maxZ() ? 114 : 80;

        BlockPos nudged = StationOriginNudge.resolve(origin, Direction.EAST, List.of(SPAWN_VILLAGE), terrain);

        assertEquals(origin.getX(), nudged.getX(), "along-track coordinate must not move");
        assertTrue(nudged.getZ() < SPAWN_VILLAGE.minZ(),
                "must exit north onto the low ground, not south into the mountain");
        assertTrue(SPAWN_VILLAGE.minZ() - nudged.getZ() >= StationOriginNudge.CLEARANCE_MARGIN);
    }

    @Test
    void picksTheSouthSideWhenItIsLower() {
        BlockPos origin = new BlockPos(104, 100, 56);
        ToIntFunction<BlockPos> terrain = pos -> pos.getZ() < SPAWN_VILLAGE.minZ() ? 120 : 70;

        BlockPos nudged = StationOriginNudge.resolve(origin, Direction.EAST, List.of(SPAWN_VILLAGE), terrain);

        assertTrue(nudged.getZ() > SPAWN_VILLAGE.maxZ(),
                "must exit south when that is the lower side");
        assertTrue(nudged.getZ() - SPAWN_VILLAGE.maxZ() >= StationOriginNudge.CLEARANCE_MARGIN);
    }

    @Test
    void clearOriginIsLeftUntouched() {
        // Far south of the footprint: nothing across the corridor, so the origin is unchanged.
        BlockPos origin = new BlockPos(104, 100, 300);

        BlockPos nudged = StationOriginNudge.resolve(origin, Direction.EAST, List.of(SPAWN_VILLAGE), FLAT);

        assertSame(origin, nudged);
    }

    @Test
    void villageEntirelyBehindTheStartDoesNotMoveTheOrigin() {
        // WEST track travels toward -X; a footprint entirely east (behind) the origin must not
        // displace the start.
        BoundingBox behind = new BoundingBox(400, 62, 40, 460, 100, 90);
        BlockPos origin = new BlockPos(104, 100, 56);

        BlockPos nudged = StationOriginNudge.resolve(origin, Direction.WEST, List.of(behind), FLAT);

        assertSame(origin, nudged);
    }

    @Test
    void northSouthTrackShiftsAlongX() {
        // NORTH track runs along Z, so a footprint across it is cleared by shifting in X.
        BlockPos origin = new BlockPos(104, 100, 56);

        BlockPos nudged = StationOriginNudge.resolve(origin, Direction.NORTH, List.of(SPAWN_VILLAGE), FLAT);

        assertEquals(origin.getZ(), nudged.getZ(), "along-track coordinate must not move");
        assertTrue(nudged.getX() > SPAWN_VILLAGE.maxX() || nudged.getX() < SPAWN_VILLAGE.minX(),
                "origin must end clear of the footprint in X");
    }
}
