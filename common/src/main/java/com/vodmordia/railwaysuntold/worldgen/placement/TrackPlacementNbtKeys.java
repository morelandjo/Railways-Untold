package com.vodmordia.railwaysuntold.worldgen.placement;


/**
 * NBT key constants for track placement state serialization.
 * Used in {@link TrackPlacementSavedData}.
 */
public final class TrackPlacementNbtKeys {

    private TrackPlacementNbtKeys() {
    }

    // Top-level keys
    public static final String INITIATED = "initiated";
    public static final String INITIAL_TRACK_DIRECTION = "initialTrackDirection";
    public static final String NEXT_HEAD_NUMBER = "nextHeadNumber";
    public static final String HEADS = "heads";

    // Head identity keys
    public static final String HEAD_ID = "headId";
    public static final String PARENT_HEAD_ID = "parentHeadId";
    public static final String HEAD_NUMBER = "headNumber";
    public static final String BRANCH_DEPTH = "branchDepth";

    // Head position/direction keys
    public static final String POS_X = "posX";
    public static final String POS_Y = "posY";
    public static final String POS_Z = "posZ";
    public static final String DIRECTION = "direction";
    public static final String INITIAL_DIRECTION = "initialDirection";
    public static final String COMPLETE = "complete";

    // Curve tracking keys
    public static final String BLOCKS_SINCE_LAST_CURVE = "blocksSinceLastCurve";
    public static final String BLOCKS_SINCE_LAST_BRANCH = "blocksSinceLastBranch";
    public static final String LAST_CURVE_DIRECTION_1 = "lastCurveDirection1";
    public static final String LAST_CURVE_DIRECTION_2 = "lastCurveDirection2";
    public static final String LAST_PLACEMENT_WAS_EMERGENCY_CURVE = "lastPlacementWasEmergencyCurve";
    public static final String TORCH_PLACEMENT_OFFSET = "torchPlacementOffset";

    // Child branch keys
    public static final String CHILD_BRANCH_COUNT = "childBranchCount";
    public static final String CHILD_BRANCH_PREFIX = "childBranch_";

    // Village targeting keys
    public static final String TARGET_VILLAGE_ID = "targetVillageId";
    public static final String TARGET_VILLAGE_CENTER_X = "targetVillageCenterX";
    public static final String TARGET_VILLAGE_CENTER_Y = "targetVillageCenterY";
    public static final String TARGET_VILLAGE_CENTER_Z = "targetVillageCenterZ";
    public static final String INITIAL_DISTANCE_TO_VILLAGE = "initialDistanceToVillage";
    public static final String HAS_REACHED_VILLAGE = "hasReachedVillage";

    // Pause state keys
    public static final String PAUSED = "paused";
    public static final String PAUSED_AT_TIME = "pausedAtTime";

    // Village confirmation keys
    public static final String VILLAGE_CONFIRMED = "villageConfirmed";
    public static final String RUNWAY_CONFIRMED = "runwayConfirmed";
    public static final String STATION_PLACED = "stationPlaced";

    // Village defer tracking
    public static final String VILLAGE_DEFER_COUNT = "villageDeferCount";

    // Pre-computed village layout keys
    public static final String LAYOUT_TOTAL_BOUNDS = "layoutTotalBounds";
    public static final String LAYOUT_CHUNK_BOUNDS = "layoutChunkBounds";
    public static final String APPROACH_WAYPOINT = "approachWaypoint";

    // Runway data keys
    public static final String RUNWAY_STATION_POS = "runwayStationPos";
    public static final String RUNWAY_TRACK_DIR = "runwayTrackDir";
    public static final String RUNWAY_APPROACH_DIR = "runwayApproachDir";
    public static final String RUNWAY_TRACK_START = "runwayTrackStart";
    public static final String RUNWAY_TRACK_END = "runwayTrackEnd";

    // Event state keys
    public static final String BLOCKS_SINCE_LAST_EVENT = "blocksSinceLastEvent";

    // Branch origin keys
    public static final String BRANCH_ORIGIN_POS = "branchOriginPos";
    public static final String PARENT_TRACK_DIRECTION = "parentTrackDirection";

    // Diagonal travel keys
    public static final String DIAGONAL_HEADING = "diagonalHeading";

    // Exploration target key
    public static final String EXPLORATION_TARGET = "explorationTarget";
}
