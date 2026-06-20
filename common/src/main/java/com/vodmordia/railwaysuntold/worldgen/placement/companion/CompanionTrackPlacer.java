package com.vodmordia.railwaysuntold.worldgen.placement.companion;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.placement.SCurve45Geometry;
import com.vodmordia.railwaysuntold.util.spatial.DirectionUtil;
import com.vodmordia.railwaysuntold.worldgen.head.TrackExpansionHead;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementConstants;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import com.vodmordia.railwaysuntold.worldgen.placement.decider.CurveParameterDecider;
import com.vodmordia.railwaysuntold.worldgen.placement.handler.PlacementContext;
import com.vodmordia.railwaysuntold.worldgen.placement.handler.SupportPlacementService;
import com.vodmordia.railwaysuntold.worldgen.placement.handler.TunnelPlacementExecutor;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.BezierConnectionPlacer;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.BezierPlacementRequest;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.StraightTrackPlacer;
import com.vodmordia.railwaysuntold.worldgen.placement.support.SupportConstants;
import com.vodmordia.railwaysuntold.worldgen.terrain.clearing.DeferredTerrainClearer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

/**
 * Places a companion (parallel) track alongside the primary track for side-by-side dual track mode.
 *
 */
public final class CompanionTrackPlacer {

    private static final Logger LOGGER = LogUtils.getLogger();

    private CompanionTrackPlacer() {
    }

    /**
     * Calculates the separation distance between the primary and companion tracks.
     *
     * @return separation in blocks: max(5, horizontalExpansion * 2)
     */
    public static int getCompanionSeparation() {
        return Math.max(5, RailwaysUntoldConfig.getHorizontalExpansion() * 2);
    }

    /**
     * Determines whether the companion track is on the LEFT side of the travel direction
     * for a given head, based on its initial direction. This is computed once and stays
     * consistent through all curves because LEFT/RIGHT rotate with the heading.
     *
     */
    public static boolean isCompanionOnLeft(Direction initialDirection) {
        return initialDirection == Direction.SOUTH || initialDirection == Direction.WEST;
    }

    /**
     * Returns the canonical companion offset direction for a given travel direction at the start.
     * Used only for the starting train segment where there is no head context.
     */
    public static Direction getCompanionOffsetDirection(Direction travelDirection) {
        return switch (travelDirection) {
            case NORTH, SOUTH -> Direction.EAST;
            case EAST, WEST -> Direction.SOUTH;
            default -> Direction.EAST;
        };
    }

    /**
     * Gets the companion offset direction for a specific head's current travel direction.
     */
    public static Direction getHeadCompanionDirection(TrackExpansionHead head, Direction currentTravelDir) {
        boolean onLeft = isCompanionOnLeft(head.getInitialDirection());
        return onLeft ? DirectionUtil.getLeftDirection(currentTravelDir)
                : DirectionUtil.getRightDirection(currentTravelDir);
    }

    /**
     * Offsets a position toward the companion side for a given head.
     */
    private static BlockPos offsetForHead(BlockPos pos, TrackExpansionHead head, Direction travelDir, int separation) {
        Direction offsetDir = getHeadCompanionDirection(head, travelDir);
        return pos.relative(offsetDir, separation);
    }

    /**
     * Checks whether a curve turns toward the companion side for a given head.
     */
    private static boolean curveTurnsTowardCompanion(TrackExpansionHead head, Direction travelDir, boolean turnLeft) {
        Direction companionDir = getHeadCompanionDirection(head, travelDir);
        Direction turnDir = turnLeft ? DirectionUtil.getLeftDirection(travelDir)
                : DirectionUtil.getRightDirection(travelDir);
        return turnDir == companionDir;
    }

    /**
     * Main dispatch: places a companion track segment matching the primary placement decision.
     */
    public static void placeCompanionForDecision(ServerLevel level, PlacementDecision decision,
                                                  TrackExpansionHead head, PlacementContext ctx) {
        // Only main line heads get companion tracks - branches are single track
        if (head.getBranchDepth() > 0) {
            return;
        }

        try {
            switch (decision.getType()) {
                case STRAIGHT -> placeCompanionStraight(level, decision, head, ctx);
                case CURVE -> placeCompanionCurve(level, decision, head, ctx);
                case BEZIER -> placeCompanionBezier(level, decision, head, ctx);
                case TUNNEL -> placeCompanionTunnel(level, decision, head, ctx);
                case BRIDGE -> placeCompanionBridge(level, decision, head, ctx);
                case ELEVATED_TUNNEL -> placeCompanionElevatedTunnel(level, decision, head, ctx);
                case SCURVE_45 -> placeCompanionSCurve45(level, decision, head, ctx);
                case STATION -> placeCompanionStation();
                case BRANCH -> placeCompanionBranch(level, decision, head, ctx);
                default -> {}
            }
        } catch (Exception e) {
            LOGGER.warn("[COMPANION] Failed to place companion for {} at {}: {}",
                    decision.getType(), decision.getStart(), e.getMessage(), e);
        }
    }

    private static void placeCompanionStraight(ServerLevel level, PlacementDecision decision,
                                                TrackExpansionHead head, PlacementContext ctx) {
        int separation = getCompanionSeparation();
        Direction dir = ctx.expandDir();
        BlockPos start = offsetForHead(decision.getStart(), head, dir, separation);

        // Use head's actual position after handler fallbacks
        BlockPos actualPrimaryEnd = head.getPosition();
        BlockPos end = offsetForHead(actualPrimaryEnd, head, dir, separation);


        placeStraightOrDefer(level, start, end, dir);
    }

    /**
     * Places a straight companion track, deferring if placement fails.
     */
    private static void placeStraightOrDefer(ServerLevel level, BlockPos start, BlockPos end, Direction dir) {
        if (tryPlaceStraight(level, start, end, dir)) {
            return;
        }
        DeferredCompanionQueue.enqueue(
                () -> tryPlaceStraight(level, start, end, dir),
                start, end, "straight");
    }

    private static boolean tryPlaceStraight(ServerLevel level, BlockPos start, BlockPos end, Direction dir) {
        StraightTrackPlacer.StraightPlacementResult result = StraightTrackPlacer.place(level, start, end, dir);
        if (result.success) {
            DeferredTerrainClearer.queueStraightClearing(level, start, end, dir, null);
            return true;
        }
        return false;
    }

    private static void placeCompanionCurve(ServerLevel level, PlacementDecision decision,
                                             TrackExpansionHead head, PlacementContext ctx) {
        CurveParameterDecider.CurveParameters primaryParams = decision.getCurveParams();
        if (primaryParams == null) {
            return;
        }

        int separation = getCompanionSeparation();
        Direction dir = primaryParams.trackDirection;

        boolean turnsToward = curveTurnsTowardCompanion(head, dir, primaryParams.turnLeft);
        int companionRadius;
        if (turnsToward) {
            companionRadius = primaryParams.radius - separation;
            if (companionRadius < RailwaysUntoldConfig.getMinCurveRadius()) {
                LOGGER.warn("[COMPANION] Curve radius {} too small for companion (min={}), skipping",
                        companionRadius, RailwaysUntoldConfig.getMinCurveRadius());
                return;
            }
        } else {
            companionRadius = primaryParams.radius + separation;
        }

        BlockPos companionStart = offsetForHead(primaryParams.start, head, dir, separation);


        CurveParameterDecider.CurveParameters companionParams =
                CurveParameterDecider.createWithExactRadius(
                        companionStart, dir, companionRadius,
                        primaryParams.turnLeft, primaryParams.elevationChange);

        Direction endDir = primaryParams.turnLeft
                ? DirectionUtil.getLeftDirection(dir)
                : DirectionUtil.getRightDirection(dir);

        BezierPlacementRequest request = BezierPlacementRequest.builder(
                        companionParams.start, companionParams.end, dir, endDir)
                .elevationChange(primaryParams.elevationChange)
                .performClearing(true)
                .config(ctx.config())
                .build();

        placeBezierOrDefer(level, request, companionStart, companionParams.end, "curve");
    }

    private static void placeCompanionBezier(ServerLevel level, PlacementDecision decision,
                                              TrackExpansionHead head, PlacementContext ctx) {
        int separation = getCompanionSeparation();
        Direction dir = ctx.expandDir();
        BlockPos start = offsetForHead(decision.getStart(), head, dir, separation);

        // Use the head's actual position (updated by the handler after any fallbacks)
        // instead of decision.getEnd() which may be the original planned endpoint
        BlockPos actualPrimaryEnd = head.getPosition();
        BlockPos end = offsetForHead(actualPrimaryEnd, head, dir, separation);
        int elevationChange = actualPrimaryEnd.getY() - decision.getStart().getY();


        BezierPlacementRequest request = BezierPlacementRequest.builder(start, end, dir)
                .elevationChange(elevationChange)
                .performClearing(true)
                .config(ctx.config())
                .build();

        placeBezierOrDefer(level, request, start, end, "bezier");
    }

    private static void placeCompanionTunnel(ServerLevel level, PlacementDecision decision,
                                              TrackExpansionHead head, PlacementContext ctx) {
        int separation = getCompanionSeparation();
        Direction dir = decision.getDirection() != null ? decision.getDirection() : ctx.expandDir();
        BlockPos start = offsetForHead(decision.getStart(), head, dir, separation);

        // Use the same ravine-shortened tunnel length as the primary
        int tunnelLength = TunnelPlacementExecutor.findTunnelEndpointBeforeRavine(
                decision.getScan(), decision.getStart().getY(), SupportConstants.TUNNEL_LENGTH);
        BlockPos end = start.relative(dir, tunnelLength);


        placeStraightOrDefer(level, start, end, dir);
    }

    private static void placeCompanionBridge(ServerLevel level, PlacementDecision decision,
                                              TrackExpansionHead head, PlacementContext ctx) {
        int separation = getCompanionSeparation();
        Direction dir = decision.getDirection() != null ? decision.getDirection() : ctx.expandDir();
        BlockPos start = offsetForHead(decision.getStart(), head, dir, separation);

        // Use head's actual position after handler fallbacks
        BlockPos actualPrimaryEnd = head.getPosition();
        BlockPos end = offsetForHead(actualPrimaryEnd, head, dir, separation);
        int elevationChange = actualPrimaryEnd.getY() - decision.getStart().getY();


        BezierPlacementRequest request = BezierPlacementRequest.builder(start, end, dir)
                .elevationChange(elevationChange)
                .performClearing(true)
                .config(ctx.config())
                .build();

        BezierConnectionPlacer.BezierConnectionResult result = BezierConnectionPlacer.place(level, request);
        if (result.success) {
            SupportPlacementService.placeSupportsAlongBezier(ctx, result, start);
        } else {
            // Bridge deferred retry won't place supports (minor visual issue, not functional)
            DeferredCompanionQueue.enqueue(
                    () -> tryPlaceBezier(level, request),
                    start, end, "bridge");
        }
    }

    private static void placeCompanionElevatedTunnel(ServerLevel level, PlacementDecision decision,
                                                      TrackExpansionHead head, PlacementContext ctx) {
        int separation = getCompanionSeparation();
        Direction dir = decision.getDirection() != null ? decision.getDirection() : ctx.expandDir();
        BlockPos start = offsetForHead(decision.getStart(), head, dir, separation);

        // Use head's actual position after handler fallbacks
        BlockPos actualPrimaryEnd = head.getPosition();
        BlockPos end = offsetForHead(actualPrimaryEnd, head, dir, separation);
        int elevationChange = actualPrimaryEnd.getY() - decision.getStart().getY();


        BezierPlacementRequest request = BezierPlacementRequest.builder(start, end, dir)
                .elevationChange(elevationChange)
                .performClearing(true)
                .config(ctx.config())
                .build();

        placeBezierOrDefer(level, request, start, end, "elevated-tunnel");
    }

    /**
     * Places a bezier companion track, deferring if placement fails (e.g., chunks not loaded).
     */
    private static void placeBezierOrDefer(ServerLevel level, BezierPlacementRequest request,
                                            BlockPos start, BlockPos end, String type) {
        if (tryPlaceBezier(level, request)) {
            return;
        }
        DeferredCompanionQueue.enqueue(
                () -> tryPlaceBezier(level, request),
                start, end, type);
    }

    private static boolean tryPlaceBezier(ServerLevel level, BezierPlacementRequest request) {
        BezierConnectionPlacer.BezierConnectionResult result = BezierConnectionPlacer.place(level, request);
        return result.success;
    }

    private static void placeCompanionSCurve45(ServerLevel level, PlacementDecision decision,
                                                TrackExpansionHead head, PlacementContext ctx) {
        int separation = getCompanionSeparation();
        Direction dir = decision.getDirection() != null ? decision.getDirection() : ctx.expandDir();
        BlockPos start = offsetForHead(decision.getStart(), head, dir, separation);

        int radius = decision.getScurve45Radius();
        int diagonalLength = decision.getScurve45DiagonalLength();
        boolean shiftLeft = decision.isScurve45ShiftLeft();
        int elevationChange = decision.getElevationChange();

        BlockPos end = SCurve45Geometry.calculateEndpoint(start, dir, shiftLeft, radius, diagonalLength, elevationChange);


        // First curve: cardinal to diagonal
        BlockPos firstCurveEnd = SCurve45Geometry.calculateFirstCurveEnd(
                start, dir, shiftLeft, radius,
                SCurve45Geometry.calculateEndpoint(start, dir, shiftLeft, radius, 0, 0).getY() - start.getY());

        BezierPlacementRequest firstCurveRequest = BezierPlacementRequest.builder(start, firstCurveEnd, dir, dir)
                .elevationChange(firstCurveEnd.getY() - start.getY())
                .performClearing(true)
                .config(ctx.config())
                .build();

        placeBezierOrDefer(level, firstCurveRequest, start, firstCurveEnd, "scurve45-first");

        // Diagonal segment
        BlockPos diagonalEnd = SCurve45Geometry.calculateDiagonalEndFromFinal(
                start, dir, shiftLeft, radius, diagonalLength, elevationChange);

        BezierPlacementRequest diagRequest = BezierPlacementRequest.builder(firstCurveEnd, diagonalEnd, dir, dir)
                .elevationChange(diagonalEnd.getY() - firstCurveEnd.getY())
                .performClearing(true)
                .config(ctx.config())
                .build();

        placeBezierOrDefer(level, diagRequest, firstCurveEnd, diagonalEnd, "scurve45-diagonal");

        // Second curve: diagonal back to cardinal
        BezierPlacementRequest secondCurveRequest = BezierPlacementRequest.builder(diagonalEnd, end, dir, dir)
                .elevationChange(end.getY() - diagonalEnd.getY())
                .performClearing(true)
                .config(ctx.config())
                .build();

        placeBezierOrDefer(level, secondCurveRequest, diagonalEnd, end, "scurve45-second");
    }

    private static void placeCompanionStation() {
        // Single station shared between both tracks for now - no companion station
    }

    /**
     * Handles companion track for a BRANCH decision.
     * The non-branching track gets a straight segment through the junction area.
     * The branch itself is single-track (no companion).
     *
     */
    private static void placeCompanionBranch(ServerLevel level, PlacementDecision decision,
                                              TrackExpansionHead head, PlacementContext ctx) {
        Direction travelDir = ctx.expandDir();
        Direction companionDir = getHeadCompanionDirection(head, travelDir);
        Direction branchDir = decision.getBranchDirection();

        boolean branchFromCompanion = (branchDir == companionDir);

        if (branchFromCompanion) {
            // Branch comes from companion side - executor already placed junction there.
            return;
        }

        // Branch from main side - companion continues straight through junction area
        int separation = getCompanionSeparation();
        BlockPos start = offsetForHead(decision.getStart(), head, travelDir, separation);
        int distance = decision.getDistance() > 0 ? decision.getDistance()
                : PlacementConstants.STANDARD_SEGMENT_LENGTH;
        BlockPos end = start.relative(travelDir, distance - 1);


        BezierPlacementRequest request = BezierPlacementRequest.builder(start, end, travelDir)
                .config(ctx.config())
                .build();

        placeBezierOrDefer(level, request, start, end, "branch-companion-straight");
    }
}
