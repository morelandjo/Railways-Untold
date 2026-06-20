package com.vodmordia.railwaysuntold.worldgen.placement;

import com.vodmordia.railwaysuntold.worldgen.head.TrackExpansionHead;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;


import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persistent storage for track placement state.
 */
public class TrackPlacementSavedData extends SavedData {

    private static final String DATA_NAME = "railwaysuntold_track_placement";

    // State fields
    private boolean initiated;
    @Nullable private Direction initialTrackDirection;
    private final List<TrackHeadPersistenceData> heads;
    private int nextHeadNumber = 1;

    /**
     * Constructor for new data (not yet initiated).
     */
    public TrackPlacementSavedData() {
        this.initiated = false;
        this.heads = new ArrayList<>();
    }

    /**
     * Constructor for loading from NBT.
     */
    private TrackPlacementSavedData(CompoundTag tag) {
        this.initiated = tag.getBoolean(TrackPlacementNbtKeys.INITIATED);
        this.heads = new ArrayList<>();

        if (tag.contains(TrackPlacementNbtKeys.INITIAL_TRACK_DIRECTION)) {
            this.initialTrackDirection = Direction.from3DDataValue(tag.getInt(TrackPlacementNbtKeys.INITIAL_TRACK_DIRECTION));
        }

        // Load nextHeadNumber with fallback
        this.nextHeadNumber = tag.contains(TrackPlacementNbtKeys.NEXT_HEAD_NUMBER) ? tag.getInt(TrackPlacementNbtKeys.NEXT_HEAD_NUMBER) : 1;

        if (tag.contains(TrackPlacementNbtKeys.HEADS, Tag.TAG_LIST)) {
            ListTag headsListTag = tag.getList(TrackPlacementNbtKeys.HEADS, Tag.TAG_COMPOUND);
            for (int i = 0; i < headsListTag.size(); i++) {
                CompoundTag headTag = headsListTag.getCompound(i);
                TrackHeadPersistenceData headData = loadHeadData(headTag);
                this.heads.add(headData);
            }
        }
    }

    /**
     * Loads a single head from NBT.
     */
    private TrackHeadPersistenceData loadHeadData(CompoundTag headTag) {
        UUID headId = headTag.getUUID(TrackPlacementNbtKeys.HEAD_ID);
        UUID parentHeadId = headTag.contains(TrackPlacementNbtKeys.PARENT_HEAD_ID) ? headTag.getUUID(TrackPlacementNbtKeys.PARENT_HEAD_ID) : null;
        int headNumber = headTag.getInt(TrackPlacementNbtKeys.HEAD_NUMBER);
        int branchDepth = headTag.getInt(TrackPlacementNbtKeys.BRANCH_DEPTH);
        BlockPos pos = new BlockPos(headTag.getInt(TrackPlacementNbtKeys.POS_X), headTag.getInt(TrackPlacementNbtKeys.POS_Y), headTag.getInt(TrackPlacementNbtKeys.POS_Z));
        Direction dir = Direction.from3DDataValue(headTag.getInt(TrackPlacementNbtKeys.DIRECTION));
        Direction initialDir = headTag.contains(TrackPlacementNbtKeys.INITIAL_DIRECTION)
            ? Direction.from3DDataValue(headTag.getInt(TrackPlacementNbtKeys.INITIAL_DIRECTION))
            : dir;


        TrackHeadPersistenceData headData = new TrackHeadPersistenceData(headId, parentHeadId, headNumber, branchDepth, pos, dir, initialDir);
        headData.complete = headTag.getBoolean(TrackPlacementNbtKeys.COMPLETE);
        headData.blocksSinceLastCurve = headTag.getInt(TrackPlacementNbtKeys.BLOCKS_SINCE_LAST_CURVE);
        headData.blocksSinceLastBranch = headTag.getInt(TrackPlacementNbtKeys.BLOCKS_SINCE_LAST_BRANCH);
        // NBT keys kept for backwards compatibility
        headData.previousCurveDirection = headTag.getInt(TrackPlacementNbtKeys.LAST_CURVE_DIRECTION_1);
        headData.priorCurveDirection = headTag.getInt(TrackPlacementNbtKeys.LAST_CURVE_DIRECTION_2);
        headData.lastPlacementWasEmergencyCurve = headTag.getBoolean(TrackPlacementNbtKeys.LAST_PLACEMENT_WAS_EMERGENCY_CURVE);
        headData.torchPlacementOffset = headTag.getInt(TrackPlacementNbtKeys.TORCH_PLACEMENT_OFFSET);
        headData.blocksSinceLastEvent = headTag.getInt(TrackPlacementNbtKeys.BLOCKS_SINCE_LAST_EVENT);

        // Load child branch IDs
        int childCount = headTag.getInt(TrackPlacementNbtKeys.CHILD_BRANCH_COUNT);
        for (int i = 0; i < childCount; i++) {
            if (headTag.contains(TrackPlacementNbtKeys.CHILD_BRANCH_PREFIX + i)) {
                headData.childBranchIds.add(headTag.getUUID(TrackPlacementNbtKeys.CHILD_BRANCH_PREFIX + i));
            }
        }

        // Load village targeting data
        if (headTag.contains(TrackPlacementNbtKeys.TARGET_VILLAGE_ID)) {
            headData.targetVillageId = headTag.getUUID(TrackPlacementNbtKeys.TARGET_VILLAGE_ID);
        }
        if (headTag.contains(TrackPlacementNbtKeys.TARGET_VILLAGE_CENTER_X)) {
            headData.targetVillageCenter = new BlockPos(
                headTag.getInt(TrackPlacementNbtKeys.TARGET_VILLAGE_CENTER_X),
                headTag.getInt(TrackPlacementNbtKeys.TARGET_VILLAGE_CENTER_Y),
                headTag.getInt(TrackPlacementNbtKeys.TARGET_VILLAGE_CENTER_Z)
            );
        }
        headData.initialDistanceToVillage = headTag.getInt(TrackPlacementNbtKeys.INITIAL_DISTANCE_TO_VILLAGE);
        headData.hasReachedVillage = headTag.getBoolean(TrackPlacementNbtKeys.HAS_REACHED_VILLAGE);
        headData.villageDeferCount = headTag.getInt(TrackPlacementNbtKeys.VILLAGE_DEFER_COUNT);

        // Load pause state
        headData.paused = headTag.getBoolean(TrackPlacementNbtKeys.PAUSED);
        headData.pausedAtTime = headTag.getLong(TrackPlacementNbtKeys.PAUSED_AT_TIME);

        // Load extended village confirmation state
        headData.villageConfirmed = headTag.getBoolean(TrackPlacementNbtKeys.VILLAGE_CONFIRMED);
        headData.runwayConfirmed = headTag.getBoolean(TrackPlacementNbtKeys.RUNWAY_CONFIRMED);
        headData.stationPlaced = headTag.getBoolean(TrackPlacementNbtKeys.STATION_PLACED);

        // Load pre-computed village layout bounds
        if (headTag.contains(TrackPlacementNbtKeys.LAYOUT_TOTAL_BOUNDS)) {
            headData.layoutTotalBounds = headTag.getIntArray(TrackPlacementNbtKeys.LAYOUT_TOTAL_BOUNDS);
            if (headData.layoutTotalBounds.length != 6) {
                headData.layoutTotalBounds = null;
            }
        }
        if (headTag.contains(TrackPlacementNbtKeys.LAYOUT_CHUNK_BOUNDS)) {
            headData.layoutChunkBounds = headTag.getIntArray(TrackPlacementNbtKeys.LAYOUT_CHUNK_BOUNDS);
            if (headData.layoutChunkBounds.length != 4) {
                headData.layoutChunkBounds = null;
            }
        }
        if (headTag.contains(TrackPlacementNbtKeys.APPROACH_WAYPOINT)) {
            headData.approachWaypoint = BlockPos.of(headTag.getLong(TrackPlacementNbtKeys.APPROACH_WAYPOINT));
        }

        // Load locked station plan
        if (headTag.contains("locked_station_plan")) {
            int[] planData = headTag.getIntArray("locked_station_plan");
            if (planData.length >= 14) {
                headData.lockedStationPlanData = planData;
            }
        }

        // Load runway data if confirmed
        if (headData.runwayConfirmed && headTag.contains(TrackPlacementNbtKeys.RUNWAY_STATION_POS)) {
            headData.runwayStationPosition = BlockPos.of(headTag.getLong(TrackPlacementNbtKeys.RUNWAY_STATION_POS));
            headData.runwayTrackDirection = Direction.from3DDataValue(headTag.getInt(TrackPlacementNbtKeys.RUNWAY_TRACK_DIR));
            headData.runwayApproachDirection = Direction.from3DDataValue(headTag.getInt(TrackPlacementNbtKeys.RUNWAY_APPROACH_DIR));
            headData.runwayTrackStart = BlockPos.of(headTag.getLong(TrackPlacementNbtKeys.RUNWAY_TRACK_START));
            headData.runwayTrackEnd = BlockPos.of(headTag.getLong(TrackPlacementNbtKeys.RUNWAY_TRACK_END));
        }

        // Load branch origin state
        if (headTag.contains(TrackPlacementNbtKeys.BRANCH_ORIGIN_POS)) {
            headData.branchOriginPos = BlockPos.of(headTag.getLong(TrackPlacementNbtKeys.BRANCH_ORIGIN_POS));
        }
        if (headTag.contains(TrackPlacementNbtKeys.PARENT_TRACK_DIRECTION)) {
            headData.parentTrackDirection = Direction.from3DDataValue(headTag.getInt(TrackPlacementNbtKeys.PARENT_TRACK_DIRECTION));
        }

        // Load diagonal travel state
        if (headTag.contains(TrackPlacementNbtKeys.DIAGONAL_HEADING)) {
            headData.diagonalHeading = headTag.getString(TrackPlacementNbtKeys.DIAGONAL_HEADING);
        }

        // Load exploration target
        if (headTag.contains(TrackPlacementNbtKeys.EXPLORATION_TARGET)) {
            headData.explorationTarget = BlockPos.of(headTag.getLong(TrackPlacementNbtKeys.EXPLORATION_TARGET));
        }

        return headData;
    }

    /**
     * Saves data to NBT.
     */
    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putBoolean(TrackPlacementNbtKeys.INITIATED, initiated);

        if (initialTrackDirection != null) {
            tag.putInt(TrackPlacementNbtKeys.INITIAL_TRACK_DIRECTION, initialTrackDirection.get3DDataValue());
        }

        // Save nextHeadNumber
        tag.putInt(TrackPlacementNbtKeys.NEXT_HEAD_NUMBER, nextHeadNumber);

        // Save all heads as a list
        ListTag headsListTag = new ListTag();
        for (TrackHeadPersistenceData headData : heads) {
            CompoundTag headTag = new CompoundTag();
            saveHeadData(headTag, headData);
            headsListTag.add(headTag);
        }
        tag.put(TrackPlacementNbtKeys.HEADS, headsListTag);

        return tag;
    }

    /**
     * Saves a single head to NBT.
     */
    private void saveHeadData(CompoundTag headTag, TrackHeadPersistenceData headData) {

        headTag.putUUID(TrackPlacementNbtKeys.HEAD_ID, headData.headId);
        if (headData.parentHeadId != null) {
            headTag.putUUID(TrackPlacementNbtKeys.PARENT_HEAD_ID, headData.parentHeadId);
        }
        headTag.putInt(TrackPlacementNbtKeys.HEAD_NUMBER, headData.headNumber);
        headTag.putInt(TrackPlacementNbtKeys.BRANCH_DEPTH, headData.branchDepth);
        headTag.putInt(TrackPlacementNbtKeys.POS_X, headData.position.getX());
        headTag.putInt(TrackPlacementNbtKeys.POS_Y, headData.position.getY());
        headTag.putInt(TrackPlacementNbtKeys.POS_Z, headData.position.getZ());
        headTag.putInt(TrackPlacementNbtKeys.DIRECTION, headData.direction.get3DDataValue());
        headTag.putInt(TrackPlacementNbtKeys.INITIAL_DIRECTION, headData.initialDirection.get3DDataValue());
        headTag.putBoolean(TrackPlacementNbtKeys.COMPLETE, headData.complete);
        headTag.putInt(TrackPlacementNbtKeys.BLOCKS_SINCE_LAST_CURVE, headData.blocksSinceLastCurve);
        headTag.putInt(TrackPlacementNbtKeys.BLOCKS_SINCE_LAST_BRANCH, headData.blocksSinceLastBranch);
        // NBT keys kept for backwards compatibility
        headTag.putInt(TrackPlacementNbtKeys.LAST_CURVE_DIRECTION_1, headData.previousCurveDirection);
        headTag.putInt(TrackPlacementNbtKeys.LAST_CURVE_DIRECTION_2, headData.priorCurveDirection);
        headTag.putBoolean(TrackPlacementNbtKeys.LAST_PLACEMENT_WAS_EMERGENCY_CURVE, headData.lastPlacementWasEmergencyCurve);
        headTag.putInt(TrackPlacementNbtKeys.TORCH_PLACEMENT_OFFSET, headData.torchPlacementOffset);
        headTag.putInt(TrackPlacementNbtKeys.BLOCKS_SINCE_LAST_EVENT, headData.blocksSinceLastEvent);

        // Save child branch IDs
        headTag.putInt(TrackPlacementNbtKeys.CHILD_BRANCH_COUNT, headData.childBranchIds.size());
        for (int i = 0; i < headData.childBranchIds.size(); i++) {
            headTag.putUUID(TrackPlacementNbtKeys.CHILD_BRANCH_PREFIX + i, headData.childBranchIds.get(i));
        }

        // Save village targeting data
        if (headData.targetVillageId != null) {
            headTag.putUUID(TrackPlacementNbtKeys.TARGET_VILLAGE_ID, headData.targetVillageId);
        }
        if (headData.targetVillageCenter != null) {
            headTag.putInt(TrackPlacementNbtKeys.TARGET_VILLAGE_CENTER_X, headData.targetVillageCenter.getX());
            headTag.putInt(TrackPlacementNbtKeys.TARGET_VILLAGE_CENTER_Y, headData.targetVillageCenter.getY());
            headTag.putInt(TrackPlacementNbtKeys.TARGET_VILLAGE_CENTER_Z, headData.targetVillageCenter.getZ());
        }
        headTag.putInt(TrackPlacementNbtKeys.INITIAL_DISTANCE_TO_VILLAGE, headData.initialDistanceToVillage);
        headTag.putBoolean(TrackPlacementNbtKeys.HAS_REACHED_VILLAGE, headData.hasReachedVillage);
        headTag.putInt(TrackPlacementNbtKeys.VILLAGE_DEFER_COUNT, headData.villageDeferCount);

        // Save pause state
        headTag.putBoolean(TrackPlacementNbtKeys.PAUSED, headData.paused);
        headTag.putLong(TrackPlacementNbtKeys.PAUSED_AT_TIME, headData.pausedAtTime);

        // Save extended village confirmation state
        headTag.putBoolean(TrackPlacementNbtKeys.VILLAGE_CONFIRMED, headData.villageConfirmed);
        headTag.putBoolean(TrackPlacementNbtKeys.RUNWAY_CONFIRMED, headData.runwayConfirmed);
        headTag.putBoolean(TrackPlacementNbtKeys.STATION_PLACED, headData.stationPlaced);

        // Save pre-computed village layout bounds
        if (headData.layoutTotalBounds != null) {
            headTag.putIntArray(TrackPlacementNbtKeys.LAYOUT_TOTAL_BOUNDS, headData.layoutTotalBounds);
        }
        if (headData.layoutChunkBounds != null) {
            headTag.putIntArray(TrackPlacementNbtKeys.LAYOUT_CHUNK_BOUNDS, headData.layoutChunkBounds);
        }
        if (headData.approachWaypoint != null) {
            headTag.putLong(TrackPlacementNbtKeys.APPROACH_WAYPOINT, headData.approachWaypoint.asLong());
        }

        // Save locked station plan
        if (headData.lockedStationPlanData != null) {
            headTag.putIntArray("locked_station_plan", headData.lockedStationPlanData);
        }

        // Save runway data if confirmed
        if (headData.runwayConfirmed && headData.runwayStationPosition != null) {
            headTag.putLong(TrackPlacementNbtKeys.RUNWAY_STATION_POS, headData.runwayStationPosition.asLong());
            headTag.putInt(TrackPlacementNbtKeys.RUNWAY_TRACK_DIR, headData.runwayTrackDirection.get3DDataValue());
            headTag.putInt(TrackPlacementNbtKeys.RUNWAY_APPROACH_DIR, headData.runwayApproachDirection.get3DDataValue());
            headTag.putLong(TrackPlacementNbtKeys.RUNWAY_TRACK_START, headData.runwayTrackStart.asLong());
            headTag.putLong(TrackPlacementNbtKeys.RUNWAY_TRACK_END, headData.runwayTrackEnd.asLong());
        }

        // Save branch origin state
        if (headData.branchOriginPos != null) {
            headTag.putLong(TrackPlacementNbtKeys.BRANCH_ORIGIN_POS, headData.branchOriginPos.asLong());
        }
        if (headData.parentTrackDirection != null) {
            headTag.putInt(TrackPlacementNbtKeys.PARENT_TRACK_DIRECTION, headData.parentTrackDirection.get3DDataValue());
        }

        // Save diagonal travel state
        if (headData.diagonalHeading != null) {
            headTag.putString(TrackPlacementNbtKeys.DIAGONAL_HEADING, headData.diagonalHeading);
        }

        // Save exploration target
        if (headData.explorationTarget != null) {
            headTag.putLong(TrackPlacementNbtKeys.EXPLORATION_TARGET, headData.explorationTarget.asLong());
        }
    }

    /**
     * Gets or creates the SavedData for a world.
     */
    public static TrackPlacementSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
            TrackPlacementSavedData::new,
            TrackPlacementSavedData::new,
            DATA_NAME
        );
    }

    // Getters
    public boolean isInitiated() {
        return initiated;
    }

    @Nullable
    public Direction getInitialTrackDirection() {
        return initialTrackDirection;
    }

    /**
     * Gets all head data (defensive copy).
     */
    public List<TrackHeadPersistenceData> getAllHeads() {
        return new ArrayList<>(heads);
    }

    /**
     * Gets head data by UUID.
     */
    @Nullable
    public TrackHeadPersistenceData getHeadById(UUID headId) {
        return heads.stream()
            .filter(h -> h.headId.equals(headId))
            .findFirst()
            .orElse(null);
    }

    /**
     * Checks if all heads are complete.
     */
    public boolean areAllHeadsComplete() {
        return heads.stream().allMatch(h -> h.complete);
    }

    /**
     * Gets the next head number counter.
     */
    public int getNextHeadNumber() {
        return nextHeadNumber;
    }

    /**
     * Sets the next head number counter.
     */
    public void setNextHeadNumber(int nextHeadNumber) {
        this.nextHeadNumber = nextHeadNumber;
        setDirty();
    }

    // Setters (all call setDirty() to mark for save)

    /**
     * Marks track placement as initiated.
     */
    public void markInitiated(Direction initialDirection) {
        this.initiated = true;
        this.initialTrackDirection = initialDirection;
        setDirty();
    }

    /**
     * Updates head data by syncing from a TrackExpansionHead.
     */
    public void updateHeadFromExpansionHead(TrackExpansionHead head) {
        TrackHeadPersistenceData headData = getHeadById(head.getHeadId());
        if (headData == null) {
            // Head doesn't exist in saved data yet, create it
            headData = new TrackHeadPersistenceData(
                head.getHeadId(),
                head.getParentHeadId(),
                head.getHeadNumber(),
                head.getBranchDepth(),
                head.getPosition(),
                head.getDirection(),
                head.getInitialDirection()
            );
            heads.add(headData);
        }

        headData.position = head.getPosition();
        headData.direction = head.getDirection();
        headData.complete = head.isComplete();
        headData.blocksSinceLastCurve = head.getBlocksSinceLastCurve();
        headData.blocksSinceLastBranch = head.getBlocksSinceLastBranch();
        headData.previousCurveDirection = head.getPreviousCurveDirection().getValue();
        headData.priorCurveDirection = head.getPriorCurveDirection().getValue();
        headData.lastPlacementWasEmergencyCurve = head.wasLastPlacementEmergencyCurve();
        headData.torchPlacementOffset = head.getTorchPlacementOffset();
        headData.blocksSinceLastEvent = head.getBlocksSinceLastEvent();
        headData.childBranchIds.clear();
        headData.childBranchIds.addAll(head.getChildBranchIds());

        headData.paused = head.isPaused();
        headData.pausedAtTime = head.getPausedAtTime();

        var villageState = head.getVillageState();
        headData.targetVillageId = villageState.getTargetVillageId();
        headData.targetVillageCenter = villageState.getTargetVillageCenter();
        headData.initialDistanceToVillage = villageState.getInitialDistanceToVillage();
        headData.hasReachedVillage = villageState.hasReachedVillage();
        headData.villageDeferCount = villageState.getVillageDeferCount();

        headData.villageConfirmed = villageState.isVillageConfirmed();
        headData.runwayConfirmed = villageState.hasConfirmedRunway();
        headData.stationPlaced = villageState.isStationPlaced();

        var runway = villageState.getConfirmedRunway();
        if (runway != null) {
            headData.runwayStationPosition = runway.stationPosition;
            headData.runwayTrackDirection = runway.trackDirection;
            headData.runwayApproachDirection = runway.approachDirection;
            headData.runwayTrackStart = runway.trackStart;
            headData.runwayTrackEnd = runway.trackEnd;
        }

        headData.approachWaypoint = villageState.getApproachWaypoint();

        // Save pre-computed layout bounds
        var layout = villageState.getPrecomputedLayout();
        if (layout != null) {
            var tb = layout.totalBounds();
            headData.layoutTotalBounds = new int[]{ tb.minX(), tb.minY(), tb.minZ(), tb.maxX(), tb.maxY(), tb.maxZ() };
            var cb = layout.chunkBounds();
            headData.layoutChunkBounds = new int[]{ cb.minChunkX(), cb.maxChunkX(), cb.minChunkZ(), cb.maxChunkZ() };
        }

        // Sync branch origin state
        headData.branchOriginPos = head.getBranchOriginPos();
        headData.parentTrackDirection = head.getParentTrackDirection();

        // Sync diagonal travel state
        headData.diagonalHeading = head.isDiagonal() ? head.getDiagonalHeading().name() : null;

        // Sync exploration target
        headData.explorationTarget = villageState.getExplorationTarget();

        var lockedPlan = villageState.getLockedStationPlan();
        if (lockedPlan != null) {
            headData.lockedStationPlanData = lockedPlan.toIntArray();
        }

        setDirty();
    }

}
