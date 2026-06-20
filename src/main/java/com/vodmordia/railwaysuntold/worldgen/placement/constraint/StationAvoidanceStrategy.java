package com.vodmordia.railwaysuntold.worldgen.placement.constraint;

import com.vodmordia.railwaysuntold.worldgen.placement.AvoidanceCurveCalculator;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementConstants;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import com.vodmordia.railwaysuntold.worldgen.village.PlacedStationTracker;
import com.vodmordia.railwaysuntold.worldgen.village.VillageTargetingSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.Optional;

/**
 * Avoidance strategy for placed train stations.
 *
 * Prevents track from being placed through existing stations by routing around them.
 */
public final class StationAvoidanceStrategy implements AvoidanceStrategy {

    private static final int PRIORITY = 3;
    private static final int BUFFER = 5;

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    private Optional<ObstacleInfo> detect(AvoidanceContext context) {
        VillageTargetingSavedData villageData = VillageTargetingSavedData.get(context.level());
        PlacedStationTracker stationTracker = villageData.getStationTracker();

        if (stationTracker == null) {
            return Optional.empty();
        }

        int endX = context.trajectoryEndX();
        int endZ = context.trajectoryEndZ();

        PlacedStationTracker.StationBounds stationAhead =
                stationTracker.getStationIntersectingTrajectory(
                        context.currentPos().getX(), context.currentPos().getZ(),
                        endX, endZ, context.currentPos().getY());

        if (stationAhead == null) {
            return Optional.empty();
        }

        // Skip avoidance if the station ahead sits at (or very near) the head's locked
        // plan's intended entry. 
        com.vodmordia.railwaysuntold.worldgen.village.StationPlan plan =
                context.head().getVillageState().getLockedStationPlan();
        if (plan != null && isNearStation(plan.arrivalPos(), stationAhead)) {
            return Optional.empty();
        }

        BlockPos minCorner = stationAhead.minCorner;
        BlockPos maxCorner = stationAhead.maxCorner;
        BoundingBox obstacleBox = new BoundingBox(
                minCorner.getX() - BUFFER,
                minCorner.getY() - PlacementConstants.OBSTACLE_BOX_VERTICAL_BELOW,
                minCorner.getZ() - BUFFER,
                maxCorner.getX() + BUFFER,
                maxCorner.getY() + PlacementConstants.OBSTACLE_BOX_VERTICAL_ABOVE,
                maxCorner.getZ() + BUFFER
        );

        return Optional.of(new ObstacleInfo(obstacleBox, "placed station"));
    }

    @Override
    public Optional<PlacementDecision> apply(AvoidanceContext context) {
        Optional<ObstacleInfo> obstacle = detect(context);
        if (obstacle.isEmpty()) {
            return Optional.empty();
        }

        ObstacleInfo info = obstacle.get();
        return AvoidanceCurveCalculator.findCurveAroundObstacleBothDirections(
                context.level(), context.currentPos(), context.expandDir(),
                info.box(), context.scan(), info.description());
    }

    /** True if {@code pos} lies within the station's footprint expanded horizontally by a margin. */
    private static boolean isNearStation(BlockPos pos, PlacedStationTracker.StationBounds station) {
        int margin = 16;
        return pos.getX() >= station.minCorner.getX() - margin
                && pos.getX() <= station.maxCorner.getX() + margin
                && pos.getZ() >= station.minCorner.getZ() - margin
                && pos.getZ() <= station.maxCorner.getZ() + margin;
    }
}
