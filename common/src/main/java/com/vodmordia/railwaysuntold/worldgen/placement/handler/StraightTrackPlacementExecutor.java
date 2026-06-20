package com.vodmordia.railwaysuntold.worldgen.placement.handler;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementConstants;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementLogContext;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.StraightTrackPlacer;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

/**
 * Executor for STRAIGHT track placement decisions.
 */
public class StraightTrackPlacementExecutor extends AbstractPlacementExecutor {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public PlacementDecision.Type getType() {
        return PlacementDecision.Type.STRAIGHT;
    }

    /**
     * Resolves the straight run length to request: the decision's distance when positive, else the
     * default segment length - floored at 2 so a degenerate zero/one-block straight is never requested.
     */
    // Package-private for StraightTrackPlacementExecutorTest, which gates this request guard (PL4).
    static int resolveStraightDistance(int decisionDistance) {
        int distance = decisionDistance > 0 ? decisionDistance : PlacementConstants.DEFAULT_STRAIGHT_LENGTH;
        return Math.max(2, distance);
    }

    @Override
    public HandlerResult handle(PlacementContext ctx) {
        int straightDistance = resolveStraightDistance(ctx.decision().getDistance());

        BlockPos straightEnd = ctx.currentPos().relative(ctx.expandDir(), straightDistance);

        if (hasCollisionWithExistingTrack(ctx, ctx.currentPos(), straightEnd)) {
            LOGGER.warn("[Straight] Collision detected at {} -> {}, aborting placement",
                    ctx.currentPos(), straightEnd);
            return HandlerResult.failed("Straight blocked by existing track");
        }

        StraightTrackPlacer.StraightPlacementResult result = StraightTrackPlacer.place(
                ctx.level(), ctx.currentPos(), straightEnd, ctx.expandDir());

        if (result.needsRetry) {
            return deferForBoundingBox(ctx.currentPos(), straightEnd, "Straight");
        }

        if (!result.success) {
            return HandlerResult.failed("Straight track placement failed");
        }

        BlockPos startPos = ctx.currentPos();

        applyBasicStateUpdate(ctx.head(), result.endpoint, result.tracksPlaced);
        SupportPlacementService.placeSupportsAlongStraight(ctx, startPos, ctx.expandDir(), result.tracksPlaced);


        PlacementLogContext logCtx = ctx.decision().getLogContext();
        if (logCtx == null) {
            logCtx = new PlacementLogContext(ctx.head().getHeadNumber(), "CoarseRouteExecution");
        }

        return HandlerResult.succeeded();
    }
}
