package com.vodmordia.railwaysuntold.worldgen.placement.decision;

import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementLogContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates a pipeline of placement rules.
 * Rules are executed in priority order until one returns a decision.
 */
public class PlacementPipeline {

    private final List<PlacementRule> rules;

    private PlacementPipeline(List<PlacementRule> rules) {
        this.rules = rules;
    }

    /**
     * Executes the rule pipeline, returning the first non-empty decision.
     *
     * @param context The decision context
     * @return The placement decision, or a defer decision if no rule handles the situation
     */
    public PlacementDecision execute(DeciderContext context) {
        for (PlacementRule rule : rules) {
            Optional<PlacementDecision> result = rule.decide(context);
            if (result.isPresent()) {
                PlacementDecision decision = result.get();
                return attachLogContext(decision, context, rule.getName());
            }
        }
        return PlacementDecision.defer();
    }

    private PlacementDecision attachLogContext(PlacementDecision decision, DeciderContext context, String source) {
        if (decision.getType() == PlacementDecision.Type.DEFER ||
            decision.getType() == PlacementDecision.Type.INVALID) {
            return decision;
        }

        PlacementLogContext logCtx = new PlacementLogContext(context.head().getHeadNumber(), source);

        if (decision.getType() == PlacementDecision.Type.CURVE && decision.getCurveParams() != null) {
            logCtx.withCurveInfo(decision.getCurveParams().radius, decision.getCurveParams().turnLeft);
        } else if (decision.getType() == PlacementDecision.Type.BEZIER) {
            int bezierDist = decision.getDistance();
            // Some code paths create BEZIER decisions without setting distance -
            // compute from start/end positions so the log always captures it.
            if (bezierDist <= 0 && decision.getStart() != null && decision.getEnd() != null) {
                bezierDist = decision.getStart().distManhattan(decision.getEnd());
            }
            if (bezierDist > 0) {
                logCtx.withBezierInfo(bezierDist);
            }
        } else if (decision.getType() == PlacementDecision.Type.BRANCH && decision.getBranchDirection() != null) {
            logCtx.withBranchInfo(decision.getBranchDirection());
        }

        if (context.head().getVillageState().hasTargetVillage()) {
            // Log the actual steering target (approach waypoint when far, station when close)
            net.minecraft.core.BlockPos approachWp = context.head().getVillageState().getApproachWaypoint();
            net.minecraft.core.BlockPos stationTarget = context.head().getVillageState().getStationTarget();
            double distToStation = com.vodmordia.railwaysuntold.util.spatial.DirectionUtil.horizontalDistance(
                    context.start(), stationTarget);
            net.minecraft.core.BlockPos logTarget = (approachWp != null && distToStation > 150)
                    ? approachWp : stationTarget;
            logCtx.withVillageTarget(logTarget);
        }

        return decision.withLogContext(logCtx);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final List<PlacementRule> rules = new ArrayList<>();

        public Builder add(PlacementRule rule) {
            rules.add(rule);
            return this;
        }

        public PlacementPipeline build() {
            List<PlacementRule> sorted = new ArrayList<>(rules);
            sorted.sort(Comparator.comparingInt(PlacementRule::getPriority));
            return new PlacementPipeline(sorted);
        }
    }
}
