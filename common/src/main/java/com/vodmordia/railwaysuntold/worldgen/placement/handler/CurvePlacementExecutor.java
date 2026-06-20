package com.vodmordia.railwaysuntold.worldgen.placement.handler;

import com.vodmordia.railwaysuntold.util.spatial.DirectionUtil;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import com.vodmordia.railwaysuntold.worldgen.placement.decider.CurveParameterDecider;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.BezierPlacementRequest;
import net.minecraft.core.Direction;

/**
 * Executor for CURVE placement decisions.
 */
public class CurvePlacementExecutor extends AbstractPlacementExecutor {

    @Override
    public PlacementDecision.Type getType() {
        return PlacementDecision.Type.CURVE;
    }

    @Override
    public HandlerResult handle(PlacementContext ctx) {
        CurveParameterDecider.CurveParameters curveParams = ctx.decision().getCurveParams();
        boolean isEmergencyCurve = ctx.decision().isEmergencyCurve();

        var drift = plannerDriftGuard(ctx, curveParams.start, "curve");
        if (drift.isPresent()) return drift.get();

        ctx.head().recordCurveTurnDirection(curveParams.turnLeft);

        Direction endDirection = DirectionUtil.getPerpendicularDirection(
                curveParams.trackDirection, curveParams.turnLeft);

        BezierPlacementRequest request = BezierPlacementRequest.builder(
                        curveParams.start, curveParams.end, curveParams.trackDirection, endDirection)
                .config(ctx.config())
                .elevationChange(curveParams.elevationChange)
                .torchOffset(ctx.head().getTorchPlacementOffset())
                .logContext(ctx.decision().getLogContext())
                .onClearingComplete(ctx.head()::addToTorchPlacementOffset)
                .build();

        HandlerResult result = placeBezierAndUpdateState(ctx, request, "Curve", true);

        if (result.success()) {
            ctx.head().onDirectionChange();
            ctx.head().setLastPlacementWasEmergencyCurve(isEmergencyCurve);
        }

        return result;
    }

}
