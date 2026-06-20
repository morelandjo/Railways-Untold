package com.vodmordia.railwaysuntold.worldgen.terrain;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.block.BlockTypeUtil;
import com.vodmordia.railwaysuntold.util.block.ChunkSafeBlockAccess;
import com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.CreateTrackUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Utility class for terrain height detection and clearance checking.
 */
public class TerrainHeightUtil {

    private TerrainHeightUtil() {
    }

    private record GroundScanResult(Integer solidGroundY, Integer waterSurfaceY) {
    }

    private static GroundScanResult scanForGround(
            net.minecraft.world.level.chunk.LevelChunk chunk,
            int x, int z, int startY, int minY,
            int waterSurfaceOffset, int solidOffset,
            boolean checkTrackBlocks, boolean stopOnSolidBelowWater) {

        Integer solidGroundY = null;
        Integer waterSurfaceY = null;

        BlockPos.MutableBlockPos checkPos = new BlockPos.MutableBlockPos(x, startY, z);
        for (int y = startY; y >= minY; y--) {
            checkPos.setY(y);
            var blockState = chunk.getBlockState(checkPos);

            if (BlockTypeUtil.isWater(blockState)) {
                if (waterSurfaceY == null) {
                    waterSurfaceY = y + waterSurfaceOffset;
                }
                continue;
            }

            if (BlockTypeUtil.isAnyFluid(blockState)) {
                continue;
            }

            if (stopOnSolidBelowWater && !blockState.isAir() && waterSurfaceY != null) {
                break;
            }

            boolean isIgnored = blockState.isAir()
                    || BlockTypeUtil.isTreeOrWood(blockState)
                    || BlockTypeUtil.isPlant(blockState)
                    || (checkTrackBlocks && CreateTrackUtil.isTrackBlock(blockState));

            if (!isIgnored) {
                solidGroundY = y + solidOffset;
                break;
            }
        }

        return new GroundScanResult(solidGroundY, waterSurfaceY);
    }

    /**
     * Gets the track placement height at a specific position using default config.
     *
     * @param level Server level
     * @param pos Position to check
     * @return Track placement height
     */
    public static int getGroundLevel(ServerLevel level, BlockPos pos) {
        return getGroundLevel(level, pos, RailwaysUntoldConfig.getDefault());
    }

    /**
     * Gets the track placement height at a specific position.
     *
     * @param level Server level
     * @param pos Position to check
     * @param config Configuration for height offset (null uses default)
     * @return Track placement height
     */
    public static int getGroundLevel(ServerLevel level, BlockPos pos, RailwaysUntoldConfig config) {
        if (config == null) {
            config = RailwaysUntoldConfig.getDefault();
        }

        var chunk = ChunkCoordinateUtil.getLoadedChunk(level, pos);
        if (chunk == null) {
            return TerrainConstants.DEFAULT_FALLBACK_HEIGHT;
        }

        int startY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX() & 15, pos.getZ() & 15);
        int minY = Math.max(level.getMinBuildHeight(), startY - TerrainConstants.GROUND_SCAN_RANGE);

        GroundScanResult scan = scanForGround(chunk, pos.getX(), pos.getZ(), startY, minY,
                0, 0, true, false);

        int baseHeight;
        boolean waterDominant = false;
        if (scan.solidGroundY != null && scan.waterSurfaceY != null) {
            baseHeight = Math.max(scan.solidGroundY, scan.waterSurfaceY);
            waterDominant = scan.waterSurfaceY >= scan.solidGroundY;
        } else if (scan.solidGroundY != null) {
            baseHeight = scan.solidGroundY;
        } else if (scan.waterSurfaceY != null) {
            baseHeight = scan.waterSurfaceY;
            waterDominant = true;
        } else {
            baseHeight = startY;
        }

        // Over water, use bridge water distance for proper elevation above water surface
        if (waterDominant && config.BRIDGE_WATER_DISTANCE > 0) {
            return baseHeight + config.BRIDGE_WATER_DISTANCE;
        }
        return baseHeight + config.TRACK_HEIGHT_OFFSET;
    }

    /**
     * Gets the raw ground level at specific coordinates, without any offset.
     * Scans down from heightmap, ignoring trees and leaves.
     * If the location is underwater, returns water surface level instead.
     *
     * @param level Server level
     * @param x X coordinate
     * @param z Z coordinate
     * @return Raw ground Y level (or water surface if underwater), or sea level if chunk not loaded
     */
    public static int getRawGroundLevel(ServerLevel level, int x, int z) {
        var chunk = ChunkCoordinateUtil.getLoadedChunk(level, x >> 4, z >> 4);
        if (chunk == null) {
            return level.getSeaLevel();
        }

        int surfaceY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
        int minY = level.getMinBuildHeight();

        GroundScanResult scan = scanForGround(chunk, x, z, surfaceY, minY,
                1, 1, false, false);

        if (scan.waterSurfaceY != null && scan.solidGroundY != null) {
            return Math.max(scan.waterSurfaceY, scan.solidGroundY);
        } else if (scan.waterSurfaceY != null) {
            return scan.waterSurfaceY;
        } else if (scan.solidGroundY != null) {
            return scan.solidGroundY;
        }

        return surfaceY;
    }

    /**
     * Simple ground level finder for branch terrain scoring.
     *
     * @param pos Position to check
     * @param level Server level
     * @return Y coordinate of ground, clamped to world minimum height
     */
    public static int findSimpleGroundLevel(BlockPos pos, ServerLevel level) {
        int minBuildHeight = level.getMinBuildHeight();
        for (int y = 0; y < 20; y++) {
            BlockPos checkPos = pos.below(y);
            BlockState state = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, checkPos);
            if (state == null) {
                return Math.max(pos.getY() - 20, minBuildHeight);
            }
            if (!state.isAir() && !state.canBeReplaced()) {
                return checkPos.getY();
            }
        }
        return Math.max(pos.getY() - 20, minBuildHeight);
    }

    /**
     * Measures vertical clearance (air blocks) above a position.
     *
     * @param pos Position to check
     * @param level Server level
     * @return Number of air/replaceable blocks above position (0-10)
     */
    public static int getClearanceHeight(BlockPos pos, ServerLevel level) {
        int height = 0;
        for (int y = 0; y < 10; y++) {
            BlockState state = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, pos.above(y));
            if (state == null) return height;
            if (state.isAir() || state.canBeReplaced()) {
                height++;
            } else {
                break;
            }
        }
        return height;
    }

    /**
     * Checks if position contains water or lava.
     *
     * @param pos Position to check
     * @param level Server level
     * @return true if water or lava is present
     */
    public static boolean hasLiquidObstruction(BlockPos pos, ServerLevel level) {
        BlockState state = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, pos);
        if (state == null) return false;
        return BlockTypeUtil.isWaterOrLava(state);
    }

    /**
     * Detects the water surface Y at a given X/Z position.
     * Returns -1 if no water is found or chunk is not loaded.
     *
     * @param level Server level
     * @param x     X coordinate
     * @param z     Z coordinate
     * @return Water surface Y, or -1 if no water found
     */
    public static int getWaterSurfaceY(ServerLevel level, int x, int z) {
        var chunk = ChunkCoordinateUtil.getLoadedChunk(level, x >> 4, z >> 4);
        if (chunk == null) {
            return -1;
        }

        int startY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
        int minY = Math.max(level.getMinBuildHeight(), startY - TerrainConstants.GROUND_SCAN_RANGE);

        GroundScanResult scan = scanForGround(chunk, x, z, startY, minY,
                0, 0, false, true);

        return scan.waterSurfaceY != null ? scan.waterSurfaceY : -1;
    }

}
