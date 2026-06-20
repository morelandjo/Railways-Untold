package com.vodmordia.railwaysuntold.worldgen.siding;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.worldgen.head.TrackExpansionHead;
import com.vodmordia.railwaysuntold.worldgen.head.state.SidingState;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.PlannerTestConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Characterizes {@link SidingValidator#validate}'s eligibility gate - the pure check that runs before any
 * world-bound side scoring. The load-bearing property is that an ineligible head is rejected up front, so
 * the validator never touches the level (here null) for a head that hasn't earned a siding yet.
 */
class SidingValidatorTest {

    @BeforeAll
    static void setUp() {
        PlannerTestConfig.bootstrapDefaults();
    }

    @Test
    void rejectsBeforeAnyWorldScoringWhenTheHeadIsNotYetEligible() {
        SidingState state = mock(SidingState.class);
        when(state.isEligible(anyInt())).thenReturn(false);
        TrackExpansionHead head = mock(TrackExpansionHead.class);
        when(head.getSidingState()).thenReturn(state);

        // level == null: the eligibility gate must reject before the world-bound side scoring runs.
        SidingValidator.ValidationResult result = SidingValidator.validate(
                head, BlockPos.ZERO, Direction.EAST, null, RailwaysUntoldConfig.getDefault());

        assertFalse(result.approved);
        assertTrue(result.reason.toLowerCase().contains("straight") || result.reason.toLowerCase().contains("placed"),
                result.reason);
    }
}
