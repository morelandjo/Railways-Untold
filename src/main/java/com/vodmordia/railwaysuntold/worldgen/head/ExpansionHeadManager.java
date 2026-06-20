package com.vodmordia.railwaysuntold.worldgen.head;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.spatial.DirectionUtil;
import com.vodmordia.railwaysuntold.worldgen.head.state.HeadStateSnapshot.VillageTargetingSnapshot;
import com.vodmordia.railwaysuntold.worldgen.placement.TrackHeadPersistenceData;
import com.vodmordia.railwaysuntold.worldgen.placement.TrackPlacementSavedData;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRouteFactory;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRouteRegistry;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRouteSavedData;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainSampler;
import com.vodmordia.railwaysuntold.worldgen.village.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages expansion head state and lifecycle.
 */
public class ExpansionHeadManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final List<TrackExpansionHead> heads;
    private int nextHeadNumber = 1;
    private List<TrackExpansionHead> cachedActiveHeads = null;

    public ExpansionHeadManager() {
        this.heads = new ArrayList<>();
    }

    /**
     * Restores head state from saved data.
     *
     * @param savedData The saved data to restore from
     * @param level     The server level (needed to re-cache villages)
     */
    public void restoreFromSavedData(TrackPlacementSavedData savedData, ServerLevel level) {
        heads.clear();

        int savedNextHeadNumber = savedData.getNextHeadNumber();
        if (savedNextHeadNumber > 0) {
            this.nextHeadNumber = savedNextHeadNumber;
        }

        List<TrackHeadPersistenceData> savedHeads = savedData.getAllHeads();

        for (TrackHeadPersistenceData headData : savedHeads) {
            try {
                TrackExpansionHead head = new TrackExpansionHead(
                        headData.headNumber,
                        headData.position,
                        headData.direction,
                        headData.initialDirection,
                        headData.headId,
                        headData.parentHeadId,
                        headData.branchDepth
                );

                VillageEdgeFinder.StationZone runway = null;
                if (headData.runwayConfirmed && headData.runwayStationPosition != null) {
                    runway = new VillageEdgeFinder.StationZone(
                            headData.runwayStationPosition,
                            headData.runwayTrackDirection,
                            headData.runwayApproachDirection,
                            headData.runwayTrackStart,
                            headData.runwayTrackEnd,
                            null  // VillageBounds not persisted, will be re-scanned if needed
                    );
                }

                var snapshot = com.vodmordia.railwaysuntold.worldgen.head.state.HeadStateSnapshot.builder()
                        .position(headData.position)
                        .direction(headData.direction)
                        .complete(headData.complete)
                        .blocksSinceLastCurve(headData.blocksSinceLastCurve)
                        .previousCurveDirection(headData.previousCurveDirection)
                        .priorCurveDirection(headData.priorCurveDirection)
                        .lastPlacementWasEmergencyCurve(headData.lastPlacementWasEmergencyCurve)
                        .blocksSinceLastBranch(headData.blocksSinceLastBranch)
                        .childBranchIds(headData.childBranchIds)
                        .torchPlacementOffset(headData.torchPlacementOffset)
                        .blocksSinceLastEvent(headData.blocksSinceLastEvent)
                        .paused(headData.paused)
                        .pausedAtTime(headData.pausedAtTime)
                        .villageTargeting(new VillageTargetingSnapshot(
                                headData.targetVillageId,
                                headData.targetVillageCenter,
                                headData.initialDistanceToVillage,
                                headData.hasReachedVillage,
                                headData.villageConfirmed,
                                headData.runwayConfirmed,
                                headData.stationPlaced,
                                runway,
                                headData.villageDeferCount,
                                headData.layoutTotalBounds,
                                headData.layoutChunkBounds,
                                headData.approachWaypoint,
                                headData.explorationTarget,
                                headData.lockedStationPlanData
                        ))
                        .diagonalHeading(headData.diagonalHeading)
                        .build();
                head.restoreFromSnapshot(snapshot);

                // Restore branch origin info
                if (headData.branchOriginPos != null && headData.parentTrackDirection != null) {
                    head.setBranchOrigin(headData.branchOriginPos, headData.parentTrackDirection);
                }

                heads.add(head);

                // Re-cache village assignment to prevent orphaned assignments after restart
                if (headData.targetVillageId != null && headData.targetVillageCenter != null) {
                    VillageInfo villageInfo = new VillageInfo(
                            headData.targetVillageCenter,
                            "unknown"  // Type is unknown until confirmed, but that's okay
                    );
                    VillageLocator.cachePredictedVillage(level, villageInfo);
                }

                updateNextHeadNumberIfNeeded(headData.headNumber);
            } catch (RuntimeException e) {
                LOGGER.error("[HEAD-MANAGER] Failed to restore head {}: {}", headData.headNumber, e.getMessage(), e);
            }
        }

        validateParentReferences();
        restoreCoarseRoutes(level);
        performCrashRecoveryScan(level);
        invalidateActiveHeadsCache();
    }

    private void validateParentReferences() {
        Set<UUID> headIds = new HashSet<>();
        for (TrackExpansionHead head : heads) {
            headIds.add(head.getHeadId());
        }

        for (TrackExpansionHead head : heads) {
            UUID parentId = head.getParentHeadId();
            if (parentId != null && !headIds.contains(parentId)) {
                LOGGER.warn("[HEAD-MANAGER] Head {} has orphaned parent reference: {}",
                        head.getHeadNumber(), parentId);
            }
        }
    }

    /**
     * Initializes expansion heads with a custom starting position for Head 1.
     *
     * @param initialTrackPos       The initial track position (used for Head 2)
     * @param head1StartPos         Custom starting position for Head 1 (e.g., after train)
     * @param initialTrackDirection The initial track direction
     * @param level                 Server level (for village targeting)
     * @param config                Configuration (for village targeting)
     * @param tracker               Village assignment tracker (for village targeting)
     */
    public void initializeHeadsWithCustomStart(BlockPos initialTrackPos, BlockPos head1StartPos,
                                               Direction initialTrackDirection,
                                               @Nullable ServerLevel level, @Nullable RailwaysUntoldConfig config,
                                               @Nullable VillageAssignmentTracker tracker) {
        initializeHeadsCore(head1StartPos, initialTrackPos, initialTrackDirection, level, config, tracker);
    }

    /**
     * Initializes expansion heads with explicit starting positions for both heads.
     *
     * @param head1StartPos         Starting position for Head 1 (positive direction)
     * @param head2StartPos         Starting position for Head 2 (negative direction)
     * @param initialTrackDirection The initial track direction
     * @param level                 Server level (for village targeting)
     * @param config                Configuration (for village targeting)
     * @param tracker               Village assignment tracker (for village targeting)
     */
    public void initializeHeadsWithBothCustomStarts(BlockPos head1StartPos, BlockPos head2StartPos,
                                                    Direction initialTrackDirection,
                                                    @Nullable ServerLevel level, @Nullable RailwaysUntoldConfig config,
                                                    @Nullable VillageAssignmentTracker tracker) {
        heads.clear();

        Direction positiveDir = getPositiveDirection(initialTrackDirection);
        Direction negativeDir = positiveDir.getOpposite();

        TrackExpansionHead head1 = new TrackExpansionHead(1, head1StartPos, positiveDir);
        TrackExpansionHead head2 = new TrackExpansionHead(2, head2StartPos, negativeDir);

        tryAssignVillagesToHeads(List.of(head1, head2), level, config, tracker);

        heads.add(head1);
        heads.add(head2);
        nextHeadNumber = 3;
        invalidateActiveHeadsCache();
    }

    private void initializeHeadsCore(BlockPos head1Pos, BlockPos initialTrackPos, Direction initialTrackDirection,
                                     @Nullable ServerLevel level, @Nullable RailwaysUntoldConfig config,
                                     @Nullable VillageAssignmentTracker tracker) {
        heads.clear();

        Direction positiveDir = getPositiveDirection(initialTrackDirection);
        Direction negativeDir = positiveDir.getOpposite();
        BlockPos head2Pos = initialTrackPos.relative(negativeDir);

        TrackExpansionHead head1 = new TrackExpansionHead(1, head1Pos, positiveDir);
        TrackExpansionHead head2 = new TrackExpansionHead(2, head2Pos, negativeDir);

        tryAssignVillagesToHeads(List.of(head1, head2), level, config, tracker);

        heads.add(head1);
        heads.add(head2);
        nextHeadNumber = 3;
        invalidateActiveHeadsCache();
    }

    /**
     * Gets the positive direction for track expansion.
     * For NORTH direction tracks (NS), positive is SOUTH
     * For EAST direction tracks (EW), positive is EAST
     */
    public static Direction getPositiveDirection(Direction trackDir) {
        return DirectionUtil.getPositiveDirection(trackDir);
    }

    /**
     * Creates a new branch head from a parent head with optional village assignment.
     *
     * @param parent              The parent head spawning the branch
     * @param branchPosition      Starting position of the branch
     * @param branchDirection     Direction the branch will expand
     * @param targetVillageId     Optional village ID to assign to branch (can be null)
     * @param targetVillageCenter Optional village center position (can be null)
     * @param level               Server level for registering village assignments (can be null if no village targeting)
     * @return The newly created branch head
     */
    public TrackExpansionHead createBranch(TrackExpansionHead parent, BlockPos branchPosition, Direction branchDirection,
                                           UUID targetVillageId, BlockPos targetVillageCenter,
                                           @Nullable ServerLevel level) {
        int branchHeadNumber = nextHeadNumber++;
        int branchDepth = parent.getBranchDepth() + 1;

        UUID branchHeadId = UUID.randomUUID();

        TrackExpansionHead branchHead = new TrackExpansionHead(
                branchHeadNumber,
                branchPosition,
                branchDirection,
                branchHeadId,
                parent.getHeadId(),
                branchDepth
        );

        boolean villageAssigned = false;
        if (targetVillageId != null && targetVillageCenter != null && level != null) {
            VillageAssignmentTracker tracker = VillageTargetingSavedData.get(level).getAssignmentTracker();
            villageAssigned = tracker.assignVillage(targetVillageId, branchHeadId);
            if (villageAssigned) {
                // Apply angular sector exclusion - same check used for all village assignments.
                // Without this, branch villages bypass the spawn-relative angle check entirely.
                BlockPos spawnPos = level.getSharedSpawnPos();
                double angle = com.vodmordia.railwaysuntold.util.spatial.DirectionUtil.computeAngleFromOrigin(spawnPos, targetVillageCenter);
                boolean tooClose = tracker.isAngleTooClose(angle, branchHeadId, 25.0);
                if (tooClose) {
                    tracker.unassignHead(branchHeadId);
                    villageAssigned = false;
                } else {
                    tracker.registerHeadAngle(branchHeadId, angle, false);
                }
            }
        }

        if (villageAssigned) {
            branchHead.getVillageState().setTarget(targetVillageId, targetVillageCenter, branchPosition);

            NoiseTerrainSampler sampler = NoiseTerrainSampler.forLevel(level);

            // Try layout prediction for branch village targeting
            net.minecraft.world.level.ChunkPos villageChunk = new net.minecraft.world.level.ChunkPos(targetVillageCenter);
            com.vodmordia.railwaysuntold.worldgen.village.PredictedVillageLayout layout =
                    com.vodmordia.railwaysuntold.worldgen.village.VillageLayoutPredictor.predict(level, villageChunk);

            Direction approach;
            BlockPos routeTarget;
            if (layout != null) {
                branchHead.getVillageState().setPrecomputedLayout(layout);
                approach = NoiseVillageApproachSelector.selectBestApproach(
                        sampler, layout, branchPosition, branchPosition.getY());
                routeTarget = com.vodmordia.railwaysuntold.worldgen.village.NoiseStationPositionCalculator
                        .getApproachWaypoint(sampler, approach, layout.chunkBounds(), branchPosition);
            } else {
                approach = NoiseVillageApproachSelector.selectBestApproach(
                        sampler, targetVillageCenter, branchPosition, branchPosition.getY());
                routeTarget = targetVillageCenter.relative(approach, 140);
            }

            branchHead.getVillageState().setPlannedApproachDirection(approach);
            CoarseRouteFactory.createAndAttach(level, branchHead, routeTarget);
        } else {
            // No village assigned - give the branch an exploration target so it still has
            // a coarse route for structure avoidance (pillager outposts, temples, etc.)
            if (level != null) {
                BlockPos explorationTarget = DirectionUtil.generateExplorationTarget(
                        branchPosition, branchDirection, 800, new java.util.Random());
                branchHead.getVillageState().setExplorationTarget(explorationTarget);
                CoarseRouteFactory.createAndAttach(level, branchHead, explorationTarget);
            }
        }

        branchHead.setBranchOrigin(branchPosition, parent.getDirection());

        heads.add(branchHead);
        invalidateActiveHeadsCache();
        parent.addChildBranch(branchHead.getHeadId());
        parent.resetBranchCounter();

        return branchHead;
    }

    /**
     * Creates a new pair of expansion heads from a new starting point.
     *
     * @param startPos       The starting track position
     * @param trackDirection The track direction (NORTH for N/S, EAST for E/W)
     * @param level          Server level (for village targeting)
     * @param config         Configuration (for village targeting)
     * @param tracker        Village assignment tracker (for village targeting)
     * @return List of the two newly created heads
     */
    public List<TrackExpansionHead> createNewStartingHeads(BlockPos startPos, Direction trackDirection,
                                                           @Nullable ServerLevel level,
                                                           @Nullable RailwaysUntoldConfig config,
                                                           @Nullable VillageAssignmentTracker tracker) {
        Direction positiveDir = getPositiveDirection(trackDirection);
        Direction negativeDir = positiveDir.getOpposite();

        BlockPos head1Pos = startPos.relative(positiveDir);
        BlockPos head2Pos = startPos.relative(negativeDir);

        int head1Number = nextHeadNumber++;
        int head2Number = nextHeadNumber++;

        TrackExpansionHead head1 = new TrackExpansionHead(head1Number, head1Pos, positiveDir);
        TrackExpansionHead head2 = new TrackExpansionHead(head2Number, head2Pos, negativeDir);

        tryAssignVillagesToHeads(List.of(head1, head2), level, config, tracker);

        heads.add(head1);
        heads.add(head2);
        invalidateActiveHeadsCache();

        return List.of(head1, head2);
    }

    /**
     * Creates a single head at startPos aimed at an explicit target position, and
     * registers it. The head faces along trackDirection's axis toward targetPos and
     * gets a coarse route to the target for structure avoidance. The caller is
     * responsible for scheduling the head's expansion.
     *
     * @param startPos       Starting position of the head
     * @param targetPos      The position the head should expand toward
     * @param trackDirection The track axis (NORTH for N/S, EAST for E/W)
     * @param level          Server level (for the coarse route; may be null)
     * @return The newly created and registered head
     */
    public TrackExpansionHead createTargetedHead(BlockPos startPos, BlockPos targetPos,
                                                 Direction trackDirection, @Nullable ServerLevel level) {
        Direction toward;
        if (trackDirection.getAxis() == Direction.Axis.X) {
            toward = targetPos.getX() >= startPos.getX() ? Direction.EAST : Direction.WEST;
        } else {
            toward = targetPos.getZ() >= startPos.getZ() ? Direction.SOUTH : Direction.NORTH;
        }

        TrackExpansionHead head = new TrackExpansionHead(nextHeadNumber++, startPos, toward);
        head.getVillageState().setExplorationTarget(targetPos);
        if (level != null) {
            CoarseRouteFactory.createAndAttach(level, head, targetPos);
        }

        heads.add(head);
        invalidateActiveHeadsCache();
        return head;
    }

    /**
     * Saves current state of all heads to persistent storage.
     *
     * @param savedData The saved data to update
     */
    public void saveState(TrackPlacementSavedData savedData) {
        invalidateActiveHeadsCache();
        savedData.setNextHeadNumber(nextHeadNumber);

        for (TrackExpansionHead head : heads) {
            savedData.updateHeadFromExpansionHead(head);
        }
    }

    public boolean isComplete() {
        return !heads.isEmpty() && heads.stream().allMatch(TrackExpansionHead::isComplete);
    }

    public boolean hasRestoredState() {
        return !heads.isEmpty();
    }

    /**
     * Gets all active (non-complete) heads. Cached to avoid repeated filtering.
     */
    public List<TrackExpansionHead> getActiveHeads() {
        if (cachedActiveHeads == null) {
            rebuildActiveHeadsCache();
        }
        return cachedActiveHeads;
    }

    public List<TrackExpansionHead> getPausedHeads() {
        return getActiveHeads().stream()
                .filter(TrackExpansionHead::isPaused)
                .collect(Collectors.toList());
    }

    private void rebuildActiveHeadsCache() {
        cachedActiveHeads = new ArrayList<>();
        for (TrackExpansionHead head : heads) {
            if (!head.isComplete()) {
                cachedActiveHeads.add(head);
            }
        }
    }

    public void invalidateActiveHeadsCache() {
        cachedActiveHeads = null;
    }

    public int getActiveHeadCount() {
        return getActiveHeads().size();
    }

    /**
     * Total number of heads ever created in this dimension, including ones that
     * have since completed, merged, or terminated. Head numbers are assigned
     * sequentially from 1, so the next number minus one is the running total.
     */
    public int getTotalHeadCount() {
        return nextHeadNumber - 1;
    }

    /**
     * Retrieves a head by its unique ID.
     *
     * @param headId The UUID of the head to find
     * @return The head with the given ID, or null if not found
     */
    @Nullable
    public TrackExpansionHead getHeadById(UUID headId) {
        for (TrackExpansionHead head : heads) {
            if (head.getHeadId().equals(headId)) {
                return head;
            }
        }
        return null;
    }

    /**
     * Find any other active head near the given position.
     *
     * @param pos           Position to check
     * @param excludeHeadId Head ID to exclude from check (the head doing the check)
     * @param radius        Manhattan distance radius to check
     * @return The nearby head if found, null otherwise
     */
    @Nullable
    public TrackExpansionHead findNearbyHead(BlockPos pos, UUID excludeHeadId, int radius) {
        for (TrackExpansionHead head : getActiveHeads()) {
            if (head.getHeadId().equals(excludeHeadId)) continue;
            // Never yield to a completed head. getActiveHeads() is a cache that is only rebuilt on
            // explicit invalidation, so a head completed since the last rebuild can still be in the
            // list; yielding to it stalls the live head forever (it reschedules every tick and the
            // dead head never moves out of range). Re-check completion at the point of use.
            if (head.isComplete()) continue;
            int distance = head.getPosition().distManhattan(pos);
            if (distance <= radius) {
                return head;
            }
        }
        return null;
    }

    private void tryAssignVillagesToHeads(List<TrackExpansionHead> headsToAssign,
                                          @Nullable ServerLevel level,
                                          @Nullable RailwaysUntoldConfig config,
                                          @Nullable VillageAssignmentTracker tracker) {
        if (level != null && config != null && tracker != null && config.ENABLE_VILLAGE_TARGETING) {
            for (TrackExpansionHead head : headsToAssign) {
                VillageHeadAssigner.tryAssignVillage(head, level, config, tracker);
            }
        }
    }

    /**
     * Restores coarse routes from saved data, loads them into the registry,
     * and re-attaches to their corresponding heads.
     */
    private void restoreCoarseRoutes(ServerLevel level) {
        CoarseRouteSavedData routeSavedData = CoarseRouteSavedData.get(level);
        CoarseRouteRegistry registry = CoarseRouteRegistry.forLevel(level);
        routeSavedData.loadIntoRegistry(registry, level);

        int derivedTargets = 0;
        for (TrackExpansionHead head : heads) {
            CoarseRoute route = registry.getRoute(head.getHeadId());
            if (route != null) {
                head.getTerrainPlanState().setCoarseRoute(route);

                if (head.getVillageState().getRouteTarget() == null && !route.getWaypoints().isEmpty()) {
                    List<CoarseRoute.CoarseWaypoint> waypoints = route.getWaypoints();
                    BlockPos lastWp = waypoints.get(waypoints.size() - 1).position();
                    head.getVillageState().setExplorationTarget(lastWp);
                    derivedTargets++;
                }
            }
        }

        if (derivedTargets > 0) {
            LOGGER.warn("[HEAD-MANAGER] Derived {} exploration targets from coarse routes (missing from save data)", derivedTargets);
        }
    }

    /**
     * Scans forward from each restored head's position to detect track that was
     * placed but not saved (e.g., due to a crash). Advances heads past any
     * existing track to prevent duplicate placement.
     */
    private void performCrashRecoveryScan(ServerLevel level) {
        for (TrackExpansionHead head : heads) {
            CrashRecoveryScanner.scanAndAdvance(head, level);
        }
    }

    private void updateNextHeadNumberIfNeeded(int headNumber) {
        if (headNumber >= nextHeadNumber) {
            nextHeadNumber = headNumber + 1;
        }
    }

}
