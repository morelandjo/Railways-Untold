package com.vodmordia.railwaysuntold.worldgen.village;

import com.vodmordia.railwaysuntold.worldgen.village.network.StructureConnection;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.*;

/**
 * Persists village targeting data across world saves/loads.
 */
public class VillageTargetingSavedData extends SavedData {

    private static final String DATA_NAME = "railwaysuntold_village_targeting";

    // Village cache: VillageId -> VillageData
    private final Map<UUID, VillageData> discoveredVillages = new HashMap<>();

    // Village assignment tracker (current assignments)
    private final VillageAssignmentTracker assignmentTracker = new VillageAssignmentTracker();

    // Attempted village tracker (permanent record of villages that have been attempted)
    private final AttemptedVillageTracker attemptedTracker = new AttemptedVillageTracker();

    // Placed station tracker (to exclude stations from village bounds scanning)
    private final PlacedStationTracker stationTracker = new PlacedStationTracker();

    // Per-block protection for station schematic blocks
    private final StationBlockProtection stationBlockProtection = new StationBlockProtection();

    // Planner-computed candidate rail edges between village centers (advisory)
    private final List<StructureConnection> networkEdges = new ArrayList<>();

    public VillageTargetingSavedData() {
    }

    /**
     * Gets the VillageTargetingSavedData instance for a level
     */
    public static VillageTargetingSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
            VillageTargetingSavedData::load,
            VillageTargetingSavedData::new,
            DATA_NAME
        );
    }

    /** Convenience: check whether a position is protected by any station on this level. */
    public static boolean isBlockProtectedByStation(ServerLevel level, BlockPos pos) {
        return get(level).getStationBlockProtection().isProtected(pos);
    }

    /**
     * Gets the assignment tracker
     */
    public VillageAssignmentTracker getAssignmentTracker() {
        return assignmentTracker;
    }

    /**
     * Gets the attempted village tracker
     */
    public AttemptedVillageTracker getAttemptedTracker() {
        return attemptedTracker;
    }

    /**
     * Gets the placed station tracker
     */
    public PlacedStationTracker getStationTracker() {
        return stationTracker;
    }

    /**
     * Gets the station block protection tracker
     */
    public StationBlockProtection getStationBlockProtection() {
        return stationBlockProtection;
    }

    /**
     * Returns an unmodifiable view of the planner-computed edge list.
     */
    public List<StructureConnection> getNetworkEdges() {
        return Collections.unmodifiableList(networkEdges);
    }

    /**
     * Replaces the planner-computed edge list and marks the saved-data dirty.
     * Pass an empty list to clear.
     */
    public void setNetworkEdges(List<StructureConnection> edges) {
        networkEdges.clear();
        if (edges != null) networkEdges.addAll(edges);
        setDirty();
    }

    /**
     * Adds a discovered village to the cache
     */
    public void addDiscoveredVillage(VillageInfo village) {
        VillageData villageData = new VillageData(
            village.villageId,
            village.center,
            village.villageType
        );
        discoveredVillages.put(village.villageId, villageData);
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        // Save discovered villages
        ListTag villagesList = new ListTag();
        for (VillageData village : discoveredVillages.values()) {
            CompoundTag villageTag = new CompoundTag();
            villageTag.putUUID(VillageNbtKeys.ID, village.villageId);
            villageTag.putLong(VillageNbtKeys.CENTER, village.center.asLong());
            villageTag.putString(VillageNbtKeys.TYPE, village.villageType);
            villagesList.add(villageTag);
        }
        tag.put(VillageNbtKeys.VILLAGES, villagesList);

        // Save assignments
        tag.put(VillageNbtKeys.ASSIGNMENTS, assignmentTracker.save());

        // Save attempted villages
        tag.put(VillageNbtKeys.ATTEMPTED_VILLAGES, attemptedTracker.save());

        // Save placed stations
        tag.put(VillageNbtKeys.PLACED_STATIONS, stationTracker.save());

        // Save station block protection
        tag.put(VillageNbtKeys.STATION_BLOCK_PROTECTION, stationBlockProtection.save());

        // Save planner-computed network edges
        ListTag edgesList = new ListTag();
        for (StructureConnection edge : networkEdges) {
            CompoundTag edgeTag = new CompoundTag();
            edgeTag.putLong(VillageNbtKeys.EDGE_FROM, edge.from().asLong());
            edgeTag.putLong(VillageNbtKeys.EDGE_TO, edge.to().asLong());
            edgesList.add(edgeTag);
        }
        tag.put(VillageNbtKeys.NETWORK_EDGES, edgesList);

        return tag;
    }

    /**
     * Loads data from NBT
     */
    public static VillageTargetingSavedData load(CompoundTag tag) {
        VillageTargetingSavedData savedData = new VillageTargetingSavedData();

        // Load villages
        if (tag.contains(VillageNbtKeys.VILLAGES, Tag.TAG_LIST)) {
            ListTag villagesList = tag.getList(VillageNbtKeys.VILLAGES, Tag.TAG_COMPOUND);
            for (int i = 0; i < villagesList.size(); i++) {
                CompoundTag villageTag = villagesList.getCompound(i);
                if (villageTag.hasUUID(VillageNbtKeys.ID)) {
                    UUID id = villageTag.getUUID(VillageNbtKeys.ID);
                    BlockPos center = BlockPos.of(villageTag.getLong(VillageNbtKeys.CENTER));
                    String type = villageTag.getString(VillageNbtKeys.TYPE);
                    savedData.discoveredVillages.put(id, new VillageData(id, center, type));
                }
            }
        }

        // Load assignments
        if (tag.contains(VillageNbtKeys.ASSIGNMENTS)) {
            savedData.assignmentTracker.load(tag.getCompound(VillageNbtKeys.ASSIGNMENTS));
        }

        // Load attempted villages
        if (tag.contains(VillageNbtKeys.ATTEMPTED_VILLAGES)) {
            savedData.attemptedTracker.load(tag.getCompound(VillageNbtKeys.ATTEMPTED_VILLAGES));
        }

        // Load placed stations
        if (tag.contains(VillageNbtKeys.PLACED_STATIONS)) {
            savedData.stationTracker.load(tag.getCompound(VillageNbtKeys.PLACED_STATIONS));
        }

        // Load station block protection
        if (tag.contains(VillageNbtKeys.STATION_BLOCK_PROTECTION)) {
            savedData.stationBlockProtection.load(tag.getCompound(VillageNbtKeys.STATION_BLOCK_PROTECTION));
        }

        // Load planner-computed network edges
        if (tag.contains(VillageNbtKeys.NETWORK_EDGES, Tag.TAG_LIST)) {
            ListTag edgesList = tag.getList(VillageNbtKeys.NETWORK_EDGES, Tag.TAG_COMPOUND);
            for (int i = 0; i < edgesList.size(); i++) {
                CompoundTag edgeTag = edgesList.getCompound(i);
                BlockPos from = BlockPos.of(edgeTag.getLong(VillageNbtKeys.EDGE_FROM));
                BlockPos to = BlockPos.of(edgeTag.getLong(VillageNbtKeys.EDGE_TO));
                savedData.networkEdges.add(new StructureConnection(from, to));
            }
        }

        return savedData;
    }

    /**
     * Cached village data for persistence
     */
    public static class VillageData {
        public final UUID villageId;
        public final BlockPos center;
        public final String villageType;

        public VillageData(UUID villageId, BlockPos center, String villageType) {
            this.villageId = villageId;
            this.center = center;
            this.villageType = villageType;
        }
    }
}
