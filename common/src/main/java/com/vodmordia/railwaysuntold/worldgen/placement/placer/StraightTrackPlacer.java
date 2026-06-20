package com.vodmordia.railwaysuntold.worldgen.placement.placer;

import com.mojang.logging.LogUtils;
import com.simibubi.create.Create;
import com.vodmordia.railwaysuntold.util.block.ChunkSafeBlockAccess;
import com.vodmordia.railwaysuntold.util.chunk.ChunkVerificationUtil;
import com.vodmordia.railwaysuntold.util.spatial.DirectionUtil;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.CreateTrackUtil;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.DirectGraphConnector;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.PropagationSuppressor;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.TrackMergeUtil;
import com.vodmordia.railwaysuntold.worldgen.terrain.clearing.DeferredTerrainClearer;
import com.vodmordia.railwaysuntold.worldgen.tracking.BoundarySegmentData.ConnectionType;
import com.vodmordia.railwaysuntold.worldgen.tracking.ConnectedBoundaryTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Places straight track segments without creating BezierConnections.
 */
public class StraightTrackPlacer {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Result of a straight track placement operation.
     */
    public static class StraightPlacementResult {
        public final boolean success;
        public final boolean needsRetry;
        public final BlockPos endpoint;
        public final int tracksPlaced;

        private StraightPlacementResult(boolean success, boolean needsRetry, BlockPos endpoint, int tracksPlaced) {
            this.success = success;
            this.needsRetry = needsRetry;
            this.endpoint = endpoint;
            this.tracksPlaced = tracksPlaced;
        }

        public static StraightPlacementResult failure() {
            return new StraightPlacementResult(false, false, null, 0);
        }

        public static StraightPlacementResult needsRetry() {
            return new StraightPlacementResult(false, true, null, 0);
        }

        public static StraightPlacementResult success(BlockPos endpoint, int tracksPlaced) {
            return new StraightPlacementResult(true, false, endpoint, tracksPlaced);
        }
    }

    /**
     * Places a straight track segment from start to end.
     *
     * @param level     The server level
     * @param start     Starting position
     * @param end       Ending position
     * @param direction The direction of the track (must be cardinal: N, S, E, or W)
     * @return StraightPlacementResult with success status
     */
    public static StraightPlacementResult place(ServerLevel level, BlockPos start, BlockPos end, Direction direction) {
        return place(level, start, end, direction, 0);
    }

    /**
     * Places a straight track segment with torch offset.
     *
     * @param torchOffset Starting offset for torch interval calculation in tunnel decoration
     */
    public static StraightPlacementResult place(ServerLevel level, BlockPos start, BlockPos end, Direction direction, int torchOffset) {
        com.vodmordia.railwaysuntold.worldgen.integration.railway.RailwayModCompat.applyMaterialForBiome(level, start);
        if (direction.getAxis() == Direction.Axis.Y) {
            return StraightPlacementResult.failure();
        }

        // Extend the check by 1 block on every side
        BlockPos checkStart = start.offset(-1, 0, -1);
        BlockPos checkEnd = end.offset(1, 0, 1);
        if (!ChunkVerificationUtil.areBoundingBoxChunksLoaded(level, checkStart, checkEnd)) {
            return StraightPlacementResult.needsRetry();
        }

        int distance = DirectionUtil.calculateDirectionalDistance(start, end, direction);
        if (distance < 1) {
            return StraightPlacementResult.failure();
        }

        BlockState trackState = CreateTrackUtil.getStraightTrack(direction);
        if (trackState == null) {
            return StraightPlacementResult.failure();
        }

        // Check start/end for fake_track and replace with real track
        TrackMergeUtil.EnsureResult startResult = TrackMergeUtil.ensureRealTrack(level, start, direction);
        TrackMergeUtil.EnsureResult endResult = TrackMergeUtil.ensureRealTrack(level, end, direction);
        boolean overlapExists = startResult.hadExistingTrack || endResult.hadExistingTrack;

        List<BlockPos> placedPositions = new ArrayList<>();
        BlockPos current = start;

        // Suppress Create's onRailAdded during batch placement to avoid O(n²) graph rebuilds.
        boolean batchSuppressed = !Create.RAILWAYS.trains.isEmpty();
        if (batchSuppressed) {
            PropagationSuppressor.beginBatch();
        }

        try {
            for (int i = 0; i <= distance; i++) {
                BlockPos pos = start.relative(direction, i);

                BlockState existingState = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, pos);
                if (existingState == null) {
                    // Chunk not loaded - stop here to avoid gaps
                    break;
                }
                if (CreateTrackUtil.isTrackBlock(existingState)) {
                    // Replace fake_track in the interior of the segment
                    if (CreateTrackUtil.isFakeTrack(existingState)) {
                        if (!ChunkSafeBlockAccess.setBlockStateNonBlocking(level, pos, trackState, true)) {
                            LOGGER.warn("[STRAIGHT] Failed to replace fake_track at {} (i={}/{})",
                                    pos, i, distance);
                            break;
                        }
                        placedPositions.add(pos);
                    }
                    current = pos;
                    continue;
                }

                if (!ChunkSafeBlockAccess.setBlockStateNonBlocking(level, pos, trackState, true)) {
                    // Chunk not yet ready for the write (autoload runs ahead of worldgen); the head defers
                    // and retries - routine, not surfaced.
                    break;
                }

                placedPositions.add(pos);
                current = pos;
            }
        } finally {
            if (batchSuppressed) {
                PropagationSuppressor.endBatch();
            }
        }

        // If we couldn't place any blocks, defer for retry
        if (placedPositions.isEmpty() && current.equals(start)) {
            return StraightPlacementResult.needsRetry();
        }

        int actualDistance = DirectionUtil.calculateDirectionalDistance(start, current, direction);

        // Add the full segment to the graph in one efficient operation
        if (batchSuppressed && actualDistance > 0) {
            if (!DirectGraphConnector.connectStraightSegmentDirect(level, start, current, direction, actualDistance)) {
                if (Create.RAILWAYS.trains.isEmpty()) {
                    // Safe to use propagation when no trains are running
                    LOGGER.warn("[STRAIGHT] Direct segment connect failed, falling back to propagation at {}", start);
                    CreateTrackUtil.triggerTrackPropagator(level, start, trackState);
                } else {
                    LOGGER.warn("[STRAIGHT] Direct segment connect failed with trains present at {}, graph may be incomplete", start);
                }
            }
        }

        ConnectedBoundaryTracker.confirmSegment(level, start, current,
                ConnectionType.STRAIGHT, placedPositions);

        DeferredTerrainClearer.queueStraightClearing(level, start, current, direction, torchOffset, null);

        // Place girders under ALL track positions (not just newly placed ones)
        for (int i = 0; i <= actualDistance; i++) {
            BlockPos pos = start.relative(direction, i);
            if (ChunkVerificationUtil.areGirderChunksLoaded(level, pos)) {
                CreateTrackUtil.placeGirdersUnderStraightTrack(level, pos, direction);
            }
        }

        // Bridge the track graph at overlap boundaries only.
        // Only bridge the first pair (existing->new) and last pair (new->existing)
        // to avoid creating long edges that bypass intermediate graph nodes.
        if (overlapExists && actualDistance > 0) {
            if (startResult.hadExistingTrack && actualDistance >= 1) {
                // Bridge start block (existing) to its immediate successor
                BlockPos firstNew = start.relative(direction, 1);
                TrackMergeUtil.bridgeTrackGraph(level, start, firstNew, direction);
            }
            if (endResult.hadExistingTrack && actualDistance >= 1) {
                // Bridge last new block to end block (existing)
                BlockPos lastNew = current.relative(direction.getOpposite(), 1);
                if (!lastNew.equals(start)) {
                    TrackMergeUtil.bridgeTrackGraph(level, lastNew, current, direction);
                }
            }
        }

        return StraightPlacementResult.success(current, placedPositions.size());
    }

}
