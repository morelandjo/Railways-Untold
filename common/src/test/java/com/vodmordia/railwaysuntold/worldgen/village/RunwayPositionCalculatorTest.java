package com.vodmordia.railwaysuntold.worldgen.village;

import com.vodmordia.railwaysuntold.util.chunk.ChunkBounds;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Characterizes the pure pieces of {@link RunwayPositionCalculator}: the runway chunk (one chunk past
 * the village edge on the approach side) and the track direction (perpendicular to that side).
 * ({@code getGroundLevel} is world-bound and gametest-covered.)
 */
class RunwayPositionCalculatorTest {

    // A village spanning chunk X in [2,5], Z in [3,7].
    private static final ChunkBounds VILLAGE = new ChunkBounds(2, 5, 3, 7);

    @Test
    void runwayChunkSitsOneChunkPastTheApproachSideEdge() {
        assertEquals(3 - 1, RunwayPositionCalculator.getRunwayChunk(Direction.NORTH, VILLAGE), "north: minZ-1");
        assertEquals(7 + 1, RunwayPositionCalculator.getRunwayChunk(Direction.SOUTH, VILLAGE), "south: maxZ+1");
        assertEquals(5 + 1, RunwayPositionCalculator.getRunwayChunk(Direction.EAST, VILLAGE), "east: maxX+1");
        assertEquals(2 - 1, RunwayPositionCalculator.getRunwayChunk(Direction.WEST, VILLAGE), "west: minX-1");
    }

    @Test
    void aNonHorizontalApproachIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> RunwayPositionCalculator.getRunwayChunk(Direction.UP, VILLAGE));
        assertThrows(IllegalArgumentException.class,
                () -> RunwayPositionCalculator.getRunwayChunk(Direction.DOWN, VILLAGE));
    }

    @Test
    void trackRunsPerpendicularToTheStationSide() {
        // Station on a N/S side -> track runs E-W; on an E/W side -> track runs N-S.
        assertEquals(Direction.EAST, RunwayPositionCalculator.getTrackDirection(Direction.NORTH));
        assertEquals(Direction.EAST, RunwayPositionCalculator.getTrackDirection(Direction.SOUTH));
        assertEquals(Direction.NORTH, RunwayPositionCalculator.getTrackDirection(Direction.EAST));
        assertEquals(Direction.NORTH, RunwayPositionCalculator.getTrackDirection(Direction.WEST));
    }
}
