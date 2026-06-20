package com.vodmordia.railwaysuntold.worldgen.terrain.clearing;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil;
import com.vodmordia.railwaysuntold.worldgen.integration.deferred.DeferredTunnelFinisher;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * Handles tunnel decoration including facade blocks and torch placement.
 */
public class TunnelFinisher {

    /**
     * Decorates a tunnel segment with facade blocks and torches.
     *
     * @param level The server level
     * @param centerPos Center position of the tunnel segment (track level)
     * @param normal Normal vector perpendicular to track direction
     * @param segmentIndex Index of the segment (used for torch interval)
     * @param config Configuration settings
     */
    public static void decorateSegment(
            ServerLevel level,
            BlockPos centerPos,
            Vec3 normal,
            int segmentIndex,
            RailwaysUntoldConfig config) {

        // Check if chunks are loaded for decoration; if not, queue for deferred processing
        ChunkPos centerChunk = new ChunkPos(centerPos);
        if (!ChunkCoordinateUtil.areAdjacentChunksLoaded(level, centerChunk)) {
            DeferredTunnelFinisher.queueDecoration(level, centerPos, normal, segmentIndex, config);
            return;
        }

        TunnelDecorationUtil.decorateSegmentDirect(level, centerPos, normal, segmentIndex, config);
    }
}
