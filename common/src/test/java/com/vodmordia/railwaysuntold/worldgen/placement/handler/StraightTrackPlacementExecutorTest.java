package com.vodmordia.railwaysuntold.worldgen.placement.handler;

import com.vodmordia.railwaysuntold.worldgen.placement.PlacementConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gates the one piece of request-shaping logic the STRAIGHT executor owns before it hands off to the
 * world (PL4): the run length it asks {@code StraightTrackPlacer.place} for. The placement seam's
 * block-level outcomes - gap-free beziers, exact-target-Y, a connected run - are pinned end-to-end by the
 * PL5 gametests; the residual pure guard here is that a degenerate (&lt;2) straight is never requested.
 */
class StraightTrackPlacementExecutorTest {

    @Test
    void positiveDistancePassesThrough() {
        assertEquals(5, StraightTrackPlacementExecutor.resolveStraightDistance(5));
        assertEquals(64, StraightTrackPlacementExecutor.resolveStraightDistance(64));
    }

    @Test
    void shortPositiveDistanceFloorsAtTwo() {
        // A 1-block decision would otherwise place a degenerate run; it is lifted to 2.
        assertEquals(2, StraightTrackPlacementExecutor.resolveStraightDistance(1));
    }

    @Test
    void nonPositiveDistanceFallsBackToTheDefaultLength() {
        assertEquals(PlacementConstants.DEFAULT_STRAIGHT_LENGTH,
                StraightTrackPlacementExecutor.resolveStraightDistance(0));
        assertEquals(PlacementConstants.DEFAULT_STRAIGHT_LENGTH,
                StraightTrackPlacementExecutor.resolveStraightDistance(-7));
        // The fallback the floor relies on is itself >=2.
        assertTrue(PlacementConstants.DEFAULT_STRAIGHT_LENGTH >= 2);
    }
}
