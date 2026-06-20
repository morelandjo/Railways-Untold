package com.vodmordia.railwaysuntold.worldgen.placement.placer;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.util.chunk.ChunkVerificationUtil;
import com.vodmordia.railwaysuntold.util.spatial.DiagonalDirection;
import com.vodmordia.railwaysuntold.worldgen.integration.create.SCurve45TrackPlacer;
import com.vodmordia.railwaysuntold.worldgen.tracking.BoundarySegmentData.ConnectionType;
import com.vodmordia.railwaysuntold.worldgen.tracking.ConnectedBoundaryTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Places a diagonal straight track segment between two positions.
 * Reuses the diagonal straight placement logic from SCurve45TrackPlacer.
 */
public class DiagonalStraightTrackPlacer {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static class DiagonalPlacementResult {
        public final boolean success;
        public final boolean needsRetry;
        public final BlockPos endpoint;
        /** The bezier connection object, for terrain clearing. Null on failure. */
        public final Object connection;

        private DiagonalPlacementResult(boolean success, boolean needsRetry, BlockPos endpoint, Object connection) {
            this.success = success;
            this.needsRetry = needsRetry;
            this.endpoint = endpoint;
            this.connection = connection;
        }

        public static DiagonalPlacementResult failure() {
            return new DiagonalPlacementResult(false, false, null, null);
        }

        public static DiagonalPlacementResult needsRetry() {
            return new DiagonalPlacementResult(false, true, null, null);
        }

        public static DiagonalPlacementResult success(BlockPos endpoint, Object connection) {
            return new DiagonalPlacementResult(true, false, endpoint, connection);
        }
    }

    /**
     * Places a diagonal straight track segment with head ownership tracking.
     */
    public static DiagonalPlacementResult place(ServerLevel level, BlockPos start, BlockPos end,
                                                 DiagonalDirection diagonal, UUID headId) {
        if (!ChunkVerificationUtil.areBoundingBoxChunksLoaded(level, start, end)) {
            return DiagonalPlacementResult.needsRetry();
        }

        try {
            Object connection = SCurve45TrackPlacer.placeDiagonalStraightSegment(level, start, end, diagonal);
            if (connection == null) {
                LOGGER.debug("[DIAGONAL-STRAIGHT] Placement failed at {} -> {}", start, end);
                return DiagonalPlacementResult.failure();
            }

            ConnectedBoundaryTracker.confirmSegment(level, start, end, ConnectionType.STRAIGHT, null, headId);
            return DiagonalPlacementResult.success(end, connection);
        } catch (com.vodmordia.railwaysuntold.worldgen.integration.deferred.BlockEntitiesNotReadyException e) {
            // Transient: a setBlockStateNonBlocking write at a chunk boundary hasn't
            // taken effect yet (or pos2 is on a chunk that's loaded geometrically but
            // whose block-entity lookup isn't ready).
            LOGGER.debug("[DIAGONAL-STRAIGHT] Block entities not ready, will retry: {}", e.getMessage());
            return DiagonalPlacementResult.needsRetry();
        } catch (Exception e) {
            LOGGER.error("[DIAGONAL-STRAIGHT] Exception placing diagonal straight: {}", e.getMessage(), e);
            return DiagonalPlacementResult.failure();
        }
    }
}
