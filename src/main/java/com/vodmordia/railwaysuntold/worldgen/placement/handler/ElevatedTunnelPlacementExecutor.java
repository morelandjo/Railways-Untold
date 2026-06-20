package com.vodmordia.railwaysuntold.worldgen.placement.handler;

import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.BezierPlacementRequest;

/**
 * Executor for ELEVATED_TUNNEL placement decisions.
 */
public class ElevatedTunnelPlacementExecutor extends AbstractPlacementExecutor {

    @Override
    public PlacementDecision.Type getType() {
        return PlacementDecision.Type.ELEVATED_TUNNEL;
    }

    @Override
    public HandlerResult handle(PlacementContext ctx) {
        PlacementDecision decision = ctx.decision();

        var drift = plannerDriftGuard(ctx, decision.getStart(), "elevated-tunnel");
        if (drift.isPresent()) return drift.get();

        net.minecraft.core.BlockPos start = decision.getStart();
        net.minecraft.core.BlockPos end = decision.getEnd();

        // Cap short of any foreign track crossing this span so the head advances to the obstacle and
        // resolves there, instead of the whole span failing as blocked and stalling far short.
        var cappedEnd = cappedEndOrJunction(ctx, start, end, "elevated-tunnel");
        if (cappedEnd.isEmpty()) {
            return HandlerResult.failed("ElevatedTunnel blocked by existing track");
        }
        end = cappedEnd.get();
        int elevationChange = end.getY() - start.getY();

        // Compute slopes for smooth normal matching
        int horizDist = Math.abs(end.getX() - start.getX()) + Math.abs(end.getZ() - start.getZ());
        double currentSlope = (horizDist > 0) ? (double) elevationChange / horizDist : 0.0;

        BezierPlacementRequest request = BezierPlacementRequest.builder(start, end, decision.getDirection())
                .config(ctx.config())
                .elevationChange(elevationChange)
                .incomingSlope(ctx.head().getLastSegmentSlope())
                .outgoingSlope(currentSlope)
                .torchOffset(ctx.head().getTorchPlacementOffset())
                .logContext(decision.getLogContext())
                .onClearingComplete(ctx.head()::addToTorchPlacementOffset)
                .build();

        // Place supports along the run: terrain fill (ballast) where there is ground just below the
        // track, and bridge decking + pillars where the track spans open air (a cave or void). Without
        // this, a tunnel that crosses a cave leaves the track suspended over the void with no support.
        return placeBezierAndUpdateState(ctx, request, "ElevatedTunnel", true);
    }
}
