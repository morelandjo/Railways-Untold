package com.vodmordia.railwaysuntold.worldgen.placement.decider;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.track.SlopeValidator;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementConstants;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.BezierConnectionPlacer;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.BezierPlacementRequest;
import com.vodmordia.railwaysuntold.worldgen.terrain.TerrainAnalyzer;
import com.vodmordia.railwaysuntold.worldgen.terrain.TerrainScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import java.util.function.IntConsumer;

/**
 * Handles tunnel escape logic
 */
public final class TunnelEscapeSystem {

    private TunnelEscapeSystem() {
    }

    /**
     * Result of a tunnel escape attempt.
     */
    public static class EscapeResult {
        /** Whether the escape bezier was successfully placed */
        public final boolean success;
        /** The endpoint position if successful */
        public final BlockPos endpoint;
        /** The distance traveled if successful */
        public final int distance;

        private EscapeResult(boolean success, BlockPos endpoint, int distance) {
            this.success = success;
            this.endpoint = endpoint;
            this.distance = distance;
        }

        /** Escape conditions were not met - no attempt was made */
        public static EscapeResult notAttempted() {
            return new EscapeResult(false, null, 0);
        }

        /**
         * Escape was attempted but bezier placement failed.
         * Semantically distinct from notAttempted() but returns an identical result,
         * since no consumer currently distinguishes between them.
         */
        public static EscapeResult failed() {
            return notAttempted();
        }

        /** Escape was attempted and succeeded */
        public static EscapeResult success(BlockPos endpoint, int distance) {
            return new EscapeResult(true, endpoint, distance);
        }
    }

    /**
     * Attempts to escape from tunnel to surface when ground is close.
     * After placing a tunnel, checks if the next decision would also be a tunnel.
     * If ground is less than MAX_TUNNEL_ESCAPE_DISTANCE blocks above, creates an upward bezier to break out.
     *
     *
     * @param level Server level
     * @param tunnelEnd Position at end of tunnel (16 blocks from start)
     * @param direction Travel direction
     * @param config Train world configuration
     * @param torchOffset Current torch placement offset for continuous torch spacing
     * @return EscapeResult indicating whether escape was attempted and succeeded
     */
    public static EscapeResult tryTunnelEscape(ServerLevel level, BlockPos tunnelEnd, Direction direction,
                                               RailwaysUntoldConfig config, int torchOffset,
                                               IntConsumer onClearingComplete) {
        TerrainScanner.TerrainScan nextScan = TerrainScanner.scanAhead(level, tunnelEnd, direction, PlacementConstants.REQUIRED_LOOKAHEAD, config);
        if (nextScan == null) {
            return EscapeResult.notAttempted();
        }

        TerrainAnalyzer.TerrainAnalysis analysis = TerrainAnalyzer.analyzeTerrain(nextScan, tunnelEnd);
        if (!analysis.isMountain()) {
            return EscapeResult.notAttempted();
        }

        BlockPos checkPos = tunnelEnd.relative(direction, PlacementConstants.TUNNEL_ESCAPE_CHECK_DISTANCE);
        int groundLevel = TerrainScanner.getGroundLevelAt(level, checkPos);
        int tunnelTrackHeight = tunnelEnd.getY();

        int verticalClearTop = tunnelTrackHeight + RailwaysUntoldConfig.getVerticalExpansion();
        int distanceFromClearingToSurface = groundLevel - verticalClearTop;

        if (distanceFromClearingToSurface >= PlacementConstants.MAX_TUNNEL_ESCAPE_DISTANCE) {
            return EscapeResult.notAttempted();
        }

        int heightChange = groundLevel - tunnelTrackHeight;

        if (heightChange <= 0) {
            return EscapeResult.notAttempted();
        }

        int minRunDistance = SlopeValidator.getMinimumDistance(heightChange);

        if (minRunDistance > PlacementConstants.REQUIRED_LOOKAHEAD) {
            return EscapeResult.notAttempted();
        }

        BlockPos escapeEnd = tunnelEnd.relative(direction, minRunDistance);
        BlockPos surfaceEnd = new BlockPos(escapeEnd.getX(), groundLevel, escapeEnd.getZ());

        if (!SlopeValidator.isValidSlope(minRunDistance, heightChange)) {
            return EscapeResult.notAttempted();
        }

        BezierPlacementRequest escapeRequest = BezierPlacementRequest.builder(tunnelEnd, surfaceEnd, direction)
            .config(config)
            .elevationChange(heightChange)
            .torchOffset(torchOffset)
            .onClearingComplete(onClearingComplete)
            .build();
        BezierConnectionPlacer.BezierConnectionResult escapeResult = BezierConnectionPlacer.place(level, escapeRequest);

        if (escapeResult.success) {
            return EscapeResult.success(surfaceEnd, minRunDistance);
        } else {
            return EscapeResult.failed();
        }
    }
}
