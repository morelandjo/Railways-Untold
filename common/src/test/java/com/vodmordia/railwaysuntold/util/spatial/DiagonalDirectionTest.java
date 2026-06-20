package com.vodmordia.railwaysuntold.util.spatial;

import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterizes {@link DiagonalDirection} - the 45° diagonal enum behind diagonal track placement: step
 * vectors, the cardinal+turn -> diagonal mapping, left-turn detection, Create PD/ND classification, and the
 * cardinal-compatibility helpers the diagonal entry/exit decisions rely on. World/config-free.
 */
class DiagonalDirectionTest {

    @Test
    void stepVectorsMatchTheCompassQuadrant() {
        assertEquals(1, DiagonalDirection.NORTHEAST.getStepX());
        assertEquals(-1, DiagonalDirection.NORTHEAST.getStepZ());
        assertEquals(1, DiagonalDirection.SOUTHEAST.getStepX());
        assertEquals(1, DiagonalDirection.SOUTHEAST.getStepZ());
        assertEquals(-1, DiagonalDirection.SOUTHWEST.getStepX());
        assertEquals(1, DiagonalDirection.SOUTHWEST.getStepZ());
        assertEquals(-1, DiagonalDirection.NORTHWEST.getStepX());
        assertEquals(-1, DiagonalDirection.NORTHWEST.getStepZ());
    }

    @Test
    void from45TurnPicksTheDiagonalForEachCardinalAndTurn() {
        assertSame(DiagonalDirection.NORTHWEST, DiagonalDirection.from45Turn(Direction.NORTH, true));
        assertSame(DiagonalDirection.NORTHEAST, DiagonalDirection.from45Turn(Direction.NORTH, false));
        assertSame(DiagonalDirection.NORTHEAST, DiagonalDirection.from45Turn(Direction.EAST, true));
        assertSame(DiagonalDirection.SOUTHEAST, DiagonalDirection.from45Turn(Direction.EAST, false));
        assertSame(DiagonalDirection.SOUTHEAST, DiagonalDirection.from45Turn(Direction.SOUTH, true));
        assertSame(DiagonalDirection.SOUTHWEST, DiagonalDirection.from45Turn(Direction.SOUTH, false));
        assertSame(DiagonalDirection.SOUTHWEST, DiagonalDirection.from45Turn(Direction.WEST, true));
        assertSame(DiagonalDirection.NORTHWEST, DiagonalDirection.from45Turn(Direction.WEST, false));
    }

    @Test
    void isLeftTurnFromIsTheInverseOfFrom45Turn() {
        // The left-turn diagonal of each cardinal reports a left turn; the right-turn one does not.
        for (Direction cardinal : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            assertTrue(DiagonalDirection.from45Turn(cardinal, true).isLeftTurnFrom(cardinal),
                    "left diagonal of " + cardinal);
            assertFalse(DiagonalDirection.from45Turn(cardinal, false).isLeftTurnFrom(cardinal),
                    "right diagonal of " + cardinal);
        }
    }

    @Test
    void positiveDiagonalIsTheSeNwPair() {
        assertTrue(DiagonalDirection.SOUTHEAST.isPositiveDiagonal());
        assertTrue(DiagonalDirection.NORTHWEST.isPositiveDiagonal());
        assertFalse(DiagonalDirection.NORTHEAST.isPositiveDiagonal());
        assertFalse(DiagonalDirection.SOUTHWEST.isPositiveDiagonal());
    }

    @Test
    void supportsCardinalIsTrueOnlyForTheTwoSpannedAxes() {
        assertTrue(DiagonalDirection.SOUTHEAST.supportsCardinal(Direction.SOUTH));
        assertTrue(DiagonalDirection.SOUTHEAST.supportsCardinal(Direction.EAST));
        assertFalse(DiagonalDirection.SOUTHEAST.supportsCardinal(Direction.NORTH), "135° away - not spanned");
        assertFalse(DiagonalDirection.SOUTHEAST.supportsCardinal(Direction.WEST));
    }

    @Test
    void nearestCardinalKeepsACompatiblePreferenceOrSwingsNinetyDegrees() {
        // Already compatible -> unchanged.
        assertSame(Direction.EAST, DiagonalDirection.NORTHEAST.nearestCardinal(Direction.EAST));
        // Incompatible WEST for NORTHEAST -> the 90°-away NORTH, not the 180°-away EAST.
        assertSame(Direction.NORTH, DiagonalDirection.NORTHEAST.nearestCardinal(Direction.WEST));
        // Incompatible EAST for SOUTHWEST -> SOUTH (90°), not WEST (180°).
        assertSame(Direction.SOUTH, DiagonalDirection.SOUTHWEST.nearestCardinal(Direction.EAST));
    }
}
