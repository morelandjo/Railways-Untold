package com.vodmordia.railwaysuntold.worldgen.head.state;

import com.vodmordia.railwaysuntold.worldgen.head.state.SidingState.PendingSiding;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterizes {@link SidingState} - the "one siding per long straight stretch" eligibility machine the
 * siding pass mocked. Pure (no config, no world).
 */
class SidingStateTest {

    @Test
    void notEligibleUntilTheConsecutiveStraightRunReachesTheMinimum() {
        SidingState state = new SidingState();
        assertFalse(state.isEligible(50), "a fresh head has laid no straight track");

        state.incrementStraight(30);
        assertFalse(state.isEligible(50), "30 < 50 -> still ineligible");
        state.incrementStraight(20);
        assertEquals(50, state.getConsecutiveStraightBlocks());
        assertTrue(state.isEligible(50), "exactly the minimum is eligible (inclusive)");
        assertFalse(state.isEligible(51), "but not for a higher requirement");
    }

    @Test
    void placingASidingBlocksTheRestOfTheStretchEvenWithEnoughTrack() {
        SidingState state = new SidingState();
        state.incrementStraight(100);
        assertTrue(state.isEligible(50));

        state.markPlaced(Direction.EAST);
        assertFalse(state.isEligible(50), "once placed this stretch, no second siding despite the long run");
        assertEquals(Direction.EAST, state.getSidingSide());
    }

    @Test
    void resetClearsTheCounterPlacementFlagSideAndRetries() {
        SidingState state = new SidingState();
        state.incrementStraight(100);
        state.markPlaced(Direction.WEST);
        state.enqueueRetry(pending(1));

        state.reset();

        assertEquals(0, state.getConsecutiveStraightBlocks());
        assertNull(state.getSidingSide());
        assertFalse(state.hasPendingRetries());
        // After reset the head can earn another siding from scratch.
        state.incrementStraight(60);
        assertTrue(state.isEligible(50), "a fresh stretch is eligible again");
    }

    @Test
    void drainRetriesReturnsThePendingItemsAndEmptiesTheQueue() {
        SidingState state = new SidingState();
        assertEquals(List.of(), state.drainRetries(), "draining an empty queue yields an empty list");

        state.enqueueRetry(pending(1));
        state.enqueueRetry(pending(2));
        assertTrue(state.hasPendingRetries());

        List<PendingSiding> drained = state.drainRetries();
        assertEquals(2, drained.size());
        assertFalse(state.hasPendingRetries(), "drain empties the queue");
        assertEquals(List.of(), state.drainRetries(), "a second drain is empty");
    }

    @Test
    void incrementingAttemptsPreservesEveryOtherPendingField() {
        PendingSiding p = pending(1);
        PendingSiding next = p.withIncrementedAttempts();

        assertEquals(2, next.attempts());
        assertEquals(p.sidingOrigin(), next.sidingOrigin());
        assertEquals(p.parentDir(), next.parentDir());
        assertEquals(p.placeLeft(), next.placeLeft());
        assertEquals(p.sidingStraightLength(), next.sidingStraightLength());
        assertEquals(p.sidingSide(), next.sidingSide());
    }

    private static PendingSiding pending(int attempts) {
        return new PendingSiding(new BlockPos(1, 2, 3), Direction.NORTH, true, 40, Direction.EAST, attempts);
    }
}
