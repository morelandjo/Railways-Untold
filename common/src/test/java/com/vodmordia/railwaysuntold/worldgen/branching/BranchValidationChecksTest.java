package com.vodmordia.railwaysuntold.worldgen.branching;

import com.vodmordia.railwaysuntold.worldgen.head.ExpansionHeadManager;
import com.vodmordia.railwaysuntold.worldgen.head.TrackExpansionHead;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Characterizes {@link BranchValidationChecks#hasAdequateSpacing} - the pure spacing predicate that keeps
 * a new branch from spawning too close to another active head. The load-bearing invariant is that the
 * parent head (which sits at/near the branch point) is excluded from the check, so a branch is never
 * blocked by its own creator.
 */
class BranchValidationChecksTest {

    private static TrackExpansionHead headAt(UUID id, BlockPos pos) {
        TrackExpansionHead h = mock(TrackExpansionHead.class);
        when(h.getHeadId()).thenReturn(id);
        when(h.getPosition()).thenReturn(pos);
        return h;
    }

    private static ExpansionHeadManager managerOf(TrackExpansionHead... heads) {
        ExpansionHeadManager mgr = mock(ExpansionHeadManager.class);
        when(mgr.getActiveHeads()).thenReturn(List.of(heads));
        return mgr;
    }

    @Test
    void spacingIsAdequateWhenEveryOtherHeadIsFarEnough() {
        TrackExpansionHead parent = headAt(UUID.randomUUID(), new BlockPos(0, 0, 0));
        TrackExpansionHead other = headAt(UUID.randomUUID(), new BlockPos(100, 0, 0));
        assertTrue(BranchValidationChecks.hasAdequateSpacing(
                new BlockPos(0, 0, 0), managerOf(parent, other), 50, parent));
    }

    @Test
    void spacingIsInadequateWhenAnotherHeadIsWithinTheMinimum() {
        TrackExpansionHead parent = headAt(UUID.randomUUID(), new BlockPos(0, 0, 0));
        TrackExpansionHead other = headAt(UUID.randomUUID(), new BlockPos(10, 0, 0)); // 10 < 50
        assertFalse(BranchValidationChecks.hasAdequateSpacing(
                new BlockPos(0, 0, 0), managerOf(parent, other), 50, parent));
    }

    @Test
    void theParentHeadIsExcludedSoItNeverBlocksItsOwnBranch() {
        // Only the parent head is active, sitting right at the branch point - spacing is still adequate.
        UUID parentId = UUID.randomUUID();
        TrackExpansionHead parent = headAt(parentId, new BlockPos(0, 0, 0));
        assertTrue(BranchValidationChecks.hasAdequateSpacing(
                new BlockPos(0, 0, 0), managerOf(parent), 50, parent));
    }
}
