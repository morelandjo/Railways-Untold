package com.vodmordia.railwaysuntold.worldgen.placement.decision;

import com.vodmordia.railwaysuntold.worldgen.branching.BranchValidator;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import com.vodmordia.railwaysuntold.worldgen.placement.companion.CompanionValidationService;
import com.vodmordia.railwaysuntold.worldgen.placement.constraint.AvoidanceConstraintService;
import com.vodmordia.railwaysuntold.worldgen.placement.decider.ExplorationTargetDecider;
import com.vodmordia.railwaysuntold.worldgen.village.VillageAssignmentTracker;
import com.vodmordia.railwaysuntold.worldgen.village.VillageTargetingSavedData;

import java.util.Optional;

/**
 * Executes coarse-route decisions for track placement.
 *
 *   1. Village validation - side-effects only; clears invalid / stale village
 *      targets and triggers reassignment. Never returns a decision, so it
 *      doesn't short-circuit subsequent phases.
 *   2. Exploration steering - if the head is in exploration state (no village
 *      target, no custom target), steer toward its exploration target.
 *   3. Branching - if the head is eligible (not disabled, not diagonal), place
 *      a branch here. Checked before terrain so a branch can fire mid-route.
 *   4. Events - if an event fires at this position, place it.
 *   5. Terrain (precision route) - consume the next segment of the head's
 *      installed precision route.
 *
 */
public class CoarseRouteExecutionRule implements PlacementRule {

    private final EventPlacementRule eventRule = new EventPlacementRule();

    public CoarseRouteExecutionRule() {
    }

    @Override
    public Optional<PlacementDecision> decide(DeciderContext context) {
        // A confirmed village target is established at assignment and does not change while the head
        // approaches - its center and spawn distance are fixed, and a generated village does not vanish.
        // Re-validating it every tick would abandon the target on a transient village-cache miss
        // (which churns hard during chunk generation), dropping valid villages and re-targeting in
        // conflicting directions. Genuine invalidation (no approach, collision, layout absent, give-up) is
        // handled event-driven elsewhere, so there is no per-tick village re-validation here.

        // Exploring heads steer toward their exploration target.
        Optional<PlacementDecision> explorationResult = tryExplorationSteering(context);
        if (explorationResult.isPresent()) {
            return Optional.of(applyPostConstraints(explorationResult.get(), context));
        }

        // Eligible heads may branch. Checked before terrain so branching can fire before we commit to
        // the next precision-route segment.
        Optional<PlacementDecision> branchResult = tryBranching(context);
        if (branchResult.isPresent()) {
            return Optional.of(applyPostConstraints(branchResult.get(), context));
        }

        // Events win over plain terrain-following.
        Optional<PlacementDecision> eventResult = eventRule.decide(context);
        if (eventResult.isPresent()) {
            return eventResult;
        }

        // Consume the next segment of the precision route.
        Optional<PlacementDecision> terrainResult = tryTerrainPlanning(context);
        if (terrainResult.isPresent()) {
            return Optional.of(applyPostConstraints(terrainResult.get(), context));
        }

        return Optional.empty();
    }

    /**
     * Handles exploration steering for heads without a village target.
     */
    private Optional<PlacementDecision> tryExplorationSteering(DeciderContext context) {
        if (!context.head().getVillageState().isExploring()) {
            return Optional.empty();
        }

        return ExplorationTargetDecider.tryApproach(
                context.head(),
                context.start(),
                context.direction(),
                context.level());
    }

    /**
     * Checks if a branch should be placed at this position.
     * Runs the same logic as BranchPlacementRule but integrated into the coarse route pipeline
     * so it can execute before terrain planning commits to a segment.
     */
    private Optional<PlacementDecision> tryBranching(DeciderContext context) {
        // Don't branch while the head is in diagonal mode. The diagonal exit curve
        // hasn't been placed yet - branching here would skip it, leaving broken
        // track geometry at the diagonal-to-cardinal transition.
        if (context.head().isDiagonal()) {
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
                context.headManager());

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

    /**
     * Pure precision-route consumption.
     */
    private Optional<PlacementDecision> tryTerrainPlanning(DeciderContext context) {
        com.vodmordia.railwaysuntold.worldgen.planner.noise.PrecisionRoute precision =
                context.head().getTerrainPlanState().getPrecisionRoute();
        if (precision != null && precision.hasRemainingPath()) {
            PlacementDecision decision = com.vodmordia.railwaysuntold.worldgen.planner.PathExecutor.executeNextSegment(
                    precision, context.start(), context.direction(), context.scan(), context.level());
            if (decision != null) {
                return Optional.of(decision);
            }
        }

        return Optional.empty();
    }

    /**
     * Applies AvoidanceConstraintService and CompanionValidationService to a decision.
     */
    private PlacementDecision applyPostConstraints(PlacementDecision decision, DeciderContext context) {
        decision = AvoidanceConstraintService.apply(
                context.head(), decision, context.start(),
                context.direction(), context.scan(), context.level());
        decision = CompanionValidationService.apply(
                context.head(), decision, context.start(),
                context.scan(), context.level());
        return decision;
    }

    @Override
    public String getName() {
        return "CoarseRouteExecution";
    }

    @Override
    public int getPriority() {
        return 40;
    }
}
