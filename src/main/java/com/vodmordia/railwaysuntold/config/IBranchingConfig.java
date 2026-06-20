package com.vodmordia.railwaysuntold.config;

/**
 * Configuration for track branching behavior.
 */
public interface IBranchingConfig {

    int getBranchMinDistance();
    int getBranchChance();
    int getMaxBlocksWithoutBranch();
}
