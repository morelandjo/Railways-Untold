package com.vodmordia.railwaysuntold.worldgen.head.state;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.PlannerTestConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterizes {@link BranchingState} - the branch-eligibility machine the branching pass mocked. The
 * distance/depth/recursion gates read config, so this bootstraps the defaults (branches + recursive
 * branching enabled, maxBranchDepth = 3).
 */
class BranchingStateTest {

    @BeforeAll
    static void setUp() {
        PlannerTestConfig.bootstrapDefaults();
    }

    private static final UUID ID = new UUID(0L, 1L);
    private static final UUID PARENT = new UUID(0L, 2L);

    @Test
    void originalHeadIsTheOneWithoutAParent() {
        assertTrue(new BranchingState(ID, null, 0).isOriginalHead());
        assertFalse(new BranchingState(ID, PARENT, 1).isOriginalHead());
    }

    @Test
    void theBranchDistanceCounterAccumulatesAndResets() {
        BranchingState state = new BranchingState(ID, null, 0);
        state.incrementBranchDistance(10);
        state.incrementBranchDistance(5);
        assertEquals(15, state.getBlocksSinceLastBranch());
        state.resetBranchCounter();
        assertEquals(0, state.getBlocksSinceLastBranch());
    }

    @Test
    void childBranchIdsAreTrackedAndExposedReadOnly() {
        BranchingState state = new BranchingState(ID, null, 0);
        UUID child = new UUID(7L, 7L);
        state.addChildBranch(child);

        List<UUID> children = state.getChildBranchIds();
        assertEquals(List.of(child), children);
        assertThrows(UnsupportedOperationException.class, () -> children.add(new UUID(9L, 9L)),
                "the exposed child list is unmodifiable");
    }

    @Test
    void eligibilityRequiresTheMinimumDistanceSinceTheLastBranch() {
        int minDistance = new RailwaysUntoldConfig().BRANCH_MIN_DISTANCE;
        // Original head at depth 0: only the distance gate is in question.
        BranchingState state = new BranchingState(ID, null, 0);

        assertFalse(state.isEligibleForBranch(new RailwaysUntoldConfig()), "no distance accrued yet");
        state.incrementBranchDistance(minDistance);
        assertTrue(state.isEligibleForBranch(new RailwaysUntoldConfig()),
                "at the minimum distance an original head may branch");
    }

    @Test
    void eligibilityIsRefusedAtOrBeyondTheMaxBranchDepth() {
        int maxDepth = RailwaysUntoldConfig.getMaxBranchDepth();
        // maxDepth defaults to 3 (> 0); a head already at that depth cannot branch further even with distance.
        BranchingState atCeiling = new BranchingState(ID, null, maxDepth);
        atCeiling.incrementBranchDistance(new RailwaysUntoldConfig().BRANCH_MIN_DISTANCE + 100);
        assertFalse(atCeiling.isEligibleForBranch(new RailwaysUntoldConfig()),
                "depth == maxBranchDepth is over the ceiling (gate is branchDepth < maxDepth)");

        BranchingState belowCeiling = new BranchingState(ID, null, maxDepth - 1);
        belowCeiling.incrementBranchDistance(new RailwaysUntoldConfig().BRANCH_MIN_DISTANCE + 100);
        assertTrue(belowCeiling.isEligibleForBranch(new RailwaysUntoldConfig()), "one below the ceiling may branch");
    }

    @Test
    void setLastBranchJunctionRoundTripsAndClears() {
        BranchingState state = new BranchingState(ID, null, 0);
        state.setLastBranchJunction(new BlockPos(4, 5, 6), Direction.SOUTH);
        assertEquals(new BlockPos(4, 5, 6), state.getLastBranchJunctionPos());
        assertEquals(Direction.SOUTH, state.getLastBranchDirection());
        state.clearLastBranchJunction();
        assertEquals(null, state.getLastBranchJunctionPos());
        assertEquals(null, state.getLastBranchDirection());
    }
}
