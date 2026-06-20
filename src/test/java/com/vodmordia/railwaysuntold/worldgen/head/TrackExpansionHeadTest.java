package com.vodmordia.railwaysuntold.worldgen.head;

import com.vodmordia.railwaysuntold.util.spatial.DiagonalDirection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterizes the head-level logic of {@link TrackExpansionHead} - the facade the decision/branching/siding
 * passes mocked as a whole. Focuses on behavior that lives on the head itself (construction wiring, diagonal
 * mode, the direction-change reset coordination, lifecycle flags) rather than re-testing the delegated state
 * machines, which are pinned in their own tests. World/config-free.
 */
class TrackExpansionHeadTest {

    private static TrackExpansionHead head() {
        return new TrackExpansionHead(1, new BlockPos(10, 64, 20), Direction.EAST);
    }

    @Test
    void theDefaultConstructorWiresUpAnOriginalRunningCardinalHead() {
        TrackExpansionHead head = head();
        assertEquals(1, head.getHeadNumber());
        assertEquals(new BlockPos(10, 64, 20), head.getPosition());
        assertEquals(Direction.EAST, head.getDirection());
        assertEquals(Direction.EAST, head.getInitialDirection(), "initial direction defaults to the start direction");
        assertTrue(head.isOriginalHead(), "no parent -> original head");
        assertFalse(head.isComplete());
        assertFalse(head.isPaused());
        assertFalse(head.isDiagonal());
        assertFalse(head.hasPlacedAnySegments());
    }

    @Test
    void diagonalModeEntersAndExits() {
        TrackExpansionHead head = head();
        head.enterDiagonalMode(DiagonalDirection.NORTHEAST);
        assertTrue(head.isDiagonal());
        assertSame(DiagonalDirection.NORTHEAST, head.getDiagonalHeading());

        head.exitDiagonalMode();
        assertFalse(head.isDiagonal());
        assertEquals(null, head.getDiagonalHeading());
    }

    @Test
    void onDirectionChangeResetsTheStraightRunDependentCounters() {
        TrackExpansionHead head = head();
        head.incrementSidingDistance(100);
        head.incrementBlocksSinceLastCurve(40);
        assertEquals(100, head.getSidingState().getConsecutiveStraightBlocks());
        assertTrue(head.getSidingState().isEligible(50));
        assertEquals(40, head.getBlocksSinceLastCurve());

        head.onDirectionChange();

        assertEquals(0, head.getSidingState().getConsecutiveStraightBlocks(), "a turn breaks the straight run");
        assertFalse(head.getSidingState().isEligible(50), "so the head is no longer siding-eligible");
        assertEquals(0, head.getBlocksSinceLastCurve(), "and the since-last-curve counter resets too");
    }

    @Test
    void lifecycleFlagsFlip() {
        TrackExpansionHead head = head();

        head.markHasPlacedSegments();
        assertTrue(head.hasPlacedAnySegments());

        head.pause();
        assertTrue(head.isPaused());
        head.resume();
        assertFalse(head.isPaused());

        head.markComplete();
        assertTrue(head.isComplete());
    }

    @Test
    void torchPlacementOffsetAccumulates() {
        TrackExpansionHead head = head();
        assertEquals(0, head.getTorchPlacementOffset());
        head.addToTorchPlacementOffset(3);
        head.addToTorchPlacementOffset(2);
        assertEquals(5, head.getTorchPlacementOffset());
    }
}
