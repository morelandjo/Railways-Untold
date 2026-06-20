package com.vodmordia.railwaysuntold.worldgen.placement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persistence data structure for a single track expansion head.
 * Contains all mutable state that needs to be saved/loaded for world persistence.
 */
public class TrackHeadPersistenceData {

    public final UUID headId;
    public final UUID parentHeadId;
    public final int headNumber;
    public final int branchDepth;
    public BlockPos position;
    public Direction direction;
    public Direction initialDirection;
    public boolean complete;
    public int blocksSinceLastCurve;
    public int blocksSinceLastBranch;
    public int previousCurveDirection;
    public int priorCurveDirection;
    public boolean lastPlacementWasEmergencyCurve;
    public int torchPlacementOffset;
    public int blocksSinceLastEvent;
    public final List<UUID> childBranchIds;

    // Pause state
    public boolean paused;
    public long pausedAtTime;

    // Village targeting fields
    public UUID targetVillageId;
    public BlockPos targetVillageCenter;
    public int initialDistanceToVillage;
    public boolean hasReachedVillage;

    // Village defer tracking
    public int villageDeferCount;

    // Extended village confirmation state
    public boolean villageConfirmed;
    public boolean runwayConfirmed;
    public boolean stationPlaced;

    // Pre-computed village layout bounds (null if not pre-computed)
    public int[] layoutTotalBounds;  // [minX, minY, minZ, maxX, maxY, maxZ] or null
    public int[] layoutChunkBounds;  // [minChunkX, maxChunkX, minChunkZ, maxChunkZ] or null

    // Approach waypoint - cached target for diagonal exit planning
    public BlockPos approachWaypoint;

    // Locked station plan data (serialized as int array, null if no plan)
    public int[] lockedStationPlanData;

    // Branch origin state
    public BlockPos branchOriginPos;
    public Direction parentTrackDirection;

    // Diagonal travel state
    public String diagonalHeading;

    // Exploration target (persisted so coarse routes have a destination after reload)
    public BlockPos explorationTarget;

    // Runway data (serializable form of StationZone)
    public BlockPos runwayStationPosition;
    public Direction runwayTrackDirection;
    public Direction runwayApproachDirection;
    public BlockPos runwayTrackStart;
    public BlockPos runwayTrackEnd;

    public TrackHeadPersistenceData(UUID headId, UUID parentHeadId, int headNumber, int branchDepth,
                                    BlockPos position, Direction direction, Direction initialDirection) {
        this.headId = headId;
        this.parentHeadId = parentHeadId;
        this.headNumber = headNumber;
        this.branchDepth = branchDepth;
        this.position = position;
        this.direction = direction;
        this.initialDirection = initialDirection;
        this.complete = false;
        this.blocksSinceLastCurve = 0;
        this.blocksSinceLastBranch = 0;
        this.previousCurveDirection = 0;
        this.priorCurveDirection = 0;
        this.lastPlacementWasEmergencyCurve = false;
        this.childBranchIds = new ArrayList<>();
        this.targetVillageId = null;
        this.targetVillageCenter = null;
        this.initialDistanceToVillage = 0;
        this.hasReachedVillage = false;
    }
}
