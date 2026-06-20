package com.vodmordia.railwaysuntold.worldgen.placement.handler;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.worldgen.branching.BranchTrackCreator;
import com.vodmordia.railwaysuntold.worldgen.branching.BranchValidator;
import com.vodmordia.railwaysuntold.worldgen.branching.ParentBranchDivergence;
import com.vodmordia.railwaysuntold.worldgen.head.TrackExpansionHead;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementConstants;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import com.vodmordia.railwaysuntold.worldgen.placement.companion.CompanionTrackPlacer;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.BezierConnectionPlacer;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.BezierPlacementRequest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.slf4j.Logger;

/**
 * Executor for BRANCH placement decisions.
 *
 */
public class BranchPlacementExecutor extends AbstractPlacementExecutor {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public PlacementDecision.Type getType() {
        return PlacementDecision.Type.BRANCH;
    }

    @Override
    public HandlerResult handle(PlacementContext ctx) {
        boolean sideBySide = RailwaysUntoldConfig.isSideBySideEnabled() && ctx.head().getBranchDepth() == 0;
        Direction branchDir = ctx.decision().getBranchDirection();

        // Determine if branch comes from companion side
        boolean branchFromCompanion = false;
        int companionSeparation = 0;
        Direction companionDir = null;

        if (sideBySide) {
            companionDir = CompanionTrackPlacer.getHeadCompanionDirection(ctx.head(), ctx.expandDir());
            companionSeparation = CompanionTrackPlacer.getCompanionSeparation();
            branchFromCompanion = (branchDir == companionDir);
        }

        // Validate branch spawn at the appropriate position
        BlockPos branchSourcePos = branchFromCompanion
                ? ctx.currentPos().relative(companionDir, companionSeparation)
                : ctx.currentPos();

        BranchValidator.ValidationResult validation =
                BranchValidator.validateSpawn(
                        ctx.head(), ctx.headManager(), branchSourcePos,
                        branchDir, ctx.level());

        if (!validation.success) {
            return handleValidationFailure(ctx);
        }

        if (branchFromCompanion) {
            return handleCompanionBranch(ctx, branchSourcePos);
        } else {
            return handleMainBranch(ctx);
        }
    }

    /**
     * Normal branch from main track. Companion gets a straight (handled by CompanionTrackPlacer).
     */
    private HandlerResult handleMainBranch(PlacementContext ctx) {
        BlockPos branchJunctionPos = ctx.currentPos().relative(ctx.expandDir(), 5);
        BlockPos parentEnd = ctx.currentPos().relative(ctx.expandDir(), PlacementConstants.STANDARD_SEGMENT_LENGTH - 1);

        if (hasCollisionWithExistingTrack(ctx, ctx.currentPos(), parentEnd)) {
            LOGGER.warn("[Branch-Main] Collision detected at {} -> {}, aborting placement",
                    ctx.currentPos(), parentEnd);
            return HandlerResult.failed("Branch parent blocked by existing track");
        }

        TrackExpansionHead head = ctx.head();

        BezierPlacementRequest firstSegmentRequest = BezierPlacementRequest.builder(ctx.currentPos(), branchJunctionPos, ctx.expandDir())
                .config(ctx.config())
                .torchOffset(head.getTorchPlacementOffset())
                .logContext(ctx.decision().getLogContext())
                .onClearingComplete(head::addToTorchPlacementOffset)
                .build();
        BezierConnectionPlacer.BezierConnectionResult firstSegmentResult = BezierConnectionPlacer.place(ctx.level(), firstSegmentRequest);

        if (firstSegmentResult.needsRetry) {
            return deferForBoundingBox(ctx.currentPos(), branchJunctionPos, "Branch-Parent-Seg1");
        }
        if (!firstSegmentResult.success) {
            return HandlerResult.failed("Branch parent segment 1 placement failed");
        }
        Direction branchDir = ctx.decision().getBranchDirection();
        if (!firstSegmentResult.segments.isEmpty()) {
            SupportPlacementService.placeSupportsAlongBezierNearJunction(ctx, firstSegmentResult, ctx.currentPos(), branchJunctionPos, branchJunctionPos, branchDir);
        } else {
            SupportPlacementService.placeSupportsAlongStraight(ctx, ctx.currentPos(), ctx.expandDir(), 5, branchJunctionPos, branchDir);
        }

        // Build branch curve before second parent segment to prevent junction claim conflict
        BranchTrackCreator.BranchResult branchResult = BranchTrackCreator.buildBranch(
                ctx.level(), branchJunctionPos, ctx.expandDir(), ctx.decision().getBranchDirection(),
                ctx.config());

        // Exclude the junction area from the second segment's clearing and decoration
        // to prevent facade walls from overlapping with the branch opening
        int junctionExclRadius = RailwaysUntoldConfig.getHorizontalExpansion() + 1;
        int junctionExclHeight = RailwaysUntoldConfig.getVerticalExpansion() + 1;
        BlockPos junctionExclMin = branchJunctionPos.offset(-junctionExclRadius, -2, -junctionExclRadius);
        BlockPos junctionExclMax = branchJunctionPos.offset(junctionExclRadius, junctionExclHeight, junctionExclRadius);

        BezierPlacementRequest secondSegmentRequest = BezierPlacementRequest.builder(branchJunctionPos, parentEnd, ctx.expandDir())
                .config(ctx.config())
                .torchOffset(head.getTorchPlacementOffset())
                .logContext(ctx.decision().getLogContext())
                .onClearingComplete(head::addToTorchPlacementOffset)
                .clearingExclusionBox(junctionExclMin, junctionExclMax)
                .build();
        BezierConnectionPlacer.BezierConnectionResult secondSegmentResult = BezierConnectionPlacer.place(ctx.level(), secondSegmentRequest);

        if (secondSegmentResult.needsRetry) {
            return deferForBoundingBox(branchJunctionPos, parentEnd, "Branch-Parent-Seg2");
        }
        if (!secondSegmentResult.success) {
            return HandlerResult.failed("Branch parent segment 2 placement failed");
        }
        if (!secondSegmentResult.segments.isEmpty()) {
            SupportPlacementService.placeSupportsAlongBezierNearJunction(ctx, secondSegmentResult, branchJunctionPos, parentEnd, branchJunctionPos, branchDir);
        } else {
            int secondDistance = PlacementConstants.STANDARD_SEGMENT_LENGTH - 1 - 5;
            SupportPlacementService.placeSupportsAlongStraight(ctx, branchJunctionPos, ctx.expandDir(), secondDistance, branchJunctionPos, branchDir);
        }

        if (!branchResult.success) {
            return handleBranchBuildFailure(ctx, parentEnd);
        }

        // Store junction info so subsequent parent segments can suppress end walls
        // near the branch curve (the curve may still be close to the parent decking edge)
        ctx.head().setLastBranchJunction(branchJunctionPos, branchDir);

        return finalizeBranch(ctx, branchResult, parentEnd);
    }

    /**
     * Branch from companion track. The junction is placed on the companion side,
     * and the main track continues straight through.
     */
    private HandlerResult handleCompanionBranch(PlacementContext ctx, BlockPos companionPos) {
        TrackExpansionHead head = ctx.head();
        Direction travelDir = ctx.expandDir();
        Direction branchDir = ctx.decision().getBranchDirection();
        BlockPos mainEnd = ctx.currentPos().relative(travelDir, PlacementConstants.STANDARD_SEGMENT_LENGTH - 1);

        if (hasCollisionWithExistingTrack(ctx, ctx.currentPos(), mainEnd)) {
            LOGGER.warn("[Branch-Companion] Collision detected at {} -> {}, aborting placement",
                    ctx.currentPos(), mainEnd);
            return HandlerResult.failed("Branch parent blocked by existing track");
        }

        // 1. Place main track straight through junction (no junction on main)
        BezierPlacementRequest mainStraightRequest = BezierPlacementRequest.builder(ctx.currentPos(), mainEnd, travelDir)
                .config(ctx.config())
                .torchOffset(head.getTorchPlacementOffset())
                .logContext(ctx.decision().getLogContext())
                .onClearingComplete(head::addToTorchPlacementOffset)
                .build();
        BezierConnectionPlacer.BezierConnectionResult mainResult = BezierConnectionPlacer.place(ctx.level(), mainStraightRequest);

        if (mainResult.needsRetry) {
            return deferForBoundingBox(ctx.currentPos(), mainEnd, "Branch-Companion-MainStraight");
        }
        if (!mainResult.success) {
            return HandlerResult.failed("Branch companion: main straight segment failed");
        }
        if (!mainResult.segments.isEmpty()) {
            SupportPlacementService.placeSupportsAlongBezier(ctx, mainResult, ctx.currentPos(), mainEnd);
        } else {
            SupportPlacementService.placeSupportsAlongStraight(ctx, ctx.currentPos(), travelDir,
                    PlacementConstants.STANDARD_SEGMENT_LENGTH - 1);
        }

        // 2. Place companion track junction segments
        BlockPos companionJunction = companionPos.relative(travelDir, 5);
        BlockPos companionEnd = companionPos.relative(travelDir, PlacementConstants.STANDARD_SEGMENT_LENGTH - 1);

        BezierPlacementRequest compFirstRequest = BezierPlacementRequest.builder(companionPos, companionJunction, travelDir)
                .config(ctx.config())
                .build();
        BezierConnectionPlacer.BezierConnectionResult compFirstResult = BezierConnectionPlacer.place(ctx.level(), compFirstRequest);

        if (!compFirstResult.success) {
            LOGGER.warn("[BRANCH-HANDLER] Companion first segment failed at {}", companionPos);
            return handleBranchBuildFailure(ctx, mainEnd);
        }
        if (!compFirstResult.segments.isEmpty()) {
            SupportPlacementService.placeSupportsAlongBezierNearJunction(ctx, compFirstResult, companionPos, companionJunction, companionJunction, branchDir);
        } else {
            SupportPlacementService.placeSupportsAlongStraight(ctx, companionPos, travelDir, 5, companionJunction, branchDir);
        }

        // 3. Build branch curve from companion junction
        BranchTrackCreator.BranchResult branchResult = BranchTrackCreator.buildBranch(
                ctx.level(), companionJunction, travelDir, ctx.decision().getBranchDirection(),
                ctx.config());

        // 4. Place companion second segment after junction
        // Exclude the junction area to prevent facade walls from overlapping with the branch opening
        int compExclRadius = RailwaysUntoldConfig.getHorizontalExpansion() + 1;
        int compExclHeight = RailwaysUntoldConfig.getVerticalExpansion() + 1;
        BlockPos compExclMin = companionJunction.offset(-compExclRadius, -2, -compExclRadius);
        BlockPos compExclMax = companionJunction.offset(compExclRadius, compExclHeight, compExclRadius);

        BezierPlacementRequest compSecondRequest = BezierPlacementRequest.builder(companionJunction, companionEnd, travelDir)
                .config(ctx.config())
                .clearingExclusionBox(compExclMin, compExclMax)
                .build();
        BezierConnectionPlacer.place(ctx.level(), compSecondRequest);

        if (!branchResult.success) {
            LOGGER.warn("[BRANCH-HANDLER] Companion branch curve failed at {}", companionJunction);
            return handleBranchBuildFailure(ctx, mainEnd);
        }


        return finalizeBranch(ctx, branchResult, mainEnd);
    }

    private HandlerResult finalizeBranch(PlacementContext ctx, BranchTrackCreator.BranchResult branchResult, BlockPos parentEnd) {
        // Place supports along the branch curve, excluding the parent track corridor
        // to prevent branch curve decking from overlapping the parent straight track
        if (branchResult.curveResult != null && !branchResult.curveResult.segments.isEmpty()) {
            SupportPlacementService.placeSupportsAlongBezier(ctx, branchResult.curveResult,
                    branchResult.curveStart, branchResult.branchStartPos,
                    branchResult.curveStart, ctx.expandDir());
            // Fill the fork wedge so the junction deck has no open notch between parent and branch.
            SupportPlacementService.fillBranchGore(ctx, branchResult.curveResult,
                    branchResult.curveStart, ctx.expandDir(), branchResult.branchDirection);
        }

        TrackExpansionHead branchHead = ctx.headManager().createBranch(
                ctx.head(), branchResult.branchStartPos, branchResult.branchDirection,
                ctx.decision().getBranchTargetVillageId(), ctx.decision().getBranchTargetVillageCenter(),
                ctx.level());

        applyBasicStateUpdate(ctx.head(), parentEnd, PlacementConstants.STANDARD_SEGMENT_LENGTH);
        ctx.head().getTerrainPlanState().clearApproachPath();
        // Invalidate the precision route after a branch.
        ctx.head().getTerrainPlanState().setPrecisionRoute(null);
        ctx.head().getTerrainPlanState().setCoarseRoute(null);
        // Keep the parent from chasing the branch onto the same side: an exploring parent whose target
        // lies on the branch's side is steered to the opposite side so the two diverge. A parent committed
        // to a village keeps its target.
        if (ctx.head().getVillageState().isExploring()) {
            BlockPos explore = ctx.head().getVillageState().getExplorationTarget();
            BlockPos diverged = ParentBranchDivergence.steerAwayFromBranch(
                    ctx.head().getPosition(), ctx.expandDir(), branchResult.branchDirection, explore);
            if (!diverged.equals(explore)) {
                ctx.head().getVillageState().setExplorationTarget(diverged);
            }
        }
        // Trigger a fresh replan from the head's new post-branch position.
        BlockPos replanTarget = ctx.head().getVillageState().getApproachWaypoint();
        if (replanTarget == null && ctx.head().getVillageState().hasTargetVillage()) {
            replanTarget = ctx.head().getVillageState().getTargetVillageCenter();
        }
        if (replanTarget == null) {
            // Exploring heads have no village target - fall back to the current
            // exploration target.
            replanTarget = ctx.head().getVillageState().getExplorationTarget();
        }
        if (replanTarget != null) {
            com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRouteFactory.createAndAttach(
                    (net.minecraft.server.level.ServerLevel) ctx.level(), ctx.head(), replanTarget);
        }
        ctx.head().resetBranchCounter();

        ctx.requestExpandHead(branchHead);
        ctx.scheduleParentContinuation();

        return HandlerResult.succeededSkipContinuation();
    }

    private HandlerResult handleValidationFailure(PlacementContext ctx) {
        BlockPos straightEnd = ctx.currentPos().relative(ctx.expandDir(), PlacementConstants.STANDARD_SEGMENT_LENGTH - 1);
        BezierPlacementRequest fallbackRequest = BezierPlacementRequest.builder(ctx.currentPos(), straightEnd, ctx.expandDir())
                .config(ctx.config())
                .torchOffset(ctx.head().getTorchPlacementOffset())
                .logContext(ctx.decision().getLogContext())
                .onClearingComplete(ctx.head()::addToTorchPlacementOffset)
                .build();

        ctx.head().getTerrainPlanState().clearApproachPath();
        return placeBezierAndUpdateState(ctx, fallbackRequest, "Branch-Fallback-Straight", false);
    }

    private HandlerResult handleBranchBuildFailure(PlacementContext ctx, BlockPos parentEnd) {
        applyBasicStateUpdate(ctx.head(), parentEnd, PlacementConstants.STANDARD_SEGMENT_LENGTH);
        ctx.head().getTerrainPlanState().clearApproachPath();
        return HandlerResult.succeeded();
    }
}
