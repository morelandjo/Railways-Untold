package com.vodmordia.railwaysuntold.worldgen.head;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.spatial.DiagonalDirection;
import com.vodmordia.railwaysuntold.worldgen.head.state.*;
import com.vodmordia.railwaysuntold.worldgen.placement.support.BridgePillarSpacingCounter;
import com.vodmordia.railwaysuntold.worldgen.planner.TerrainPlanState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.List;
import java.util.UUID;

/**
 * Represents one expansion head for progressive track placement.
 */
public class TrackExpansionHead {

    private final int headNumber;
    private BlockPos position;
    private Direction direction;
    private final Direction initialDirection;
    private boolean complete;

    private final PauseState pauseState;
    private final CurveTrackingState curveState;

    private int torchPlacementOffset;

    private final BridgePillarSpacingCounter bridgePillarCounter;

    private final BranchingState branchingState;
    private final EventState eventState;

    private final VillageTargetingState villageState;
    private final TerrainPlanState terrainPlanState;

    private final SidingState sidingState;

    private DiagonalDirection diagonalHeading;

    /** Slope (rise/run) of the last placed segment, for smooth normal matching at junctions. */
    private double lastSegmentSlope = 0.0;

    /**
     * True once this head has successfully placed at least one segment.
     */
    private boolean hasPlacedAnySegments = false;

    public TrackExpansionHead(int headNumber, BlockPos position, Direction direction) {
        this(headNumber, position, direction, direction, UUID.randomUUID(), null, 0);
    }

    public TrackExpansionHead(int headNumber, BlockPos position, Direction direction, UUID headId, UUID parentHeadId, int branchDepth) {
        this(headNumber, position, direction, direction, headId, parentHeadId, branchDepth);
    }

    public TrackExpansionHead(int headNumber, BlockPos position, Direction direction, Direction initialDirection, UUID headId, UUID parentHeadId, int branchDepth) {
        this.headNumber = headNumber;
        this.position = position;
        this.direction = direction;
        this.initialDirection = initialDirection;
        this.complete = false;
        this.pauseState = new PauseState();
        this.curveState = new CurveTrackingState();
        this.bridgePillarCounter = new BridgePillarSpacingCounter();
        this.branchingState = new BranchingState(headId, parentHeadId, branchDepth);
        this.eventState = new EventState();
        this.villageState = new VillageTargetingState();
        this.terrainPlanState = new TerrainPlanState();
        this.sidingState = new SidingState();
    }

    public int getHeadNumber() {
        return headNumber;
    }

    public BlockPos getPosition() {
        return position;
    }

    public Direction getDirection() {
        return direction;
    }

    public double getLastSegmentSlope() {
        return lastSegmentSlope;
    }

    public void setLastSegmentSlope(double slope) {
        this.lastSegmentSlope = slope;
    }

    public boolean hasPlacedAnySegments() {
        return hasPlacedAnySegments;
    }

    public void markHasPlacedSegments() {
        this.hasPlacedAnySegments = true;
    }

    public Direction getInitialDirection() {
        return initialDirection;
    }

    public boolean isComplete() {
        return complete;
    }

    public boolean isPaused() {
        return pauseState.isPaused();
    }

    public long getPausedAtTime() {
        return pauseState.getPausedAtTime();
    }

    public int getBlocksSinceLastCurve() {
        return curveState.getBlocksSinceLastCurve();
    }

    public boolean wasLastPlacementEmergencyCurve() {
        return curveState.wasLastPlacementEmergencyCurve();
    }

    public void setPosition(BlockPos newPosition) {
        this.position = newPosition;
    }

    public void setDirection(Direction newDirection) {
        this.direction = newDirection;
    }

    public void markComplete() {
        this.complete = true;
    }

    public void pause() {
        pauseState.pause();
    }

    public void resume() {
        pauseState.resume();
    }

    public void setLastPlacementWasEmergencyCurve(boolean wasEmergency) {
        curveState.setLastPlacementWasEmergencyCurve(wasEmergency);
    }

    public int getTorchPlacementOffset() {
        return torchPlacementOffset;
    }

    public void addToTorchPlacementOffset(int segments) {
        this.torchPlacementOffset += segments;
    }

    public BridgePillarSpacingCounter getBridgePillarCounter() {
        return bridgePillarCounter;
    }

    public VillageTargetingState getVillageState() {
        return villageState;
    }

    public TerrainPlanState getTerrainPlanState() {
        return terrainPlanState;
    }

    public UUID getHeadId() {
        return branchingState.getHeadId();
    }

    public UUID getParentHeadId() {
        return branchingState.getParentHeadId();
    }

    public int getBranchDepth() {
        return branchingState.getBranchDepth();
    }

    public int getBlocksSinceLastBranch() {
        return branchingState.getBlocksSinceLastBranch();
    }

    public List<UUID> getChildBranchIds() {
        return branchingState.getChildBranchIds();
    }

    public boolean isOriginalHead() {
        return branchingState.isOriginalHead();
    }

    public void incrementBranchDistance(int blocks) {
        branchingState.incrementBranchDistance(blocks);
    }

    public void resetBranchCounter() {
        branchingState.resetBranchCounter();
    }

    public void addChildBranch(UUID childHeadId) {
        branchingState.addChildBranch(childHeadId);
    }

    public boolean isEligibleForBranch(RailwaysUntoldConfig config) {
        return branchingState.isEligibleForBranch(config);
    }

    public BlockPos getBranchOriginPos() {
        return branchingState.getBranchOriginPos();
    }

    public Direction getParentTrackDirection() {
        return branchingState.getParentTrackDirection();
    }

    public void setBranchOrigin(BlockPos pos, Direction direction) {
        branchingState.setBranchOrigin(pos, direction);
    }

    public BlockPos getLastBranchJunctionPos() {
        return branchingState.getLastBranchJunctionPos();
    }

    public Direction getLastBranchDirection() {
        return branchingState.getLastBranchDirection();
    }

    public void setLastBranchJunction(BlockPos junctionPos, Direction branchDir) {
        branchingState.setLastBranchJunction(junctionPos, branchDir);
    }

    public void clearLastBranchJunction() {
        branchingState.clearLastBranchJunction();
    }

    public int getBlocksSinceLastEvent() {
        return eventState.getBlocksSinceLastEvent();
    }

    public void incrementEventDistance(int blocks) {
        eventState.incrementEventDistance(blocks);
    }

    public void resetEventCounter() {
        eventState.resetEventCounter();
    }

    public void deferEventRetry(int minDistance, int cooldownBlocks) {
        eventState.deferRetry(minDistance, cooldownBlocks);
    }

    public void incrementBlocksSinceLastCurve(int blocks) {
        curveState.incrementBlocksSinceLastCurve(blocks);
    }

    public void resetBlocksSinceLastCurve() {
        curveState.resetBlocksSinceLastCurve();
    }

    public void incrementBlocksSinceLastLateral(int blocks) {
        curveState.incrementBlocksSinceLastLateral(blocks);
    }

    public void resetBlocksSinceLastLateral() {
        curveState.resetBlocksSinceLastLateral();
    }

    public void recordCurveTurnDirection(boolean turnedLeft) {
        curveState.recordCurveTurn(turnedLeft);
    }

    public CurveTrackingState.CurveDirection getPreviousCurveDirection() {
        return curveState.getPreviousCurveDirection();
    }

    public CurveTrackingState.CurveDirection getPriorCurveDirection() {
        return curveState.getPriorCurveDirection();
    }

    public boolean isDiagonal() {
        return diagonalHeading != null;
    }

    public DiagonalDirection getDiagonalHeading() {
        return diagonalHeading;
    }

    public void enterDiagonalMode(DiagonalDirection diagonal) {
        this.diagonalHeading = diagonal;
    }

    public void exitDiagonalMode() {
        this.diagonalHeading = null;
    }

    public SidingState getSidingState() {
        return sidingState;
    }

    public void incrementSidingDistance(int blocks) {
        sidingState.incrementStraight(blocks);
    }

    public void resetSidingState() {
        sidingState.reset();
    }

    public void onDirectionChange() {
        resetBlocksSinceLastCurve();
        getTerrainPlanState().clearApproachPath();
        sidingState.reset();
    }

    public void clearAllPaths() {
        terrainPlanState.clearApproachPath();
        villageState.clearStationApproachPath();
    }

    public void restoreFromSnapshot(HeadStateSnapshot snapshot) {
        this.position = snapshot.position();
        this.direction = snapshot.direction();
        this.complete = snapshot.complete();
        this.torchPlacementOffset = snapshot.torchPlacementOffset();
        // Restored heads have been ticking and almost certainly placed at least one segment.
        this.hasPlacedAnySegments = true;
        branchingState.restore(snapshot.blocksSinceLastBranch(), snapshot.childBranchIds());
        eventState.restore(snapshot.blocksSinceLastEvent());

        pauseState.restore(snapshot.paused(), snapshot.pausedAtTime());
        curveState.restore(
                snapshot.blocksSinceLastCurve(),
                snapshot.previousCurveDirection(),
                snapshot.priorCurveDirection(),
                snapshot.lastPlacementWasEmergencyCurve()
        );
        villageState.restoreExtended(snapshot.villageTargeting());

        if (snapshot.diagonalHeading() != null) {
            this.diagonalHeading = DiagonalDirection.valueOf(snapshot.diagonalHeading());
        } else {
            this.diagonalHeading = null;
        }
    }

}
