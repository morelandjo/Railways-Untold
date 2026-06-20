package com.vodmordia.railwaysuntold.worldgen.placement.decision;

import com.vodmordia.railwaysuntold.worldgen.branching.BranchValidator;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import com.vodmordia.railwaysuntold.worldgen.placement.constraint.AvoidanceConstraintService;
import com.vodmordia.railwaysuntold.worldgen.village.VillageAssignmentTracker;
import com.vodmordia.railwaysuntold.worldgen.village.VillageTargetingSavedData;

import java.util.Optional;

/**
 * Rule that handles placement of branch tracks.
 */
public class BranchPlacementRule implements PlacementRule {

    @Override
    public Optional<PlacementDecision> decide(DeciderContext context) {
        if (context.head().getVillageState().hasStationApproachPath()) {
            return Optional.empty();
        }

        VillageAssignmentTracker tracker = VillageTargetingSavedData.get(context.level()).getAssignmentTracker();

        BranchValidator.BranchDecision branchDecision = BranchValidator.validateAndDecide(
                context.head(),
                context.start(),
                context.direction(),
                context.scan(),
                context.level(),
                context.config(),
                tracker,
                null);

        if (branchDecision == null || !branchDecision.approved) {
            return Optional.empty();
        }

        PlacementDecision decision = PlacementDecision.branch(
                context.start(), 16, context.direction(),
                branchDecision.branchDirection, context.scan());

        if (branchDecision.targetVillage != null) {
            decision = decision.withBranchTargetVillage(
                    branchDecision.targetVillage.villageId,
                    branchDecision.targetVillage.center);
        }

        decision = AvoidanceConstraintService.apply(
                context.head(), decision, context.start(),
                context.direction(), context.scan(), context.level());

        return Optional.of(decision);
    }

    @Override
    public String getName() {
        return "BranchPlacement";
    }

    @Override
    public int getPriority() {
        return 60;
    }
}
