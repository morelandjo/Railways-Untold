package com.vodmordia.railwaysuntold.worldgen.branching;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Keeps a parent head from drifting onto the side a branch just took. A branch leaves the parent
 * perpendicular to the parent's travel direction; if the parent's onward exploration target sits on
 * that same side, the parent curves after the branch and the two fight for the same ground until one
 * stalls. Given the parent's travel direction and the branch's direction, this mirrors a same-side
 * target across the parent's travel axis so the parent diverges instead. A target already on the far
 * side, centered on the travel axis, or a branch that is not perpendicular to travel is returned
 * unchanged, so a parent committed to a target on the far side keeps it.
 */
public final class ParentBranchDivergence {

    private ParentBranchDivergence() {}

    public static BlockPos steerAwayFromBranch(BlockPos parentPos, Direction parentDir,
                                               Direction branchDir, BlockPos target) {
        if (target == null || branchDir.getAxis() == parentDir.getAxis()) {
            return target;
        }
        int bx = branchDir.getStepX();
        int bz = branchDir.getStepZ();
        int lateral = (target.getX() - parentPos.getX()) * bx + (target.getZ() - parentPos.getZ()) * bz;
        if (lateral <= 0) {
            return target;
        }
        return target.offset(-2 * lateral * bx, 0, -2 * lateral * bz);
    }
}
