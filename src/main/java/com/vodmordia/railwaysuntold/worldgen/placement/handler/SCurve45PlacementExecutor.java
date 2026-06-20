package com.vodmordia.railwaysuntold.worldgen.placement.handler;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.util.placement.SCurve45Geometry;
import com.vodmordia.railwaysuntold.worldgen.integration.create.SCurve45TrackPlacer;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.BezierSegmentExtractor;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

/**
 * Executor for SCURVE_45 placement decisions.
 * Places a 45-degree S-curve for smooth lateral displacement while maintaining heading.
 */
public class SCurve45PlacementExecutor extends AbstractPlacementExecutor {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public PlacementDecision.Type getType() {
        return PlacementDecision.Type.SCURVE_45;
    }

    @Override
    public HandlerResult handle(PlacementContext ctx) {
        PlacementDecision decision = ctx.decision();

        int radius = decision.getScurve45Radius();
        int diagonalLength = decision.getScurve45DiagonalLength();
        boolean shiftLeft = decision.isScurve45ShiftLeft();
        int elevationChange = decision.getElevationChange();

        BlockPos start = ctx.currentPos();
        BlockPos end = decision.getEnd();
        if (end != null && hasCollisionWithExistingTrack(ctx, start, end)) {
            LOGGER.warn("[SCurve45] Collision detected at {} -> {}, aborting placement", start, end);
            return HandlerResult.failed("S-curve blocked by existing track");
        }

        SCurve45TrackPlacer.SCurve45Result result = SCurve45TrackPlacer.place(
                ctx.level(),
                ctx.currentPos(),
                ctx.expandDir(),
                radius,
                diagonalLength,
                shiftLeft,
                elevationChange,
                ctx.head().getHeadId()
        );

        if (result.needsRetry) {
            return deferForBoundingBox(ctx.currentPos(), decision.getEnd(), "SCurve45");
        }
        if (!result.success) {
            return HandlerResult.failed("S-curve placement failed");
        }

        applyBasicStateUpdate(ctx.head(), result.endpoint, calculateDistance(decision));

        // Queue terrain clearing for all bezier connections in the S-curve
        queueClearingForConnections(ctx, result);

        // Place ground supports along all bezier connections
        placeSupportsForConnections(ctx, result);

        // Lateral shift invalidates the current terrain plan
        ctx.head().getTerrainPlanState().clearApproachPath();

        return HandlerResult.succeeded();
    }

    private void queueClearingForConnections(PlacementContext ctx, SCurve45TrackPlacer.SCurve45Result result) {
        if (result.connections.isEmpty()) {
            return;
        }
        for (Object connection : result.connections) {
            BezierSegmentExtractor.BezierEndpoints endpoints = BezierSegmentExtractor.extractEndpoints(connection);
            BlockPos connStart = endpoints != null ? endpoints.first() : ctx.currentPos();
            BlockPos connEnd = endpoints != null ? endpoints.second() : result.endpoint;
            queueBezierClearing(ctx, connection, connStart, connEnd);
        }
    }

    private void placeSupportsForConnections(PlacementContext ctx, SCurve45TrackPlacer.SCurve45Result result) {
        for (Object connection : result.connections) {
            BezierSegmentExtractor.BezierEndpoints endpoints = BezierSegmentExtractor.extractEndpoints(connection);
            BlockPos origin = (endpoints != null) ? endpoints.first() : ctx.currentPos();
            SupportPlacementService.placeSupportsAlongRawBezier(ctx, connection, origin);
        }
    }

    /**
     * Calculates the approximate distance traveled by the S-curve.
     */
    private int calculateDistance(PlacementDecision decision) {
        return SCurve45Geometry.calculateTotalPathLength(
                decision.getScurve45Radius(), decision.getScurve45DiagonalLength());
    }
}
