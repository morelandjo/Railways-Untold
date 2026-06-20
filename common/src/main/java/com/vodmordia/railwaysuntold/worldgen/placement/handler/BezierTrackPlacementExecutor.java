package com.vodmordia.railwaysuntold.worldgen.placement.handler;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.block.BlockTypeUtil;
import com.vodmordia.railwaysuntold.util.block.ChunkSafeBlockAccess;
import com.vodmordia.railwaysuntold.util.track.SlopeValidator;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.BezierPlacementRequest;
import com.vodmordia.railwaysuntold.worldgen.village.StationSchematicCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

/**
 * Executor for BEZIER track placement decisions.
 */
public class BezierTrackPlacementExecutor extends AbstractPlacementExecutor {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public PlacementDecision.Type getType() {
        return PlacementDecision.Type.BEZIER;
    }

    @Override
    public HandlerResult handle(PlacementContext ctx) {
        Direction bezierDir = ctx.decision().getDirection() != null ? ctx.decision().getDirection() : ctx.expandDir();

        // Drift handling: a small purely-forward drift (the head is a bit further along its heading than
        // the planner's stale start - e.g. a branch junction was inserted between the compiled segments,
        // advancing the tip) re-anchors to the live tip, which is where the real track ends, so the bezier
        // connects and we skip the halt->replan loop the guard otherwise spins (which can strand the head
        // idle). A genuine drift (lateral/backward/large) still halts so the orchestrator replans.
        BlockPos start = reanchorForwardDrift(ctx, ctx.decision().getStart(), ctx.decision().getEnd(), bezierDir, "bezier");
        if (start == null) {
            return plannerDriftGuard(ctx, ctx.decision().getStart(), "bezier")
                    .orElse(HandlerResult.failed("planner start drift (bezier)"));
        }

        // Trust the plan's endpoint Y.
        BlockPos bezierEnd = ctx.decision().getEnd();

        // Backstop slope clamp. Slope is enforced by construction upstream - the precision
        // emit sites clamp every segment and PathPlanner clamps its approach - so a too-steep
        // bezier should not reach here. If one does (an upstream defect), clamp the endpoint Y
        // to the slope limit and place it rather than stalling the head.
        int rawElevation = bezierEnd != null && start != null
                ? bezierEnd.getY() - start.getY()
                : ctx.decision().getElevationChange();
        int horizDist = getHorizontalDistance(start, bezierEnd, ctx.decision().getDistance());
        int elevationChange = rawElevation;
        if (start != null && bezierEnd != null && !SlopeValidator.isValidSlope(horizDist, rawElevation)) {
            int clampedEndY = SlopeValidator.clampEndYToSlopeLimit(start.getY(), bezierEnd.getY(), horizDist);
            LOGGER.warn("[BEZIER-CLAMP] Head {} at {}: elevation {} over {} blocks exceeds slope limit {} - clamping endY {}->{} and proceeding (upstream produced infeasible bezier, start={}, end={})",
                    ctx.head().getHeadNumber(), start, rawElevation, horizDist,
                    RailwaysUntoldConfig.getMaxSlopeRatio(), bezierEnd.getY(), clampedEndY, start, bezierEnd);
            bezierEnd = new BlockPos(bezierEnd.getX(), clampedEndY, bezierEnd.getZ());
            elevationChange = clampedEndY - start.getY();
        }

        // Cap short of any foreign track crossing this span so the head advances to the obstacle and
        // resolves there, instead of the whole span failing as blocked and stalling far short.
        if (start != null && bezierEnd != null) {
            var cappedEnd = cappedEndOrJunction(ctx, start, bezierEnd, "bezier");
            if (cappedEnd.isEmpty()) {
                return HandlerResult.failed("Bezier blocked by existing track");
            }
            if (!cappedEnd.get().equals(bezierEnd)) {
                bezierEnd = cappedEnd.get();
                elevationChange = bezierEnd.getY() - start.getY();
            }
        }

        HandlerResult result = attemptBezierPlacement(ctx, start, bezierEnd, bezierDir, elevationChange);

        // Tunnel fallback: if the bezier failed because its endpoint is buried in solid
        // rock (noise underestimated terrain height - actual mountain is taller than the
        // noise sampler predicted), don't degrade the Y trajectory by lowering it.
        if (!result.success() && !result.shouldDefer() && bezierEnd != null
                && isEndpointBuriedInRock(ctx, bezierEnd)) {
            result = attemptTunnelPlacement(ctx, start, bezierEnd, bezierDir, elevationChange);
            if (result.success()) {
                if (bezierDir == ctx.expandDir()) {
                    ctx.head().resetBlocksSinceLastLateral();
                }
                return result;
            }
        }

        // No elevation-reduction fallback: a failed placement is left failed so the orchestrator
        // replans from the head's actual position (EXECUTE-HALT). Retrying at reduced elevation
        // would assume Create rejected a too-steep grade, but slope is clamped to the config limit
        // above ([BEZIER-CLAMP]) and Create's connector builds a bezier for any geometry without a
        // slope check, so that never holds; it would only land track at an unplanned Y and force a
        // drift-halt + replan anyway.

        if (result.success() && bezierDir == ctx.expandDir()) {
            ctx.head().resetBlocksSinceLastLateral();
        }

        return result;
    }

    /**
     * Attempts a bezier placement with the given endpoint and elevation.
     */
    private HandlerResult attemptBezierPlacement(PlacementContext ctx, BlockPos adjustedStart,
                                                  BlockPos bezierEnd,
                                                  Direction bezierDir, int elevationChange) {
        // Compute slopes for smooth normal matching at segment junctions
        int horizDist = getHorizontalDistance(adjustedStart, bezierEnd, 0);
        double currentSlope = (horizDist > 0) ? (double) elevationChange / horizDist : 0.0;
        double incomingSlope = ctx.head().getLastSegmentSlope();

        var builder = createBezierRequestBuilder(ctx, adjustedStart, bezierEnd, bezierDir)
                .elevationChange(elevationChange)
                .incomingSlope(incomingSlope)
                .outgoingSlope(currentSlope);

        if (ctx.head().getVillageState().isStationPlaced()) {
            var placedStation = ctx.head().getVillageState().getPlacedStation();
            if (placedStation != null && placedStation.stationPosition != null) {
                var selectedStation = ctx.head().getVillageState().getSelectedStation();
                int[] dims = selectedStation != null ? selectedStation.getDimensions() : StationSchematicCache.getMaxDimensions(ctx.level());
                if (dims != null) {
                    BlockPos stationMin = placedStation.stationPosition;
                    BlockPos stationMax = stationMin.offset(dims[0] - 1, dims[1] - 1, dims[2] - 1);
                    builder.clearingExclusionBox(stationMin, stationMax);
                }
            }
        }

        BezierPlacementRequest request = builder.build();
        return placeBezierAndUpdateState(ctx, request, "Bezier", true);
    }

    /**
     * Gets the horizontal distance for slope calculation.
     * Uses the decision's distance field if available, otherwise computes from positions.
     */
    private int getHorizontalDistance(BlockPos start, BlockPos end, int decisionDistance) {
        if (start != null && end != null) {
            return Math.abs(end.getX() - start.getX()) + Math.abs(end.getZ() - start.getZ());
        }
        if (decisionDistance > 0) {
            return decisionDistance;
        }
        return 0;
    }

    /**
     * True if the bezier endpoint sits inside solid rock - the planner's noise sampler
     * underestimated terrain height and the planned track Y is buried inside a mountain.
     * Used to decide whether to switch from bezier to tunnel placement.
     */
    private boolean isEndpointBuriedInRock(PlacementContext ctx, BlockPos endpoint) {
        BlockState state = ChunkSafeBlockAccess.getBlockStateNonBlocking(ctx.level(), endpoint);
        if (state == null) return false;
        if (BlockTypeUtil.isAirOrLiquid(state)) return false;
        return state.isSolid();
    }

    /**
     * Retries placement as a tunnel
     */
    private HandlerResult attemptTunnelPlacement(PlacementContext ctx, BlockPos start,
                                                   BlockPos bezierEnd, Direction bezierDir,
                                                   int elevationChange) {
        // Synchronously clear the endpoint to air so the subsequent endpoint-track
        // placement and connector validation don't trip over solid rock at that pos.
        ChunkSafeBlockAccess.setBlockStateNonBlocking(
                ctx.level(), bezierEnd, Blocks.AIR.defaultBlockState(), true);

        int horizDist = getHorizontalDistance(start, bezierEnd, 0);
        double currentSlope = (horizDist > 0) ? (double) elevationChange / horizDist : 0.0;
        double incomingSlope = ctx.head().getLastSegmentSlope();

        BezierPlacementRequest request = createBezierRequestBuilder(ctx, start, bezierEnd, bezierDir)
                .elevationChange(elevationChange)
                .incomingSlope(incomingSlope)
                .outgoingSlope(currentSlope)
                .build();

        return placeBezierAndUpdateState(ctx, request, "Bezier->Tunnel", false);
    }

}
