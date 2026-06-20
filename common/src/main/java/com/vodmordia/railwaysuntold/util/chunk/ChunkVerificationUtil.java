package com.vodmordia.railwaysuntold.util.chunk;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.Set;

/**
 * Utility class for chunk verification and collection operations.
 */
public class ChunkVerificationUtil {

    /** Horizontal offset for girder placement bounds (center +/- this value) */
    private static final int GIRDER_HORIZONTAL_OFFSET = 2;

    /**
     * Verifies that chunks at both endpoint positions are loaded.
     *
     * @param level The server level
     * @param start First position
     * @param end Second position
     * @return true if both chunks are loaded
     */
    public static boolean areEndpointChunksLoaded(ServerLevel level, BlockPos start, BlockPos end) {
        int startChunkX = ChunkCoordinateUtil.getChunkX(start.getX());
        int startChunkZ = ChunkCoordinateUtil.getChunkZ(start.getZ());
        int endChunkX = ChunkCoordinateUtil.getChunkX(end.getX());
        int endChunkZ = ChunkCoordinateUtil.getChunkZ(end.getZ());

        var startChunk = ChunkCoordinateUtil.getLoadedChunk(level, startChunkX, startChunkZ);
        if (startChunk == null) {
            return false;
        }

        var endChunk = ChunkCoordinateUtil.getLoadedChunk(level, endChunkX, endChunkZ);
        return endChunk != null;
    }

    /**
     * Verifies that all chunks in the bounding box between two positions are loaded.
     *
     * @param level The server level
     * @param start First position
     * @param end Second position
     * @return true if all chunks in bounding box are loaded
     */
    public static boolean areBoundingBoxChunksLoaded(ServerLevel level, BlockPos start, BlockPos end) {
        return ChunkCoordinateUtil.areChunksLoaded(level, ChunkBounds.from(start, end));
    }

    /**
     * Verifies that all chunks in a directional range are loaded.
     *
     * @param level The server level
     * @param start Starting position
     * @param direction Direction to scan
     * @param distance Distance to scan
     * @return true if all chunks in range are loaded
     */
    public static boolean areRangeChunksLoaded(ServerLevel level, BlockPos start, Direction direction, int distance) {
        BlockPos end = start.relative(direction, distance);
        return ChunkCoordinateUtil.areChunksLoaded(level, ChunkBounds.from(start, end));
    }

    /**
     * Verifies that all chunks needed for girder placement are loaded.
     *
     * @param level The server level
     * @param trackPos Track position where girders will be placed
     * @return true if all chunks needed for girder placement are loaded
     */
    public static boolean areGirderChunksLoaded(ServerLevel level, BlockPos trackPos) {
        // Girders are placed below the track and extend horizontally
        // (center + sides + diagonal fillers)
        BlockPos minPos = trackPos.offset(-GIRDER_HORIZONTAL_OFFSET, -1, -GIRDER_HORIZONTAL_OFFSET);
        BlockPos maxPos = trackPos.offset(GIRDER_HORIZONTAL_OFFSET, -1, GIRDER_HORIZONTAL_OFFSET);
        return ChunkCoordinateUtil.areChunksLoaded(level, ChunkBounds.from(minPos, maxPos));
    }

    /**
     * Checks if any of the given chunks are within render distance of any online player.
     *
     * @param level The server level
     * @param chunks The chunks to check
     * @param buffer Additional chunks beyond render distance to include (for safety margin)
     * @return true if at least one chunk is near a player
     */
    public static boolean areChunksWithinPlayerRange(ServerLevel level, Set<ChunkPos> chunks, int buffer) {
        int renderDistance = level.getServer().getPlayerList().getViewDistance();
        int searchRadius = renderDistance + buffer;

        var players = level.players();
        if (players.isEmpty()) {
            return false;
        }

        for (net.minecraft.server.level.ServerPlayer player : players) {
            int playerChunkX = ChunkCoordinateUtil.getChunkX(player.blockPosition().getX());
            int playerChunkZ = ChunkCoordinateUtil.getChunkZ(player.blockPosition().getZ());

            for (ChunkPos chunk : chunks) {
                int dx = Math.abs(chunk.x - playerChunkX);
                int dz = Math.abs(chunk.z - playerChunkZ);

                if (dx <= searchRadius && dz <= searchRadius) {
                    return true;
                }
            }
        }

        return false;
    }

}
