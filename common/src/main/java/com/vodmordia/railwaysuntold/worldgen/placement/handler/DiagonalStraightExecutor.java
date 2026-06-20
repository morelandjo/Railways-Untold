package com.vodmordia.railwaysuntold.worldgen.placement.handler;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.util.spatial.DiagonalDirection;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.DiagonalStraightTrackPlacer;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.DiagonalStraightTrackPlacer.DiagonalPlacementResult;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

/**
 * Executor for DIAGONAL_STRAIGHT decisions.
 * Places diagonal track segments while in diagonal mode.
 */
public class DiagonalStraightExecutor extends AbstractPlacementExecutor {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public PlacementDecision.Type getType() {
        return PlacementDecision.Type.DIAGONAL_STRAIGHT;
    }

    @Override
    public HandlerResult handle(PlacementContext ctx) {
        PlacementDecision decision = ctx.decision();
        BlockPos start = ctx.currentPos();
        BlockPos end = decision.getEnd();
        DiagonalDirection diagonal = decision.getDiagonalDirection();
        int distance = decision.getDistance();

        // Safety net: check for collision before placing
        if (hasCollisionWithExistingTrack(ctx, start, end)) {
            LOGGER.warn("[DIAGONAL-STRAIGHT] Collision detected at {} -> {}, aborting placement", start, end);
            return HandlerResult.failed("Diagonal straight blocked by existing track");
        }

        DiagonalPlacementResult result = DiagonalStraightTrackPlacer.place(
                ctx.level(), start, end, diagonal);

        if (result.needsRetry) {
            return deferForBoundingBox(start, end, "DiagonalStraight");
        }
        if (!result.success) {
            return HandlerResult.failed("Diagonal straight placement failed");
        }

        applyBasicStateUpdate(ctx.head(), result.endpoint, distance);

        // Queue terrain clearing for the diagonal bezier connection
        if (result.connection != null) {
            queueBezierClearing(ctx, result.connection, start, end);
            SupportPlacementService.placeSupportsAlongRawBezier(ctx, result.connection, start);
        }

        return HandlerResult.succeeded();
    }
}
