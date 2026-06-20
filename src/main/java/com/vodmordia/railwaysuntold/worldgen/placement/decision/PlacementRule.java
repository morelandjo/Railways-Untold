package com.vodmordia.railwaysuntold.worldgen.placement.decision;

import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;

import java.util.Optional;

/**
 * Interface for placement decision rules.
 * Each rule handles a specific concern (village approach, collision avoidance, etc.)
 * and returns a decision if it can handle the current situation.
 */
public interface PlacementRule {

    /**
     * Attempts to make a placement decision for the current context.
     *
     * @param context The decision context containing head, position, terrain scan, etc.
     * @return An Optional containing the decision if this rule handles the situation,
     *         or empty to let the next rule in the pipeline handle it
     */
    Optional<PlacementDecision> decide(DeciderContext context);

    /**
     * Returns the name of this rule for logging purposes.
     */
    String getName();

    /**
     * Returns the priority of this rule (lower = higher priority).
     * Rules are executed in priority order.
     */
    default int getPriority() {
        return 100;
    }
}
