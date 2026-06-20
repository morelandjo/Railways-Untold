package com.vodmordia.railwaysuntold.worldgen.placement.handler;

import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementLogContext;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.BezierPlacementRequest;
import net.minecraft.core.BlockPos;

/**
 * Executor for BRIDGE placement decisions.
 * Uses custom schematic-based decking and pillars instead of generic ground supports.
 */
public class BridgePlacementExecutor extends AbstractPlacementExecutor {

    @Override
    public PlacementDecision.Type getType() {
        return PlacementDecision.Type.BRIDGE;
    }

    @Override
    public HandlerResult handle(PlacementContext ctx) {
        PlacementDecision decision = ctx.decision();

        if (decision.getEnd() == null) {
            return HandlerResult.failed("Bridge decision missing endpoint");
        }

        var drift = plannerDriftGuard(ctx, decision.getStart(), "bridge");
        if (drift.isPresent()) return drift.get();

        // The bridge is rendered by Create as a single bezier spanning start->end.
        BlockPos bridgeStart = decision.getStart();
        BlockPos bridgeEnd = decision.getEnd();

        // If foreign track crosses this bridge's span partway, cap the bridge short of the crossing so the
        // head advances TO the obstacle and resolves the crossing there - instead of the whole long span
        // failing as "blocked by existing track" and the head junction-terminating at its current tip far
        // short of the actual track (the stalled-head-far-from-the-line bug).
        var cappedEnd = cappedEndOrJunction(ctx, bridgeStart, bridgeEnd, "bridge");
        if (cappedEnd.isEmpty()) {
            return HandlerResult.failed("Bridge blocked by existing track");
        }
        bridgeEnd = cappedEnd.get();

        int elevationChange = bridgeEnd.getY() - bridgeStart.getY();

        
        PlacementLogContext logCtx = decision.getLogContext();
        int horizDist = Math.abs(bridgeEnd.getX() - bridgeStart.getX())
                + Math.abs(bridgeEnd.getZ() - bridgeStart.getZ());
        if (logCtx != null && horizDist > 0) {
            logCtx.withBezierInfo(horizDist);
        }

        BezierPlacementRequest request = BezierPlacementRequest.builder(
                        bridgeStart, bridgeEnd, decision.getDirection(), decision.getDirection())
                .config(ctx.config())
                .elevationChange(elevationChange)
                .torchOffset(0) // Bridges don't need torch offset
                .logContext(logCtx)
                .build();

        // Place bezier without generic supports, then add bridge-specific schematics
        BezierPlacementOutcome outcome = placeBezierWithResult(ctx, request, "Bridge");

        if (outcome.handlerResult().success()) {
            if (!outcome.bezierResult().segments.isEmpty()) {
                SupportPlacementService.placeBridgeSupportsAlongBezier(ctx, outcome.bezierResult(), outcome.startPos());
            } else if (outcome.distance() > 0) {
                // Straight-line fallback: place bridge supports along the straight path
                SupportPlacementService.placeSupportsAlongStraight(ctx, outcome.startPos(),
                        outcome.startDirection(), outcome.distance());
            }


        }

        return outcome.handlerResult();
    }
}
