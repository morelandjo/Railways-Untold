package com.vodmordia.railwaysuntold.worldgen.village;

import com.vodmordia.railwaysuntold.datapack.SelectedStation;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.NbtSchematicLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Registers a placed station's protection bounding box (schematic footprint + track entry/exit,
 * padded - more on the perpendicular axis, scaled to the schematic) with the per-level station
 * tracker. Must run after track placement and before any
 * tunnel clearing that could touch the station edges.
 */
public final class StationBoundTracker {

    private StationBoundTracker() {
    }

    /**
     * Registers a placed station's bounding box with the tracker.
     *
     * @param level        The server level
     * @param placementPos The schematic placement position
     * @param trackEntry   The track entry position
     * @param trackExit    The track exit position
     */
    public static void registerStationBounds(ServerLevel level, BlockPos placementPos,
                                             BlockPos trackEntry, BlockPos trackExit,
                                             SelectedStation station) {
        NbtSchematicLoader.LoadedSchematic schematic = station.schematic();

        BlockPos schematicMin = placementPos;
        BlockPos schematicMax = placementPos.offset(
                schematic.getWidth() - 1,
                schematic.getHeight() - 1,
                schematic.getLength() - 1
        );

        boolean trackAlongX = Math.abs(trackExit.getX() - trackEntry.getX()) > Math.abs(trackExit.getZ() - trackEntry.getZ());

        // Scale padding with schematic size to ensure adequate clearance.
        // Perpendicular padding ensures the full schematic width + buffer is protected.
        int schematicPerpSize = trackAlongX ? schematic.getLength() : schematic.getWidth();
        int perpPadding = Math.max(8, schematicPerpSize / 2 + 3);
        int trackPadding = 2;

        int minX, maxX, minZ, maxZ;
        if (trackAlongX) {
            minX = Math.min(Math.min(schematicMin.getX(), trackEntry.getX()), trackExit.getX()) - trackPadding;
            maxX = Math.max(Math.max(schematicMax.getX(), trackEntry.getX()), trackExit.getX()) + trackPadding;
            minZ = Math.min(Math.min(schematicMin.getZ(), trackEntry.getZ()), trackExit.getZ()) - perpPadding;
            maxZ = Math.max(Math.max(schematicMax.getZ(), trackEntry.getZ()), trackExit.getZ()) + perpPadding;
        } else {
            minX = Math.min(Math.min(schematicMin.getX(), trackEntry.getX()), trackExit.getX()) - perpPadding;
            maxX = Math.max(Math.max(schematicMax.getX(), trackEntry.getX()), trackExit.getX()) + perpPadding;
            minZ = Math.min(Math.min(schematicMin.getZ(), trackEntry.getZ()), trackExit.getZ()) - trackPadding;
            maxZ = Math.max(Math.max(schematicMax.getZ(), trackEntry.getZ()), trackExit.getZ()) + trackPadding;
        }

        int minY = Math.min(Math.min(schematicMin.getY(), trackEntry.getY()), trackExit.getY()) - 1;
        int maxY = Math.max(Math.max(schematicMax.getY(), trackEntry.getY()), trackExit.getY()) + 1;

        VillageTargetingSavedData savedData = VillageTargetingSavedData.get(level);
        savedData.getStationTracker().registerStation(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
        savedData.setDirty();
    }
}
