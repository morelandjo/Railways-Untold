package com.vodmordia.railwaysuntold.worldgen.placement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link JunctionTerminator#inForwardBeam}: the last-resort merge looks AHEAD along travel for a line
 * the head is driving head-on toward (the bridge-into-a-far-crossing case), not just around its tip. A
 * point ahead within reach and window is in the beam; behind, too-lateral, too-high, or beyond reach is not.
 */
class JunctionTerminatorBeamTest {

    private static final BlockPos TIP = new BlockPos(0, 70, 0);
    private static final int REACH = 160;
    private static final int HALF = 8;

    @Test
    void aLineFarAheadOnTheTravelAxisIsInTheBeam() {
        // The exact case that left a dead stub: a crossing ~120 blocks ahead, beyond the tip search.
        assertTrue(JunctionTerminator.inForwardBeam(new BlockPos(120, 70, 0), TIP, Direction.EAST, REACH, HALF));
    }

    @Test
    void aLineBehindTheTipIsNotInTheBeam() {
        assertFalse(JunctionTerminator.inForwardBeam(new BlockPos(-30, 70, 0), TIP, Direction.EAST, REACH, HALF));
    }

    @Test
    void aLineBeyondReachIsNotInTheBeam() {
        assertFalse(JunctionTerminator.inForwardBeam(new BlockPos(200, 70, 0), TIP, Direction.EAST, REACH, HALF));
    }

    @Test
    void aLineTooFarToTheSideIsNotInTheBeam() {
        assertFalse(JunctionTerminator.inForwardBeam(new BlockPos(120, 70, 20), TIP, Direction.EAST, REACH, HALF));
    }

    @Test
    void aLineWithinTheVerticalWindowIsInTheBeam() {
        assertTrue(JunctionTerminator.inForwardBeam(new BlockPos(120, 76, 0), TIP, Direction.EAST, REACH, HALF));
    }

    @Test
    void theBeamFollowsTheTravelDirection() {
        // Heading NORTH (-z): a point to the north is in the beam, the same point to the east is not.
        assertTrue(JunctionTerminator.inForwardBeam(new BlockPos(0, 70, -120), TIP, Direction.NORTH, REACH, HALF));
        assertFalse(JunctionTerminator.inForwardBeam(new BlockPos(120, 70, 0), TIP, Direction.NORTH, REACH, HALF));
    }
}
