package com.vodmordia.railwaysuntold.worldgen.head.state;

import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;

/**
 * Tracks exploration target state for village targeting.
 */
public class ExplorationState {

    private BlockPos explorationTarget;

    public void setExplorationTarget(BlockPos target) {
        this.explorationTarget = target;
    }

    @Nullable
    public BlockPos getExplorationTarget() {
        return explorationTarget;
    }

    public boolean hasExplorationTarget() {
        return explorationTarget != null;
    }

    public void clearExplorationTarget() {
        this.explorationTarget = null;
    }

    public void clear() {
        clearExplorationTarget();
    }
}
