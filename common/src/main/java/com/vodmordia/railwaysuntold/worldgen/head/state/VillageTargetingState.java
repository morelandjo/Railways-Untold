package com.vodmordia.railwaysuntold.worldgen.head.state;

import com.vodmordia.railwaysuntold.worldgen.head.state.HeadStateSnapshot.VillageTargetingSnapshot;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.SchematicPlacer;
import com.vodmordia.railwaysuntold.worldgen.planner.ApproachState;
import com.vodmordia.railwaysuntold.worldgen.planner.PathExecutionState;
import com.vodmordia.railwaysuntold.worldgen.planner.PathSegment;
import com.vodmordia.railwaysuntold.worldgen.village.PredictedVillageLayout;
import com.vodmordia.railwaysuntold.worldgen.village.RunwayPositionCalculator;
import com.vodmordia.railwaysuntold.worldgen.village.StationPlan;
import com.vodmordia.railwaysuntold.worldgen.village.VillageEdgeFinder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Manages all state related to targeting, approaching, and placing stations at villages.
 */
public class VillageTargetingState implements PathExecutionState {

    private UUID targetVillageId;
    private BlockPos targetVillageCenter;
    private int initialDistanceToVillage;
    private boolean hasReachedVillage;
    private int villageDeferCount;

    // Set when village is first assigned - determines which side we approach for the station
    private Direction plannedApproachDirection;

    // Cached approach waypoint - the point DiagonalTravelRule targets instead of the steering target
    @Nullable private BlockPos approachWaypoint;

    // Pre-computed village layout from structure seed (null if prediction failed or not attempted)
    @Nullable
    private PredictedVillageLayout precomputedLayout;

    // Pre-computed station plan from NoiseStationPlanner - contains locked station position,
    // entry/exit points, and alternatives. Set at village assignment time.
    @Nullable
    private StationPlan lockedStationPlan;

    /**
     * Ranked list of alternative arrival poses. The first entry is the
     * primary (same as {@link #lockedStationPlan}); subsequent entries are backup
     * poses to try if the primary's compile+fit check fails. Populated at village
     * assignment time, read by the async route planner on the first attempt, and
     * cleared when the village is abandoned or the station is committed.
     */
    private java.util.List<StationPlan> stationPlanAlternatives = java.util.List.of();

    // Cumulative turn tracking for circling detection
    private double cumulativeTurnDegrees = 0.0;
    @Nullable private Direction lastTrackedDirection = null;

    private final RunwayConfirmationState runwayConfirmationState;
    private final ApproachState approachState;
    private final ExplorationState explorationState;

    public VillageTargetingState() {
        this.runwayConfirmationState = new RunwayConfirmationState();
        this.approachState = new ApproachState();
        this.explorationState = new ExplorationState();
        clear();
    }


    public boolean hasTargetVillage() {
        return targetVillageId != null && !hasReachedVillage;
    }

    public UUID getTargetVillageId() {
        return targetVillageId;
    }

    public BlockPos getTargetVillageCenter() {
        return targetVillageCenter;
    }

    public int getInitialDistanceToVillage() {
        return initialDistanceToVillage;
    }

    public boolean hasReachedVillage() {
        return hasReachedVillage;
    }

    public void setTarget(UUID villageId, BlockPos villageCenter, BlockPos currentPos) {
        this.targetVillageId = villageId;
        // Use the track's Y level for the village center to ensure distance calculations
        // are horizontal-only.
        if (villageCenter != null && currentPos != null) {
            this.targetVillageCenter = new BlockPos(villageCenter.getX(), currentPos.getY(), villageCenter.getZ());
        } else {
            this.targetVillageCenter = villageCenter;
        }
        this.initialDistanceToVillage = currentPos != null && this.targetVillageCenter != null ?
                currentPos.distManhattan(this.targetVillageCenter) : 0;
        this.hasReachedVillage = false;
        this.plannedApproachDirection = null;
        this.villageDeferCount = 0;
    }

    public void setPlannedApproachDirection(Direction direction) {
        this.plannedApproachDirection = direction;
    }

    public void setApproachWaypoint(@Nullable BlockPos waypoint) {
        this.approachWaypoint = waypoint;
    }

    @Nullable
    public BlockPos getApproachWaypoint() {
        return approachWaypoint;
    }

    public void setPrecomputedLayout(@Nullable PredictedVillageLayout layout) {
        this.precomputedLayout = layout;
    }

    @Nullable
    public PredictedVillageLayout getPrecomputedLayout() {
        return precomputedLayout;
    }

    /**
     * Sets the ranked list of station plan alternatives (retry source).
     * The caller separately locks the primary via {@link #setLockedStationPlan}.
     */
    public void setStationPlanAlternatives(java.util.List<StationPlan> alternatives) {
        this.stationPlanAlternatives = alternatives == null ? java.util.List.of() : alternatives;
    }

    public java.util.List<StationPlan> getStationPlanAlternatives() {
        return stationPlanAlternatives;
    }

    public void setLockedStationPlan(@Nullable StationPlan plan) {
        this.lockedStationPlan = plan;
    }

    @Nullable
    public StationPlan getLockedStationPlan() {
        return lockedStationPlan;
    }

    /**
     * Calculates the expected station position based on the planned approach direction
     * and precomputed village layout.
     *
     * @return The station target position, or the village center if no approach is planned
     */
    @Nullable
    public BlockPos getStationTarget() {
        if (targetVillageCenter == null || plannedApproachDirection == null) {
            return targetVillageCenter;
        }

        if (precomputedLayout != null) {
            int runwayChunk = RunwayPositionCalculator.getRunwayChunk(
                    plannedApproachDirection, precomputedLayout.chunkBounds());
            int centerChunkX = (precomputedLayout.chunkBounds().minChunkX()
                    + precomputedLayout.chunkBounds().maxChunkX()) / 2;
            int centerChunkZ = (precomputedLayout.chunkBounds().minChunkZ()
                    + precomputedLayout.chunkBounds().maxChunkZ()) / 2;
            Direction stationSide = plannedApproachDirection;

            int x, z;
            if (stationSide == Direction.NORTH || stationSide == Direction.SOUTH) {
                x = (centerChunkX << 4) + 8;
                z = (runwayChunk << 4) + 8;
            } else {
                x = (runwayChunk << 4) + 8;
                z = (centerChunkZ << 4) + 8;
            }
            return new BlockPos(x, targetVillageCenter.getY(), z);
        }

        // Fallback: approximate station position from village center
        Direction stationSide = plannedApproachDirection.getClockWise();
        int offsetDistance = 48;
        int offsetX = stationSide.getStepX() * offsetDistance
                - plannedApproachDirection.getStepX() * (offsetDistance / 2);
        int offsetZ = stationSide.getStepZ() * offsetDistance
                - plannedApproachDirection.getStepZ() * (offsetDistance / 2);

        return targetVillageCenter.offset(offsetX, 0, offsetZ);
    }

    /**
     * Returns the current route target - the position the coarse route should aim at.
     * Priority: approach waypoint -> offset from village center -> village center -> exploration target.
     */
    @Nullable
    public BlockPos getRouteTarget() {
        if (hasTargetVillage()) {
            if (approachWaypoint != null) return approachWaypoint;
            if (targetVillageCenter != null && plannedApproachDirection != null) {
                return targetVillageCenter.relative(plannedApproachDirection, 140);
            }
            return targetVillageCenter;
        }
        return explorationState.getExplorationTarget();
    }

    public void clearTarget() {
        this.targetVillageId = null;
        this.targetVillageCenter = null;
        this.initialDistanceToVillage = 0;
        this.hasReachedVillage = false;
        this.plannedApproachDirection = null;
        this.approachWaypoint = null;
        this.precomputedLayout = null;
        this.lockedStationPlan = null;
        this.stationPlanAlternatives = java.util.List.of();
        // Do NOT reset villageDeferCount here. Periodic retargeting calls clear()
        // on every tick when layout prediction fails
        resetTurnTracking();
    }

    /**
     * Records a direction change and accumulates the turn angle.
     * Called after each successful placement to detect circling behavior.
     */
    public void recordDirectionChange(Direction newDirection) {
        if (lastTrackedDirection != null && newDirection != lastTrackedDirection) {
            double angle = directionAngleBetween(lastTrackedDirection, newDirection);
            cumulativeTurnDegrees += angle;
        }
        lastTrackedDirection = newDirection;
    }

    /**
     * Returns true if the head has accumulated 360+ degrees of turning,
     * indicating it is circling and stuck.
     */
    public boolean isCircling() {
        return cumulativeTurnDegrees >= 360.0;
    }

    public void resetTurnTracking() {
        this.cumulativeTurnDegrees = 0.0;
        this.lastTrackedDirection = null;
    }

    private static double directionAngleBetween(Direction from, Direction to) {
        if (from == to) return 0.0;
        // Cardinal directions are 90 degrees apart; opposite is 180
        if (from.getOpposite() == to) return 180.0;
        return 90.0;
    }

    public int incrementDeferCount() {
        return ++villageDeferCount;
    }

    public void resetDeferCount() {
        this.villageDeferCount = 0;
    }

    public int getVillageDeferCount() {
        return villageDeferCount;
    }

    public boolean isVillageConfirmed() {
        return runwayConfirmationState.isVillageConfirmed();
    }

    public boolean hasConfirmedRunway() {
        return runwayConfirmationState.hasConfirmedRunway();
    }

    public VillageEdgeFinder.StationZone getConfirmedRunway() {
        return runwayConfirmationState.getConfirmedRunway();
    }

    public void clearRunwayState() {
        runwayConfirmationState.clear();
        clearStationApproachPath();
    }

    public void setPlacedStation(SchematicPlacer.SchematicPlacementResult result) {
        runwayConfirmationState.setPlacedStation(result);
    }

    public boolean isStationPlaced() {
        return runwayConfirmationState.isStationPlaced();
    }

    public SchematicPlacer.SchematicPlacementResult getPlacedStation() {
        return runwayConfirmationState.getPlacedStation();
    }

    public void setSelectedStation(com.vodmordia.railwaysuntold.datapack.SelectedStation station) {
        runwayConfirmationState.setSelectedStation(station);
    }

    @javax.annotation.Nullable
    public com.vodmordia.railwaysuntold.datapack.SelectedStation getSelectedStation() {
        return runwayConfirmationState.getSelectedStation();
    }

    public void setExplorationTarget(BlockPos target) {
        explorationState.setExplorationTarget(target);
        resetTurnTracking();
    }

    @Nullable
    public BlockPos getExplorationTarget() {
        return explorationState.getExplorationTarget();
    }

    public boolean hasExplorationTarget() {
        return explorationState.hasExplorationTarget();
    }

    public void clearExplorationTarget() {
        explorationState.clearExplorationTarget();
    }

    public boolean isExploring() {
        return hasExplorationTarget() && !hasTargetVillage();
    }

    public boolean hasStationApproachPath() {
        return approachState.hasApproachPath();
    }

    public void clearStationApproachPath() {
        approachState.clearApproachPath();
    }

    @Override
    public PathSegment getCurrentSegment() {
        return approachState.getCurrentSegment();
    }

    @Override
    public void advanceToNextSegment() {
        approachState.advanceToNextSegment();
    }

    public void clear() {
        clearTarget();
        clearRunwayState();
        explorationState.clear();
    }

    public void restoreExtended(VillageTargetingSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        this.targetVillageId = snapshot.targetVillageId();
        this.targetVillageCenter = snapshot.targetVillageCenter();
        this.initialDistanceToVillage = snapshot.initialDistanceToVillage();
        this.hasReachedVillage = snapshot.hasReachedVillage();
        this.villageDeferCount = snapshot.villageDeferCount();
        runwayConfirmationState.restore(
                snapshot.villageConfirmed(),
                snapshot.runwayConfirmed(),
                snapshot.stationPlaced(),
                snapshot.runway()
        );
        this.approachWaypoint = snapshot.approachWaypoint();
        this.precomputedLayout = snapshot.toLayout();
        if (snapshot.explorationTarget() != null) {
            explorationState.setExplorationTarget(snapshot.explorationTarget());
        }
        this.lockedStationPlan = StationPlan.fromIntArray(snapshot.lockedStationPlanData());
    }
}
