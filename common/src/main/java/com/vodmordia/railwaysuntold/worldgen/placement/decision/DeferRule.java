package com.vodmordia.railwaysuntold.worldgen.placement.decision;

import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import com.vodmordia.railwaysuntold.worldgen.placement.constraint.AvoidanceConstraintService;

import java.util.Optional;

/**
 * Final rule in the pipeline that creates a defer decision when no other rule handles the situation.
 * Applies avoidance constraints.
 */
public class DeferRule implements PlacementRule {

    @Override
    public Optional<PlacementDecision> decide(DeciderContext context) {
        PlacementDecision decision = PlacementDecision.defer();

        decision = AvoidanceConstraintService.apply(
                context.head(), decision, context.start(),
                context.direction(), context.scan(), context.level());

        return Optional.of(decision);
    }

    @Override
    public String getName() {
        return "Defer";
    }

    @Override
    public int getPriority() {
        return Integer.MAX_VALUE;
    }
}
