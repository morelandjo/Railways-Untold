package com.vodmordia.railwaysuntold.worldgen.village;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil;
import com.vodmordia.railwaysuntold.util.spatial.TrajectoryIntersection;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementConstants;
import com.vodmordia.railwaysuntold.worldgen.village.tracking.PersistentData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Tracks placed station bounding boxes so they can be excluded from village bounds scanning.
 */
public class PlacedStationTracker implements PersistentData {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Represents a placed station's bounding box.
     */
    public static class StationBounds {
        public final BlockPos minCorner;
        public final BlockPos maxCorner;
        public final int minChunkX;
        public final int maxChunkX;
        public final int minChunkZ;
        public final int maxChunkZ;

        public StationBounds(BlockPos minCorner, BlockPos maxCorner) {
            this.minCorner = minCorner;
            this.maxCorner = maxCorner;
            // Pre-calculate chunk bounds for faster lookup
            this.minChunkX = ChunkCoordinateUtil.getChunkX(minCorner.getX());
            this.maxChunkX = ChunkCoordinateUtil.getChunkX(maxCorner.getX());
            this.minChunkZ = ChunkCoordinateUtil.getChunkZ(minCorner.getZ());
            this.maxChunkZ = ChunkCoordinateUtil.getChunkZ(maxCorner.getZ());
        }

        @Override
        public String toString() {
            return String.format("StationBounds[%s -> %s, chunks X:%d-%d Z:%d-%d]",
                minCorner.toShortString(), maxCorner.toShortString(),
                minChunkX, maxChunkX, minChunkZ, maxChunkZ);
        }
    }

    private final List<StationBounds> placedStations = new ArrayList<>();

    /**
     * Number of stations successfully placed in this dimension.
     */
    public int getPlacedStationCount() {
        return placedStations.size();
    }

    /**
     * Register a placed station using min/max corners directly.
     */
    public void registerStation(BlockPos minCorner, BlockPos maxCorner) {
        // Validate corners are provided
        if (minCorner == null || maxCorner == null) {
            LOGGER.warn("[STATION-TRACKER] Invalid station corners: minCorner={}, maxCorner={}",
                minCorner, maxCorner);
            return;
        }

        // Ensure minCorner is actually the minimum in all dimensions
        // Cache coordinates to avoid redundant getter calls
        int corner1X = minCorner.getX(), corner2X = maxCorner.getX();
        int corner1Y = minCorner.getY(), corner2Y = maxCorner.getY();
        int corner1Z = minCorner.getZ(), corner2Z = maxCorner.getZ();
        int minX = Math.min(corner1X, corner2X);
        int minY = Math.min(corner1Y, corner2Y);
        int minZ = Math.min(corner1Z, corner2Z);
        int maxX = Math.max(corner1X, corner2X);
        int maxY = Math.max(corner1Y, corner2Y);
        int maxZ = Math.max(corner1Z, corner2Z);

        StationBounds bounds = new StationBounds(
            new BlockPos(minX, minY, minZ),
            new BlockPos(maxX, maxY, maxZ)
        );
        placedStations.add(bounds);
    }

    /**
     * Finds the first station whose buffered bounding box intersects a 2D trajectory line.
     *
     * @param x0 Start X of the trajectory
     * @param z0 Start Z of the trajectory
     * @param x1 End X of the trajectory
     * @param z1 End Z of the trajectory
     * @param y  Y level of the track (for vertical overlap check)
     * @return The first intersecting station, or null if none
     */
    @Nullable
    public StationBounds getStationIntersectingTrajectory(int x0, int z0, int x1, int z1, int y) {
        for (StationBounds station : placedStations) {
            BoundingBox box = new BoundingBox(
                    station.minCorner.getX() - TRAJECTORY_BUFFER,
                    station.minCorner.getY() - PlacementConstants.OBSTACLE_BOX_VERTICAL_BELOW,
                    station.minCorner.getZ() - TRAJECTORY_BUFFER,
                    station.maxCorner.getX() + TRAJECTORY_BUFFER,
                    station.maxCorner.getY() + PlacementConstants.OBSTACLE_BOX_VERTICAL_ABOVE,
                    station.maxCorner.getZ() + TRAJECTORY_BUFFER);
            if (y >= box.minY() && y <= box.maxY()
                    && TrajectoryIntersection.lineIntersectsBox(x0, z0, x1, z1, box)) {
                return station;
            }
        }
        return null;
    }

    private static final int TRAJECTORY_BUFFER = 5;

    public void clearAll() {
        placedStations.clear();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();

        ListTag stationsList = new ListTag();
        for (StationBounds station : placedStations) {
            CompoundTag stationTag = new CompoundTag();
            stationTag.putLong(VillageNbtKeys.MIN_CORNER, station.minCorner.asLong());
            stationTag.putLong(VillageNbtKeys.MAX_CORNER, station.maxCorner.asLong());
            stationsList.add(stationTag);
        }
        tag.put(VillageNbtKeys.STATIONS, stationsList);

        return tag;
    }

    public void load(CompoundTag tag) {
        clearAll();

        if (tag.contains(VillageNbtKeys.STATIONS, Tag.TAG_LIST)) {
            ListTag stationsList = tag.getList(VillageNbtKeys.STATIONS, Tag.TAG_COMPOUND);
            for (int i = 0; i < stationsList.size(); i++) {
                CompoundTag stationTag = stationsList.getCompound(i);
                BlockPos minCorner = BlockPos.of(stationTag.getLong(VillageNbtKeys.MIN_CORNER));
                BlockPos maxCorner = BlockPos.of(stationTag.getLong(VillageNbtKeys.MAX_CORNER));
                placedStations.add(new StationBounds(minCorner, maxCorner));
            }
        }
    }
}
