package com.vodmordia.railwaysuntold.util.chunk;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.HashSet;
import java.util.Set;

/**
 * Utility class for chunk coordinate calculations and iteration.
 */
public class ChunkCoordinateUtil {

    /**
     * Gets the dimension key string for a level.
     *
     * @param level The server level
     * @return The dimension key string (e.g., "minecraft:overworld")
     */
    public static String getDimensionKey(ServerLevel level) {
        return level.dimension().location().toString();
    }

    /**
     * Gets the chunk X coordinate from a block X coordinate.
     *
     * @param blockX The block X coordinate
     * @return The chunk X coordinate
     */
    public static int getChunkX(int blockX) {
        return blockX >> 4;
    }

    /**
     * Gets the chunk Z coordinate from a block Z coordinate.
     *
     * @param blockZ The block Z coordinate
     * @return The chunk Z coordinate
     */
    public static int getChunkZ(int blockZ) {
        return blockZ >> 4;
    }

    /**
     * Calculates a unique chunk key from chunk coordinates.
     *
     * @param chunkX The chunk X coordinate
     * @param chunkZ The chunk Z coordinate
     * @return A unique long key representing this chunk
     */
    public static long getChunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /**
     * Calculates a unique chunk key from a block position.
     *
     * @param pos The block position
     * @return A unique long key representing the chunk containing this block
     */
    public static long getChunkKey(BlockPos pos) {
        return getChunkKey(getChunkX(pos.getX()), getChunkZ(pos.getZ()));
    }

    /**
     * Extracts the chunk X coordinate from a chunk key.
     *
     * @param chunkKey The chunk key (created by getChunkKey)
     * @return The chunk X coordinate
     */
    public static int getChunkXFromKey(long chunkKey) {
        return (int) (chunkKey >> 32);
    }

    /**
     * Extracts the chunk Z coordinate from a chunk key.
     *
     * @param chunkKey The chunk key (created by getChunkKey)
     * @return The chunk Z coordinate
     */
    public static int getChunkZFromKey(long chunkKey) {
        return (int) (chunkKey & 0xFFFFFFFFL);
    }

    /**
     * Gets a set of all ChunkPos objects in the bounding box defined by two block positions.
     *
     * @param start The first corner of the bounding box
     * @param end The second corner of the bounding box
     * @return Set of chunk positions in the bounding box
     */
    public static Set<ChunkPos> getChunksInBoundingBox(BlockPos start, BlockPos end) {
        Set<ChunkPos> chunks = new HashSet<>();
        ChunkBounds bounds = ChunkBounds.from(start, end);

        for (int cx = bounds.minChunkX(); cx <= bounds.maxChunkX(); cx++) {
            for (int cz = bounds.minChunkZ(); cz <= bounds.maxChunkZ(); cz++) {
                chunks.add(new ChunkPos(cx, cz));
            }
        }
        return chunks;
    }

    /**
     * Gets a loaded chunk at the given position, or null if not loaded.
     *
     * @param level The level (ServerLevel or Level)
     * @param pos The block position
     * @return The chunk if loaded, null otherwise
     */
    public static LevelChunk getLoadedChunk(Level level, BlockPos pos) {
        int chunkX = getChunkX(pos.getX());
        int chunkZ = getChunkZ(pos.getZ());
        return level.getChunkSource().getChunkNow(chunkX, chunkZ);
    }

    /**
     * Gets a loaded chunk at the given chunk coordinates, or null if not loaded.
     *
     * @param level The level (ServerLevel or Level)
     * @param chunkX The chunk X coordinate
     * @param chunkZ The chunk Z coordinate
     * @return The chunk if loaded, null otherwise
     */
    public static LevelChunk getLoadedChunk(Level level, int chunkX, int chunkZ) {
        return level.getChunkSource().getChunkNow(chunkX, chunkZ);
    }

    /**
     * Gets a loaded chunk at the given ChunkPos, or null if not loaded.
     *
     * @param level The level (ServerLevel or Level)
     * @param chunkPos The chunk position
     * @return The chunk if loaded, null otherwise
     */
    public static LevelChunk getLoadedChunk(Level level, ChunkPos chunkPos) {
        return level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
    }

    /**
     * Checks if every chunk inside the given bounds (inclusive) is currently loaded.
     * The canonical primitive for "is this whole region available?" - callers with
     * block-pos endpoints construct a ChunkBounds first via ChunkBounds.from.
     *
     * @param level  The level
     * @param bounds Chunk coordinate bounds (inclusive on all sides)
     * @return true if every chunk in the bounds is loaded
     */
    public static boolean areChunksLoaded(Level level, ChunkBounds bounds) {
        for (int cx = bounds.minChunkX(); cx <= bounds.maxChunkX(); cx++) {
            for (int cz = bounds.minChunkZ(); cz <= bounds.maxChunkZ(); cz++) {
                if (getLoadedChunk(level, cx, cz) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Checks if the 3x3 chunk area around the center chunk is loaded.
     *
     * @param level       The server level
     * @param centerChunk The center chunk position
     * @return true if all 9 chunks in the 3x3 area are loaded
     */
    public static boolean areAdjacentChunksLoaded(ServerLevel level, ChunkPos centerChunk) {
        return areChunksLoaded(level, new ChunkBounds(
                centerChunk.x - 1, centerChunk.x + 1,
                centerChunk.z - 1, centerChunk.z + 1));
    }

}
