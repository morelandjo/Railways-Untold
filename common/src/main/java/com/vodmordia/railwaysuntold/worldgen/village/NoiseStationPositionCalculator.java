package com.vodmordia.railwaysuntold.worldgen.village;

import com.vodmordia.railwaysuntold.util.chunk.ChunkBounds;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Calculates station and runway positions using noise-based terrain height queries.
 */
public class NoiseStationPositionCalculator {

    /**
     * Calculates the station position using noise height at the runway center.
     *
     * @param sampler     Noise terrain sampler
     * @param approachDir The approach direction
     * @param bounds      Chunk bounds of the village
     * @return Station position with noise-sampled Y
     */
    public static BlockPos getStationPosition(NoiseTerrainSampler sampler, Direction approachDir, ChunkBounds bounds) {
        int runwayChunk = RunwayPositionCalculator.getRunwayChunk(approachDir, bounds);
        int centerChunkX = (bounds.minChunkX() + bounds.maxChunkX()) / 2;
        int centerChunkZ = (bounds.minChunkZ() + bounds.maxChunkZ()) / 2;
        Direction stationSide = approachDir;

        int x, z;
        if (stationSide == Direction.NORTH || stationSide == Direction.SOUTH) {
            x = (centerChunkX << 4) + 8;
            z = (runwayChunk << 4) + 8;
        } else {
            x = (runwayChunk << 4) + 8;
            z = (centerChunkZ << 4) + 8;
        }

        int y = sampler.getBaseHeight(x, z);
        return new BlockPos(x, y, z);
    }

    private static final int APPROACH_WAYPOINT_OFFSET = 120;

    /**
     * Distance to offset the approach waypoint perpendicular to the track axis.
     * This gives the head room to curve from its approach direction onto the runway.
     */
    private static final int APPROACH_PERPENDICULAR_OFFSET = 20;

    /**
     * Offsets a station position to create an approach waypoint. The waypoint is
     * offset along the runway axis (120 blocks toward the head) AND perpendicular
     * to the runway (20 blocks toward the approach side). The perpendicular offset
     * gives the head room to curve from its approach direction onto the runway
     * without overshooting.
     *
     * @param stationPos  The actual station position
     * @param approachDir The approach direction (which side the head approaches from)
     * @param headPos     Current head position
     * @return Approach waypoint with both track-axis and perpendicular offsets
     */
    public static BlockPos offsetToApproachWaypoint(BlockPos stationPos, Direction approachDir, BlockPos headPos) {
        Direction trackDir = RunwayPositionCalculator.getTrackDirection(approachDir);

        int trackOffsetX = 0, trackOffsetZ = 0;
        if (trackDir == Direction.EAST) {
            trackOffsetX = headPos.getX() < stationPos.getX() ? -APPROACH_WAYPOINT_OFFSET : APPROACH_WAYPOINT_OFFSET;
        } else {
            trackOffsetZ = headPos.getZ() < stationPos.getZ() ? -APPROACH_WAYPOINT_OFFSET : APPROACH_WAYPOINT_OFFSET;
        }

        // Offset perpendicular to the track (toward the approach side) so there's
        // room for a curve when the head arrives from a perpendicular direction.
        int perpOffsetX = approachDir.getStepX() * APPROACH_PERPENDICULAR_OFFSET;
        int perpOffsetZ = approachDir.getStepZ() * APPROACH_PERPENDICULAR_OFFSET;

        return new BlockPos(
                stationPos.getX() + trackOffsetX + perpOffsetX,
                stationPos.getY(),
                stationPos.getZ() + trackOffsetZ + perpOffsetZ);
    }

    /**
     * Calculates an approach waypoint for coarse routing.
     * Computes the station position then offsets it along the runway axis so the head
     * arrives roughly parallel to the runway instead of perpendicular.
     *
     * @param sampler     Noise terrain sampler
     * @param approachDir The approach direction
     * @param bounds      Chunk bounds of the village
     * @param headPos     Current head position
     * @return Approach waypoint with noise-sampled Y
     */
    public static BlockPos getApproachWaypoint(NoiseTerrainSampler sampler, Direction approachDir,
                                                ChunkBounds bounds, BlockPos headPos) {
        BlockPos stationPos = getStationPosition(sampler, approachDir, bounds);
        BlockPos waypoint = offsetToApproachWaypoint(stationPos, approachDir, headPos);
        // Re-sample Y at the waypoint location for accurate terrain height
        int y = sampler.getBaseHeight(waypoint.getX(), waypoint.getZ());
        return new BlockPos(waypoint.getX(), y, waypoint.getZ());
    }

    public static BlockPos getRunwayEndpoint(NoiseTerrainSampler sampler, Direction approachDir,
                                               ChunkBounds bounds, boolean isStart) {
        int runwayChunk = RunwayPositionCalculator.getRunwayChunk(approachDir, bounds);
        Direction stationSide = approachDir;

        int x, z;
        int villageRefX, villageRefZ;
        if (stationSide == Direction.NORTH || stationSide == Direction.SOUTH) {
            x = ((isStart ? bounds.minChunkX() : bounds.maxChunkX()) << 4) + 8;
            z = (runwayChunk << 4) + 8;
            villageRefX = x;
            villageRefZ = stationSide == Direction.NORTH ? (bounds.minChunkZ() << 4) + 8 : (bounds.maxChunkZ() << 4) + 8;
        } else {
            x = (runwayChunk << 4) + 8;
            z = ((isStart ? bounds.minChunkZ() : bounds.maxChunkZ()) << 4) + 8;
            villageRefX = stationSide == Direction.WEST ? (bounds.minChunkX() << 4) + 8 : (bounds.maxChunkX() << 4) + 8;
            villageRefZ = z;
        }

        // Use elevation from adjacent village chunk reference point, matching RunwayPositionCalculator behavior
        int y = sampler.getBaseHeight(villageRefX, villageRefZ);
        return new BlockPos(x, y, z);
    }
}
