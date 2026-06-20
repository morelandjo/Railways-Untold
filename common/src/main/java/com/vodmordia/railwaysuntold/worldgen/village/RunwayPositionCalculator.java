package com.vodmordia.railwaysuntold.worldgen.village;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.chunk.ChunkBounds;
import com.vodmordia.railwaysuntold.worldgen.terrain.TerrainHeightUtil;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/**
 * Utility class for calculating runway and station positions based on village bounds.
 */
public class RunwayPositionCalculator {

    /**
     * Calculates the runway chunk coordinate based on approach direction.
     *
     * @param approachDir The approach direction (which side of the village the station is placed on)
     * @param bounds      Chunk bounds of the village
     * @return Chunk coordinate for the runway (1 chunk past village edge, on the approach side)
     */
    public static int getRunwayChunk(Direction approachDir, ChunkBounds bounds) {
        // approachDir is the side of the village where the station goes
        // (e.g., NORTH means station on north side of village)

        return switch (approachDir) {
            case NORTH -> bounds.minChunkZ() - 1;  // Runway on north side
            case SOUTH -> bounds.maxChunkZ() + 1;  // Runway on south side
            case EAST -> bounds.maxChunkX() + 1;   // Runway on east side
            case WEST -> bounds.minChunkX() - 1;   // Runway on west side
            default ->
                    throw new IllegalArgumentException("Invalid approach direction: " + approachDir);
        };
    }

    /**
     * Gets the track direction (perpendicular to the station side).
     *
     * @param approachDir The approach direction
     * @return Direction the track runs along the runway
     */
    public static Direction getTrackDirection(Direction approachDir) {
        Direction stationSide = approachDir;
        // Track runs perpendicular to station side
        if (stationSide == Direction.NORTH || stationSide == Direction.SOUTH) {
            return Direction.EAST;  // Station on N/S means track runs E-W
        } else {
            return Direction.NORTH; // Station on E/W means track runs N-S
        }
    }

    /**
     * Gets the true ground level at a position, scanning down past any tree parts (logs and leaves).
     * If the position is over water, raises the Y to match bridge height (BRIDGE_WATER_DISTANCE
     * above water surface) so stations align with approaching bridge tracks.
     *
     * @param level Server level
     * @param x     X coordinate
     * @param z     Z coordinate
     * @return Y level of actual ground, or bridge-adjusted Y if over water
     */
    public static int getGroundLevel(ServerLevel level, int x, int z) {
        int rawY = TerrainHeightUtil.getRawGroundLevel(level, x, z);
        int waterY = TerrainHeightUtil.getWaterSurfaceY(level, x, z);
        if (waterY >= 0 && rawY <= waterY + 1) {
            int bridgeDistance = RailwaysUntoldConfig.getDefault().BRIDGE_WATER_DISTANCE;
            if (bridgeDistance > 0) {
                return waterY + bridgeDistance;
            }
        }
        return rawY;
    }
}
