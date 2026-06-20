package com.vodmordia.railwaysuntold.worldgen.siding;

import com.vodmordia.railwaysuntold.worldgen.planner.noise.PlannerTestConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterizes the pure footprint helper {@link SidingTrackCreator#calculateTotalForwardDistance}: a
 * siding is two S-curves (diverge, then rejoin) bracketing a parallel straight, so the total forward
 * distance is a fixed two-S-curve footprint plus the straight length 1:1.
 */
class SidingTrackCreatorTest {

    @BeforeAll
    static void setUp() {
        PlannerTestConfig.bootstrapDefaults();
    }

    @Test
    void totalForwardDistanceIsTheTwoScurveFootprintPlusTheStraightLength() {
        int base = SidingTrackCreator.calculateTotalForwardDistance(0);
        assertTrue(base > 0, "the two S-curves have a positive forward footprint");
        // The straight section adds to the forward distance one-for-one.
        assertEquals(base + 40, SidingTrackCreator.calculateTotalForwardDistance(40));
        assertEquals(base + 100, SidingTrackCreator.calculateTotalForwardDistance(100));
    }
}
