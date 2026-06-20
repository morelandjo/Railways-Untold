package com.vodmordia.railwaysuntold.worldgen.placement.placer;

import com.mojang.logging.LogUtils;
import com.simibubi.create.Create;
import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.block.ChunkSafeBlockAccess;
import com.vodmordia.railwaysuntold.util.chunk.ChunkVerificationUtil;
import com.vodmordia.railwaysuntold.worldgen.integration.create.CreateTrackConnector;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.BezierSegmentExtractor;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.BezierSegmentExtractor.BezierSegmentData;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.CreateTrackUtil;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.PropagationSuppressor;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.TrackMergeUtil;
import com.vodmordia.railwaysuntold.worldgen.integration.deferred.BlockEntitiesNotReadyException;
import com.vodmordia.railwaysuntold.worldgen.terrain.clearing.ClearingTypes;
import com.vodmordia.railwaysuntold.worldgen.terrain.clearing.DeferredTerrainClearer;
import com.vodmordia.railwaysuntold.worldgen.tracking.BoundarySegmentData.ConnectionType;
import com.vodmordia.railwaysuntold.worldgen.tracking.ConnectedBoundaryTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.List;

/**
 * Bezier connection placer.
 * Use {@link #place(ServerLevel, BezierPlacementRequest)} for all bezier placement.
 */
public class BezierConnectionPlacer {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Result object containing both success status and pre-extracted bezier segment data.
     */
    public static class BezierConnectionResult {
        public final boolean success;
        public final boolean needsRetry;
        public final List<BezierSegmentData> segments;
        /** When non-null, the actual endpoint reached (may differ from requested end for straight fallbacks). */
        public final BlockPos actualEndpoint;

        private BezierConnectionResult(boolean success, boolean needsRetry, List<BezierSegmentData> segments,
                                       BlockPos actualEndpoint) {
            this.success = success;
            this.needsRetry = needsRetry;
            this.segments = segments;
            this.actualEndpoint = actualEndpoint;
        }

        public static BezierConnectionResult failure() {
            return new BezierConnectionResult(false, false, Collections.emptyList(), null);
        }

        public static BezierConnectionResult needsRetry() {
            return new BezierConnectionResult(false, true, Collections.emptyList(), null);
        }

        public static BezierConnectionResult success(Object bezierConnection) {
            List<BezierSegmentData> segments = BezierSegmentExtractor.extractSegments(bezierConnection);
            return new BezierConnectionResult(true, false, segments, null);
        }

        public static BezierConnectionResult successWithEndpoint(BlockPos actualEndpoint) {
            return new BezierConnectionResult(true, false, Collections.emptyList(), actualEndpoint);
        }
    }

    /**
     * Tracks which endpoint tracks were newly placed vs already existing.
     */
    private record EndpointPlacementResult(boolean success, boolean chunksNotLoaded, boolean startWasNew, boolean endWasNew) {
        static EndpointPlacementResult deferForChunks() {
            return new EndpointPlacementResult(false, true, false, false);
        }
    }

    /**
     * Primary entry point for bezier placement.
     *
     * @param level   The server level
     * @param request The placement request containing all parameters
     * @return BezierConnectionResult with success status and segment data
     */
    public static BezierConnectionResult place(ServerLevel level, BezierPlacementRequest request) {
        if (isStraightLine(request)) {
            return placeStraightFallback(level, request);
        }

        if (!ChunkVerificationUtil.areBoundingBoxChunksLoaded(level,
                request.start.offset(-1, 0, -1), request.end.offset(1, 0, 1))) {
            return BezierConnectionResult.needsRetry();
        }

        EndpointPlacementResult endpoints = placeEndpointTracks(level, request.start, request.end, request.startDirection, request.endDirection);
        if (!endpoints.success) {
            if (endpoints.chunksNotLoaded) {
                return BezierConnectionResult.needsRetry();
            }
            LOGGER.warn("[BEZIER] Endpoint placement failed: {} -> {} (startDir={}, endDir={})",
                    request.start, request.end, request.startDirection, request.endDirection);
            logPlacement(request, false, 0);
            return BezierConnectionResult.failure();
        }

        Object bezierConnection;
        try {
            if (request.incomingSlope != 0.0 || request.outgoingSlope != 0.0) {
                bezierConnection = CreateTrackConnector.connectTracksWithSlopes(
                        level, request.start, request.end, request.elevationChange,
                        request.incomingSlope, request.outgoingSlope);
            } else if (request.elevationChange != 0) {
                bezierConnection = CreateTrackConnector.connectTracksWithElevation(
                        level, request.start, request.end, request.elevationChange);
            } else {
                bezierConnection = CreateTrackConnector.connectTracks(
                        level, request.start, request.end);
            }
        } catch (BlockEntitiesNotReadyException e) {
            LOGGER.debug("[BEZIER] Block entities not ready, will retry: {}", e.getMessage());
            return BezierConnectionResult.needsRetry();
        } catch (IllegalStateException e) {
            LOGGER.error("[BEZIER] IllegalStateException: {}", e.getMessage());
            throw e;
        }

        boolean success = bezierConnection != null;

        if (success) {
            List<BlockPos> curvePositions = extractCurvePositions(bezierConnection, request.start);
            ConnectedBoundaryTracker.confirmSegment(level, request.start, request.end,
                    ConnectionType.BEZIER, curvePositions, request.headId);
        } else {
            cleanupEndpointTracks(level, request.start, request.end, endpoints);
        }

        if (success && request.performClearing) {
            performTerrainClearing(level, bezierConnection, request);
        }

        if (success) {
            placeGirdersAtEndpoints(level, request);
        }

        logPlacement(request, success, 0);

        return success ? BezierConnectionResult.success(bezierConnection) : BezierConnectionResult.failure();
    }

    private static void performTerrainClearing(ServerLevel level, Object bezierConnection, BezierPlacementRequest request) {
        RailwaysUntoldConfig clearingConfig = request.config != null ? request.config : new RailwaysUntoldConfig();

        var clearingRequestBuilder = ClearingTypes.ClearingRequest.builder(request.start, clearingConfig)
                .torchOffset(request.torchOffset)
                .villageBounds(ClearingTypes.findNearbyVillageBounds(level, request.start, request.end));
        if (request.clearingExclusionMin != null && request.clearingExclusionMax != null) {
            clearingRequestBuilder.exclusionBox(request.clearingExclusionMin, request.clearingExclusionMax);
        }

        DeferredTerrainClearer.queueBezierClearing(
                level, bezierConnection, clearingRequestBuilder.build(), request.onClearingComplete);
    }

    private static void placeGirdersAtEndpoints(ServerLevel level, BezierPlacementRequest request) {
        if (ChunkVerificationUtil.areGirderChunksLoaded(level, request.start)) {
            CreateTrackUtil.placeGirdersUnderStraightTrack(level, request.start, request.startDirection);
        }
        if (ChunkVerificationUtil.areGirderChunksLoaded(level, request.end)) {
            CreateTrackUtil.placeGirdersUnderStraightTrack(level, request.end, request.endDirection);
        }
    }

    /**
     * Outputs the consolidated placement log line.
     */
    private static void logPlacement(BezierPlacementRequest request, boolean success, int undergroundSegments) {
        if (request.logContext != null) {
            request.logContext.build(
                    request.start, request.end,
                    request.startDirection, request.endDirection,
                    request.elevationChange, success, undergroundSegments);
        }
    }

    /**
     * Extracts curve positions from a bezier connection for accurate intersection detection.
     *
     * @param bezierConnection Create's BezierConnection object
     * @param origin           The origin block position (bezier uses relative coordinates)
     * @return List of BlockPos along the curve, or null if extraction fails
     */
    private static List<BlockPos> extractCurvePositions(Object bezierConnection, BlockPos origin) {
        if (bezierConnection == null) {
            return null;
        }

        List<BezierSegmentData> segments = BezierSegmentExtractor.extractSegments(bezierConnection);
        if (segments.isEmpty()) {
            return null;
        }

        // Convert segment positions to world BlockPos
        net.minecraft.world.phys.Vec3 originOffset = net.minecraft.world.phys.Vec3.atLowerCornerOf(origin).add(0, 3 / 16.0, 0);

        List<BlockPos> curvePositions = new java.util.ArrayList<>();
        for (BezierSegmentData segment : segments) {
            net.minecraft.world.phys.Vec3 worldPos = segment.position().add(originOffset);
            curvePositions.add(BlockPos.containing(worldPos));
        }

        return curvePositions;
    }

    /**
     * Removes newly-placed endpoint tracks that have no bezier connection.
     * Only removes tracks that were placed by this bezier attempt (not pre-existing ones).
     */
    private static void cleanupEndpointTracks(ServerLevel level, BlockPos start, BlockPos end,
                                               EndpointPlacementResult endpoints) {
        if (endpoints.startWasNew) {
            ChunkSafeBlockAccess.setBlockStateNonBlocking(level, start, Blocks.AIR.defaultBlockState(), true);
            LOGGER.debug("[BEZIER] Cleaned up orphaned start endpoint track at {}", start);
        }
        if (endpoints.endWasNew) {
            ChunkSafeBlockAccess.setBlockStateNonBlocking(level, end, Blocks.AIR.defaultBlockState(), true);
            LOGGER.debug("[BEZIER] Cleaned up orphaned end endpoint track at {}", end);
        }
    }

    /**
     * Checks whether a bezier request describes a perfectly straight, flat line that
     * should use individual track blocks instead of a Create BezierConnection.
     *
     */
    private static boolean isStraightLine(BezierPlacementRequest request) {
        if (request.startDirection != request.endDirection) {
            return false;
        }
        if (request.elevationChange != 0) {
            return false;
        }
        if (request.start.getY() != request.end.getY()) {
            return false;
        }
        Direction dir = request.startDirection;
        if (dir.getAxis() == Direction.Axis.X) {
            // Traveling east/west: Z must match
            if (request.start.getZ() != request.end.getZ()) {
                return false;
            }
            int delta = request.end.getX() - request.start.getX();
            return (dir == Direction.EAST) ? delta > 0 : delta < 0;
        } else if (dir.getAxis() == Direction.Axis.Z) {
            // Traveling north/south: X must match
            if (request.start.getX() != request.end.getX()) {
                return false;
            }
            int delta = request.end.getZ() - request.start.getZ();
            return (dir == Direction.SOUTH) ? delta > 0 : delta < 0;
        }
        return false;
    }

    /**
     * Delegates a straight-line bezier request to {@link StraightTrackPlacer} and wraps
     * the result in a {@link BezierConnectionResult} for transparent caller compatibility.
     */
    private static BezierConnectionResult placeStraightFallback(ServerLevel level, BezierPlacementRequest request) {

        StraightTrackPlacer.StraightPlacementResult straightResult =
                StraightTrackPlacer.place(level, request.start, request.end, request.startDirection, request.torchOffset);

        if (straightResult.needsRetry) {
            return BezierConnectionResult.needsRetry();
        }
        if (!straightResult.success) {
            logPlacement(request, false, 0);
            return BezierConnectionResult.failure();
        }

        logPlacement(request, true, 0);
        // Fail-fast on truncation
        if (!request.end.equals(straightResult.endpoint)) {
            // Endpoint not yet placeable (chunk readiness) - defer and retry; routine, not surfaced.
            return BezierConnectionResult.needsRetry();
        }
        return BezierConnectionResult.successWithEndpoint(straightResult.endpoint);
    }

    /**
     * Places endpoint tracks for bezier connections.
     * Only places tracks if they don't already exist (to avoid overwriting existing bezier connections).
     * Returns which endpoints were newly placed so they can be cleaned up on failure.
     */
    private static EndpointPlacementResult placeEndpointTracks(ServerLevel level, BlockPos start, BlockPos end,
                                                                Direction startDir, Direction endDir) {
        com.vodmordia.railwaysuntold.worldgen.integration.railway.RailwayModCompat.applyMaterialForBiome(level, start);
        if (!ChunkVerificationUtil.areBoundingBoxChunksLoaded(level, start.offset(-1, 0, -1), end.offset(1, 0, 1))) {
            LOGGER.debug("[BEZIER] Endpoint chunks not loaded: {} -> {} - deferring", start, end);
            return EndpointPlacementResult.deferForChunks();
        }

        // Use TrackMergeUtil to handle fake_track replacement at intersection points
        TrackMergeUtil.EnsureResult startResult = TrackMergeUtil.ensureRealTrack(level, start, startDir);
        boolean startWasNew = false;
        boolean endWasNew = false;

        // Suppress onRailAdded during endpoint placement
        boolean batchSuppressed = !Create.RAILWAYS.trains.isEmpty();
        if (batchSuppressed) {
            PropagationSuppressor.beginBatch();
        }

        try {
            if (!startResult.hadExistingTrack) {
                BlockState startTrack = CreateTrackUtil.getStraightTrack(startDir);
                ChunkSafeBlockAccess.setBlockStateNonBlocking(level, start, startTrack, true);
                startWasNew = true;
            }

            TrackMergeUtil.EnsureResult endResult = TrackMergeUtil.ensureRealTrack(level, end, endDir);

            if (!endResult.hadExistingTrack) {
                BlockState endTrack = CreateTrackUtil.getStraightTrack(endDir);
                ChunkSafeBlockAccess.setBlockStateNonBlocking(level, end, endTrack, true);
                endWasNew = true;
            }
        } finally {
            if (batchSuppressed) {
                PropagationSuppressor.endBatch();
            }
        }

        return new EndpointPlacementResult(true, false, startWasNew, endWasNew);
    }
}
