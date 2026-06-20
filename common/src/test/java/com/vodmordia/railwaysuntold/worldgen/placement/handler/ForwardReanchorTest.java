package com.vodmordia.railwaysuntold.worldgen.placement.handler;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins {@link AbstractPlacementExecutor#resolveForwardReanchor}: a small purely-forward drift of the
 * live tip off the planner's stale start re-anchors to the tip (so the bezier connects to the real
 * track instead of halt->replan looping), while a lateral / backward / past-the-end / over-long drift
 * still returns null so the caller halts and replans. Coordinates are the production head-2 village-skirt
 * stall (seed 6804434896546404492): a branch junction advanced the tip 15 west of the compiled bezier
 * start, then the guard spun the head into a halt-replan loop and a silent idle.
 */
class ForwardReanchorTest {

    private static final Direction WEST = Direction.WEST;

    @Test
    void noDriftReturnsPlannerStart() {
        BlockPos start = new BlockPos(-1964, 76, -49);
        assertEquals(start, AbstractPlacementExecutor.resolveForwardReanchor(
                start, start, new BlockPos(-2013, 71, -49), WEST));
    }

    @Test
    void productionForwardDriftReanchorsToTip() {
        BlockPos planned = new BlockPos(-1964, 76, -49);
        BlockPos live = new BlockPos(-1979, 76, -49);
        BlockPos end = new BlockPos(-2013, 71, -49);
        assertEquals(live, AbstractPlacementExecutor.resolveForwardReanchor(planned, live, end, WEST));
    }

    @Test
    void oneBlockForwardDriftReanchors() {
        assertEquals(new BlockPos(-1981, 76, -49), AbstractPlacementExecutor.resolveForwardReanchor(
                new BlockPos(-1980, 76, -49), new BlockPos(-1981, 76, -49), new BlockPos(-2030, 72, -47), WEST));
    }

    @Test
    void lateralDriftHalts() {
        assertNull(AbstractPlacementExecutor.resolveForwardReanchor(
                new BlockPos(-1964, 76, -49), new BlockPos(-1979, 76, -45), new BlockPos(-2013, 71, -49), WEST));
    }

    @Test
    void backwardDriftHalts() {
        assertNull(AbstractPlacementExecutor.resolveForwardReanchor(
                new BlockPos(-1979, 76, -49), new BlockPos(-1964, 76, -49), new BlockPos(-2013, 71, -49), WEST));
    }

    @Test
    void driftBeyondMaxHalts() {
        int tooFar = AbstractPlacementExecutor.MAX_REANCHOR_DRIFT + 1;
        assertNull(AbstractPlacementExecutor.resolveForwardReanchor(
                new BlockPos(-1964, 76, -49), new BlockPos(-1964 - tooFar, 76, -49),
                new BlockPos(-2013, 71, -49), WEST));
    }

    @Test
    void tipAtOrPastEndHalts() {
        assertNull(AbstractPlacementExecutor.resolveForwardReanchor(
                new BlockPos(-1964, 76, -49), new BlockPos(-2013, 71, -49), new BlockPos(-2013, 71, -49), WEST));
    }
}
