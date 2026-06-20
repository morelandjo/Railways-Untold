package com.vodmordia.railwaysuntold.worldgen.placement.decider;

import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link ExplorationReaimer#chooseEscalationDirection}: a reversed head is re-aimed outward; a head
 * still heading outward or laterally keeps its current heading.
 */
class ExplorationReaimerTest {

    @Test
    void aReversedHeadIsReAimedOutward() {
        // Set out NORTH, now heading SOUTH (looped back) - the next target must aim NORTH again, not SOUTH.
        assertEquals(Direction.NORTH,
                ExplorationReaimer.chooseEscalationDirection(Direction.NORTH, Direction.SOUTH));
    }

    @Test
    void aForwardHeadKeepsItsHeading() {
        assertEquals(Direction.NORTH,
                ExplorationReaimer.chooseEscalationDirection(Direction.NORTH, Direction.NORTH));
    }

    @Test
    void aLateralHeadingIsLeftAlone() {
        // 90 degrees off is lateral maneuvering, not a reversal - keep it.
        assertEquals(Direction.EAST,
                ExplorationReaimer.chooseEscalationDirection(Direction.NORTH, Direction.EAST));
    }
}
