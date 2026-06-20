package com.vodmordia.railwaysuntold.worldgen.village;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Characterizes the pure piece of {@link NoiseStationPositionCalculator}: {@code offsetToApproachWaypoint},
 * which offsets a station position into an approach waypoint - 120 blocks back along the runway/track axis
 * (toward the head) and 20 blocks perpendicular toward the approach side, so the head can curve onto the
 * runway instead of overshooting. (The sampler-based position methods are world-bound and gametest-covered.)
 */
class NoiseStationPositionCalculatorTest {

    private static final BlockPos STATION = new BlockPos(1000, 64, 1000);

    @Test
    void aNorthApproachOffsetsAlongTheEastWestTrackTowardTheHeadAndPushesNorth() {
        // North station -> track runs E-W; the 120 axis offset points toward the head's X side, the 20
        // perpendicular offset goes NORTH (−Z).
        BlockPos fromWest = NoiseStationPositionCalculator.offsetToApproachWaypoint(
                STATION, Direction.NORTH, new BlockPos(500, 64, 1000)); // head west of station
        assertEquals(new BlockPos(1000 - 120, 64, 1000 - 20), fromWest);

        BlockPos fromEast = NoiseStationPositionCalculator.offsetToApproachWaypoint(
                STATION, Direction.NORTH, new BlockPos(1500, 64, 1000)); // head east of station
        assertEquals(new BlockPos(1000 + 120, 64, 1000 - 20), fromEast);
    }

    @Test
    void anEastApproachOffsetsAlongTheNorthSouthTrackTowardTheHeadAndPushesEast() {
        // East station -> track runs N-S; the 120 axis offset points toward the head's Z side, the 20
        // perpendicular offset goes EAST (+X).
        BlockPos fromNorth = NoiseStationPositionCalculator.offsetToApproachWaypoint(
                STATION, Direction.EAST, new BlockPos(1000, 64, 500)); // head north of station
        assertEquals(new BlockPos(1000 + 20, 64, 1000 - 120), fromNorth);

        BlockPos fromSouth = NoiseStationPositionCalculator.offsetToApproachWaypoint(
                STATION, Direction.EAST, new BlockPos(1000, 64, 1500)); // head south of station
        assertEquals(new BlockPos(1000 + 20, 64, 1000 + 120), fromSouth);
    }

    @Test
    void theStationYIsCarriedThrough() {
        BlockPos waypoint = NoiseStationPositionCalculator.offsetToApproachWaypoint(
                new BlockPos(0, 99, 0), Direction.NORTH, new BlockPos(-50, 99, 0));
        assertEquals(99, waypoint.getY());
    }
}
