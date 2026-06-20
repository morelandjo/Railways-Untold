package com.vodmordia.railwaysuntold.util.block;

import com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import javax.annotation.Nullable;

/**
 * Provides safe methods to get and set block states without triggering chunk loading.
 */
public class ChunkSafeBlockAccess {

    // Cached spawn chunk data to avoid repeated spawn pos lookups
    private static ServerLevel cachedLevel;
    private static int cachedSpawnChunkX;
    private static int cachedSpawnChunkZ;

    /** Returns null if the chunk is not loaded. */
    @Nullable
    public static BlockState getBlockStateNonBlocking(ServerLevel level, BlockPos pos) {
        int chunkX = ChunkCoordinateUtil.getChunkX(pos.getX());
        int chunkZ = ChunkCoordinateUtil.getChunkZ(pos.getZ());

        var chunk = ChunkCoordinateUtil.getLoadedChunk(level, chunkX, chunkZ);
        if (chunk == null) {
            return null;
        }

        return chunk.getBlockState(pos);
    }

    /** Returns false if the chunk (or adjacent chunks at boundaries) is not loaded. */
    public static boolean setBlockStateNonBlocking(ServerLevel level, BlockPos pos, BlockState state, boolean markUnsaved) {
        int chunkX = ChunkCoordinateUtil.getChunkX(pos.getX());
        int chunkZ = ChunkCoordinateUtil.getChunkZ(pos.getZ());

        var chunk = ChunkCoordinateUtil.getLoadedChunk(level, chunkX, chunkZ);
        if (chunk == null) {
            return false;
        }

        return setBlockWithBoundaryChecks(level, chunk, chunkX, chunkZ, pos, state, markUnsaved);
    }

    /**
     * Sets block state using an already-resolved chunk, avoiding redundant chunk lookup.
     * Use when the caller has already verified the chunk is loaded (e.g. in batch operations).
     */
    public static boolean setBlockStateWithChunk(ServerLevel level, LevelChunk chunk, BlockPos pos, BlockState state, boolean markUnsaved) {
        int chunkX = ChunkCoordinateUtil.getChunkX(pos.getX());
        int chunkZ = ChunkCoordinateUtil.getChunkZ(pos.getZ());

        return setBlockWithBoundaryChecks(level, chunk, chunkX, chunkZ, pos, state, markUnsaved);
    }

    private static boolean setBlockWithBoundaryChecks(ServerLevel level, LevelChunk chunk,
                                                       int chunkX, int chunkZ,
                                                       BlockPos pos, BlockState state, boolean markUnsaved) {
        // Near chunk boundaries, neighbor chunks must be loaded to avoid cascading chunk loads
        int localX = pos.getX() & 15;
        int localZ = pos.getZ() & 15;
        boolean onBoundaryX = (localX == 0) || (localX == 15);
        boolean onBoundaryZ = (localZ == 0) || (localZ == 15);

        // Fast path: interior blocks (the common case) need no neighbor checks
        if (onBoundaryX || onBoundaryZ) {
            if (localX == 0 && ChunkCoordinateUtil.getLoadedChunk(level, chunkX - 1, chunkZ) == null) {
                return false;
            }
            if (localX == 15 && ChunkCoordinateUtil.getLoadedChunk(level, chunkX + 1, chunkZ) == null) {
                return false;
            }
            if (localZ == 0 && ChunkCoordinateUtil.getLoadedChunk(level, chunkX, chunkZ - 1) == null) {
                return false;
            }
            if (localZ == 15 && ChunkCoordinateUtil.getLoadedChunk(level, chunkX, chunkZ + 1) == null) {
                return false;
            }
        }

        // Spawn chunks use UPDATE_NEIGHBORS (1) for proper updates; elsewhere skip it to avoid Create cascading updates
        // UPDATE_KNOWN_SHAPE (16) suppresses neighbor shape updates, preventing support-dependent blocks from popping off
        int flags = isInSpawnChunks(level, pos) ? (1 | 2 | 16) : (2 | 16);
        boolean success = level.setBlock(pos, state, flags);

        if (success && markUnsaved) {
            chunk.setUnsaved(true);
        }

        return success;
    }

    /**
     * Clears container contents before block removal.
     * UPDATE_KNOWN_SHAPE (16) only suppresses neighbor shape updates, not block entity inventories.
     */
    public static void clearContainerContents(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof Container container) {
            container.clearContent();
        }
    }

    /** Spawn chunks are always loaded and can safely use full block update flags. */
    private static boolean isInSpawnChunks(ServerLevel level, BlockPos pos) {
        if (cachedLevel != level) {
            BlockPos spawn = level.getSharedSpawnPos();
            cachedSpawnChunkX = spawn.getX() >> 4;
            cachedSpawnChunkZ = spawn.getZ() >> 4;
            cachedLevel = level;
        }

        // 1.20.1 uses hardcoded spawn chunk radius of 10 (roughly 19x19 chunks)
        int spawnChunkRadius = 10;
        int dx = Math.abs((pos.getX() >> 4) - cachedSpawnChunkX);
        int dz = Math.abs((pos.getZ() >> 4) - cachedSpawnChunkZ);
        return dx <= spawnChunkRadius && dz <= spawnChunkRadius;
    }

    /** Clears cached spawn chunk data. Call on world unload. */
    public static void clearCache() {
        cachedLevel = null;
    }
}
