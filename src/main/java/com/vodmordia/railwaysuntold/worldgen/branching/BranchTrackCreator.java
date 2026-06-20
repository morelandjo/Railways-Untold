package com.vodmordia.railwaysuntold.worldgen.branching;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.block.BlockTypeUtil;
import com.vodmordia.railwaysuntold.util.block.ChunkSafeBlockAccess;
import com.vodmordia.railwaysuntold.util.chunk.ChunkVerificationUtil;
import com.vodmordia.railwaysuntold.util.spatial.DirectionUtil;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.CreateTrackUtil;
import com.vodmordia.railwaysuntold.worldgen.placement.decider.CurveParameterDecider;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.BezierConnectionPlacer;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.BezierPlacementRequest;
import com.vodmordia.railwaysuntold.worldgen.placement.support.SupportConstants;
import com.vodmordia.railwaysuntold.worldgen.terrain.TerrainAnalyzer;
import com.vodmordia.railwaysuntold.worldgen.terrain.TerrainScanner;
import com.vodmordia.railwaysuntold.worldgen.terrain.UndergroundDetector;
import com.vodmordia.railwaysuntold.worldgen.terrain.clearing.util.RemovalUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

/**
 * Creates branch tracks that diverge from the main track.
 */
public class BranchTrackCreator {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int BRANCH_CURVE_ANGLE = 90;
    private static final int RAVINE_SCAN_DISTANCE = 10;

    /**
     * Result of branch construction.
     */
    public static class BranchResult {
        public final BlockPos branchStartPos;  // Where the branch head should start
        public final Direction branchDirection;  // Direction the branch head faces
        public final boolean success;
        public final BezierConnectionPlacer.BezierConnectionResult curveResult;  // For support placement
        public final BlockPos curveStart;  // Start of the curve, for support placement

        private BranchResult(BlockPos branchStartPos, Direction branchDirection, boolean success,
                             BezierConnectionPlacer.BezierConnectionResult curveResult, BlockPos curveStart) {
            this.branchStartPos = branchStartPos;
            this.branchDirection = branchDirection;
            this.success = success;
            this.curveResult = curveResult;
            this.curveStart = curveStart;
        }

        public static BranchResult succeeded(BlockPos pos, Direction dir,
                                              BezierConnectionPlacer.BezierConnectionResult curveResult, BlockPos curveStart) {
            return new BranchResult(pos, dir, true, curveResult, curveStart);
        }

        public static BranchResult failed() {
            return new BranchResult(null, null, false, null, null);
        }
    }

    /**
     * Constructs a branch that curves off from parent track.
     *
     * @param level           The server level
     * @param parentPos       Parent track position where branch splits
     * @param parentDirection Parent track direction
     * @param branchDirection Direction the branch should curve toward (left/right turn direction)
     * @param config          The configuration
     * @return BranchResult with the starting position and direction for the new branch head
     */
    public static BranchResult buildBranch(ServerLevel level, BlockPos parentPos, Direction parentDirection,
                                           Direction branchDirection,
                                           com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig config) {
        boolean isLeftTurn = DirectionUtil.getLeftDirection(parentDirection) == branchDirection;

        int branchRadius = RailwaysUntoldConfig.getMinCurveRadius();
        BlockPos curveEnd = CreateTrackUtil.calculateCurveEndpoint(
                parentPos, parentDirection, isLeftTurn, BRANCH_CURVE_ANGLE, branchRadius, 0);

        // Check if curve endpoint is over a ravine - defer if so, we don't want to branch out into a dropoff
        if (isEndpointOverRavine(level, curveEnd, parentDirection, isLeftTurn, config)) {
            return BranchResult.failed();
        }

        CurveParameterDecider.CurveParameters curveParams = new CurveParameterDecider.CurveParameters(
                BRANCH_CURVE_ANGLE,
                isLeftTurn,
                branchRadius,
                parentPos,
                curveEnd,
                parentDirection
        );

        Direction endDirection = DirectionUtil.getPerpendicularDirection(parentDirection, isLeftTurn);

        // Exclude the junction area from the branch curve's clearing and decoration
        // to prevent facade walls from overlapping with the parent tunnel
        int exclRadius = RailwaysUntoldConfig.getHorizontalExpansion() + 1;
        int exclHeight = RailwaysUntoldConfig.getVerticalExpansion() + 1;
        BlockPos exclMin = parentPos.offset(-exclRadius, -2, -exclRadius);
        BlockPos exclMax = parentPos.offset(exclRadius, exclHeight, exclRadius);

        BezierPlacementRequest curveRequest = BezierPlacementRequest.builder(
                        curveParams.start, curveParams.end, curveParams.trackDirection, endDirection)
                .config(config)
                .elevationChange(curveParams.elevationChange)
                .clearingExclusionBox(exclMin, exclMax)
                .build();
        BezierConnectionPlacer.BezierConnectionResult curveResult = BezierConnectionPlacer.place(level, curveRequest);

        if (!curveResult.success) {
            LOGGER.warn("[BRANCH-BUILD] Failed: curve placement failed from {} to {}", parentPos, curveEnd);
            return BranchResult.failed();
        }

        clearTransitionArea(level, curveParams.end, endDirection);
        decorateJunctionCorner(level, parentPos, parentDirection, endDirection);

        BlockPos branchStartPos = curveParams.end.relative(endDirection, 1);

        if (ChunkVerificationUtil.areGirderChunksLoaded(level, curveParams.end)) {
            CreateTrackUtil.placeGirdersUnderStraightTrack(level, curveParams.end, endDirection);
        }
        if (ChunkVerificationUtil.areGirderChunksLoaded(level, branchStartPos)) {
            CreateTrackUtil.placeGirdersUnderStraightTrack(level, branchStartPos, endDirection);
        }

        return BranchResult.succeeded(branchStartPos, endDirection, curveResult, curveParams.start);
    }

    private static boolean isEndpointOverRavine(ServerLevel level, BlockPos endPos,
                                                Direction parentDirection, boolean isLeftTurn,
                                                RailwaysUntoldConfig config) {
        Direction branchDirection = DirectionUtil.getPerpendicularDirection(parentDirection, isLeftTurn);

        TerrainScanner.TerrainScan scan = TerrainScanner.scanAhead(
                level, endPos, branchDirection, RAVINE_SCAN_DISTANCE, config);

        if (scan == null) {
            return false; // Can't determine, allow branch
        }

        TerrainAnalyzer.TerrainAnalysis analysis = TerrainAnalyzer.analyzeTerrain(scan, endPos);
        return analysis.isRavine();
    }

    /**
     * Clears blocks in the transition area between the curve endpoint and the branch start position.
     *
     * @param level           The server level
     * @param transitionPos   The position to clear (curve endpoint)
     * @param branchDirection The direction the branch is heading
     */
    private static void clearTransitionArea(ServerLevel level, BlockPos transitionPos, Direction branchDirection) {
        int horizontalRadius = RailwaysUntoldConfig.getHorizontalExpansion();
        int verticalHeight = RailwaysUntoldConfig.getVerticalExpansion();

        Direction perpDir = DirectionUtil.getLeftDirection(branchDirection);

        RemovalUtil.ClearingContext ctx = new RemovalUtil.ClearingContext(level);

        clearAreaSlice(ctx, transitionPos, perpDir, horizontalRadius, verticalHeight);
        clearAreaSlice(ctx, transitionPos.relative(branchDirection, 1), perpDir, horizontalRadius, verticalHeight);

        ctx.finish();
    }

    /**
     * Clears a vertical slice of blocks centered on a position.
     * At Y-1 (below track), only clears center + 1 left/right.
     * At track level and above, clears full horizontal radius.
     */
    private static void clearAreaSlice(RemovalUtil.ClearingContext ctx, BlockPos center,
                                       Direction perpDir, int horizontalRadius, int verticalHeight) {
        for (int y = -1; y <= verticalHeight; y++) {
            int effectiveRadius = (y == -1) ? 1 : horizontalRadius;
            for (int perpOffset = -effectiveRadius; perpOffset <= effectiveRadius; perpOffset++) {
                BlockPos clearPos = center.relative(perpDir, perpOffset).above(y);
                if (y == -1) {
                    ctx.clearBlockPreserveWater(clearPos);
                } else {
                    ctx.clearBlock(clearPos);
                }
            }
        }
    }

    /**
     * Fills gaps at branch junctions where the straight tunnel's facade
     * doesn't cover the corner area where the branch curves away.
     */
    private static void decorateJunctionCorner(ServerLevel level, BlockPos junctionPos,
                                               Direction parentDirection, Direction branchDirection) {
        if (!UndergroundDetector.isSegmentUnderground(level, junctionPos)) {
            return;
        }

        int horizontalRadius = RailwaysUntoldConfig.getHorizontalExpansion();
        int verticalHeight = RailwaysUntoldConfig.getVerticalExpansion();
        int facadeWallDist = horizontalRadius + 1;
        int facadeCeilingHeight = verticalHeight + 1;

        for (int forwardOffset = -1; forwardOffset <= 3; forwardOffset++) {
            BlockPos basePos = junctionPos.relative(parentDirection, forwardOffset);

            for (int y = -2; y <= verticalHeight; y++) {
                BlockPos wallBase = basePos.above(y).relative(branchDirection, facadeWallDist);
                placeFacadeIfSolid(level, wallBase);
                placeFacadeIfSolid(level, wallBase.relative(parentDirection));
                placeFacadeIfSolid(level, wallBase.relative(parentDirection.getOpposite()));
            }

            BlockPos ceilingBase = basePos.above(facadeCeilingHeight).relative(branchDirection, horizontalRadius);
            placeFacadeIfSolid(level, ceilingBase);
            placeFacadeIfSolid(level, ceilingBase.relative(branchDirection));

            BlockPos floorBase = basePos.below(2).relative(branchDirection, horizontalRadius);
            placeFacadeIfSolid(level, floorBase);
            placeFacadeIfSolid(level, floorBase.relative(branchDirection));
        }

        for (int diag = 1; diag <= horizontalRadius + 1; diag++) {
            for (int y = -2; y <= facadeCeilingHeight; y++) {
                BlockPos cornerPos = junctionPos
                        .relative(parentDirection, diag)
                        .relative(branchDirection, facadeWallDist)
                        .above(y);
                placeFacadeIfSolid(level, cornerPos);
            }
        }
    }

    private static void placeFacadeIfSolid(ServerLevel level, BlockPos pos) {
        BlockState existingState = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, pos);

        if (existingState == null) {
            return;
        }

        boolean isSolid = existingState.isSolid();
        boolean isFluid = BlockTypeUtil.isWaterOrLava(existingState);
        if (!isSolid && !isFluid) {
            return;
        }

        if (BlockTypeUtil.isChest(existingState)) {
            return;
        }

        if (SupportConstants.isAnyFacadeBlock(existingState.getBlock())) {
            return;
        }

        if (existingState.getBlock() == SupportConstants.getTunnelLightingBlock(level, pos).getBlock()) {
            return;
        }

        if (CreateTrackUtil.isTrackBlock(existingState) || CreateTrackUtil.isGirderBlock(existingState)) {
            return;
        }

        ChunkSafeBlockAccess.setBlockStateNonBlocking(level, pos, SupportConstants.getTunnelFacadeBlock(level, pos), true);
    }
}
