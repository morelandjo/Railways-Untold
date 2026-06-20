package com.vodmordia.railwaysuntold.worldgen.branching;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link ParentBranchDivergence#steerAwayFromBranch}: when a branch leaves the parent on one
 * side, a parent exploration target on that same side is mirrored across the parent's travel axis so
 * the parent diverges; far-side, centered, and non-perpendicular cases are left alone.
 */
class ParentBranchDivergenceTest {

    private static final BlockPos PARENT = new BlockPos(0, 70, 0);

    @Test
    void sameSideTargetIsMirroredAcrossTheTravelAxis() {
        // Parent travels EAST; branch leaves SOUTH (+z). A target 300 south of the axis must flip to north,
        // keeping its forward (east) reach.
        BlockPos target = new BlockPos(800, 70, 300);
        BlockPos out = ParentBranchDivergence.steerAwayFromBranch(PARENT, Direction.EAST, Direction.SOUTH, target);
        assertEquals(800, out.getX(), "forward reach must be preserved");
        assertEquals(-300, out.getZ(), "lateral offset must mirror to the side opposite the branch");
    }

    @Test
    void farSideTargetIsUnchanged() {
        // Branch SOUTH, target already NORTH of the axis - the parent already diverges, leave it.
        BlockPos target = new BlockPos(800, 70, -300);
        BlockPos out = ParentBranchDivergence.steerAwayFromBranch(PARENT, Direction.EAST, Direction.SOUTH, target);
        assertEquals(target, out);
    }

    @Test
    void centeredTargetIsUnchanged() {
        BlockPos target = new BlockPos(800, 70, 0);
        BlockPos out = ParentBranchDivergence.steerAwayFromBranch(PARENT, Direction.EAST, Direction.SOUTH, target);
        assertEquals(target, out);
    }

    @Test
    void aBranchParallelToTravelIsIgnored() {
        // Defensive: a branch on the same axis as travel is not a perpendicular branch, so no mirroring.
        BlockPos target = new BlockPos(800, 70, 300);
        BlockPos out = ParentBranchDivergence.steerAwayFromBranch(PARENT, Direction.EAST, Direction.WEST, target);
        assertEquals(target, out);
    }
}
