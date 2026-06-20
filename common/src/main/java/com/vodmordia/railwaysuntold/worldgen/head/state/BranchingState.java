package com.vodmordia.railwaysuntold.worldgen.head.state;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Tracks branching state for a track expansion head.
 */
public class BranchingState {

    private final UUID headId;
    private final UUID parentHeadId;
    private final int branchDepth;
    private int blocksSinceLastBranch;
    private final List<UUID> childBranchIds;

    private BlockPos branchOriginPos;
    private Direction parentTrackDirection;

    /** Junction position of the most recent branch spawned by this head (transient, not persisted). */
    private BlockPos lastBranchJunctionPos;
    /** Direction the most recent branch diverges (transient, not persisted). */
    private Direction lastBranchDirection;

    public BranchingState(UUID headId, UUID parentHeadId, int branchDepth) {
        this.headId = headId;
        this.parentHeadId = parentHeadId;
        this.branchDepth = branchDepth;
        this.blocksSinceLastBranch = 0;
        this.childBranchIds = new ArrayList<>();
    }

    public UUID getHeadId() {
        return headId;
    }

    public UUID getParentHeadId() {
        return parentHeadId;
    }

    public int getBranchDepth() {
        return branchDepth;
    }

    public int getBlocksSinceLastBranch() {
        return blocksSinceLastBranch;
    }

    public List<UUID> getChildBranchIds() {
        return Collections.unmodifiableList(childBranchIds);
    }

    public boolean isOriginalHead() {
        return parentHeadId == null;
    }

    public void incrementBranchDistance(int blocks) {
        blocksSinceLastBranch += blocks;
    }

    public void resetBranchCounter() {
        blocksSinceLastBranch = 0;
    }

    public void addChildBranch(UUID childHeadId) {
        childBranchIds.add(childHeadId);
    }

    public boolean isEligibleForBranch(RailwaysUntoldConfig config) {
        if (!RailwaysUntoldConfig.areBranchesEnabled()) {
            return false;
        }
        if (blocksSinceLastBranch < config.BRANCH_MIN_DISTANCE) {
            return false;
        }
        if (!RailwaysUntoldConfig.isRecursiveBranchingEnabled() && !isOriginalHead()) {
            return false;
        }
        int maxDepth = RailwaysUntoldConfig.getMaxBranchDepth();
        return maxDepth <= 0 || branchDepth < maxDepth;
    }

    public BlockPos getBranchOriginPos() {
        return branchOriginPos;
    }

    public Direction getParentTrackDirection() {
        return parentTrackDirection;
    }

    public void setBranchOrigin(BlockPos pos, Direction direction) {
        this.branchOriginPos = pos;
        this.parentTrackDirection = direction;
    }

    public BlockPos getLastBranchJunctionPos() {
        return lastBranchJunctionPos;
    }

    public Direction getLastBranchDirection() {
        return lastBranchDirection;
    }

    public void setLastBranchJunction(BlockPos junctionPos, Direction branchDir) {
        this.lastBranchJunctionPos = junctionPos;
        this.lastBranchDirection = branchDir;
    }

    public void clearLastBranchJunction() {
        this.lastBranchJunctionPos = null;
        this.lastBranchDirection = null;
    }

    public void restore(int blocksSinceLastBranch, List<UUID> childBranchIds) {
        this.blocksSinceLastBranch = blocksSinceLastBranch;
        this.childBranchIds.clear();
        this.childBranchIds.addAll(childBranchIds);
    }

}
