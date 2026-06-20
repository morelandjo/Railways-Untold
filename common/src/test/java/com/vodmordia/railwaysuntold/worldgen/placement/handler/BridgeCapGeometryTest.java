package com.vodmordia.railwaysuntold.worldgen.placement.handler;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins {@link AbstractPlacementExecutor#capSpanBeforeObstacle}: a long bridge that an existing line crosses
 * partway is capped to stop short of the crossing so the head advances TO the obstacle, instead of the whole
 * span failing as "blocked by existing track" and the head stalling far short (seed -2850134536171470156:
 * head 11's 128-block bridge -1307,-1448 -> -1307,-1576 was rejected wholesale by a diagonal crossing it at
 * z=-1536, leaving the head stalled at -1448, ~88 blocks short).
 */
class BridgeCapGeometryTest {

    private static final int MARGIN = 8;

    @Test
    void theProductionBridgeIsCappedShortOfTheCrossingInsteadOfFailingWholesale() {
        BlockPos start = new BlockPos(-1307, 68, -1448);
        BlockPos end = new BlockPos(-1307, 68, -1576);
        BlockPos obstacle = new BlockPos(-1307, 68, -1536); // diagonal crosses x=-1307 here
        BlockPos capped = AbstractPlacementExecutor.capSpanBeforeObstacle(start, end, obstacle, Direction.NORTH, MARGIN);
        // Stops MARGIN short of the obstacle: -1536 + 8 = -1528 (advancing 80 of the 128-block span).
        assertEquals(new BlockPos(-1307, 68, -1528), capped);
    }

    @Test
    void anObstacleTooCloseToAdvanceTowardYieldsNoCap() {
        BlockPos start = new BlockPos(-1307, 68, -1448);
        BlockPos end = new BlockPos(-1307, 68, -1576);
        BlockPos obstacle = new BlockPos(-1307, 68, -1452); // only 4 ahead, < margin + min advance
        assertNull(AbstractPlacementExecutor.capSpanBeforeObstacle(start, end, obstacle, Direction.NORTH, MARGIN));
    }

    @Test
    void theCappedEndYInterpolatesAlongTheSpan() {
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos end = new BlockPos(0, 80, -128); // climbs 16 over 128 north
        BlockPos obstacle = new BlockPos(0, 72, -88);
        BlockPos capped = AbstractPlacementExecutor.capSpanBeforeObstacle(start, end, obstacle, Direction.NORTH, MARGIN);
        // advance = 88 - 8 = 80 of 128 -> Y = 64 + round(16 * 80/128) = 64 + 10 = 74.
        assertEquals(new BlockPos(0, 74, -80), capped);
    }

    @Test
    void theHeadingDeterminesTheForwardAxis() {
        // Heading EAST (+x): obstacle ahead on +x, cap stops short along x.
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos end = new BlockPos(128, 64, 0);
        BlockPos obstacle = new BlockPos(88, 64, 0);
        BlockPos capped = AbstractPlacementExecutor.capSpanBeforeObstacle(start, end, obstacle, Direction.EAST, MARGIN);
        assertEquals(new BlockPos(80, 64, 0), capped);
    }
}
