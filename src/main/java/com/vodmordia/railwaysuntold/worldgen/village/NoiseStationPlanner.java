package com.vodmordia.railwaysuntold.worldgen.village;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.datapack.SelectedStation;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Picks an arrival pose (position + cardinal direction) for the head to land at when
 * approaching a village.
 *
 */
public class NoiseStationPlanner {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * How far outside the village edge the head should aim to land. 
     */
    private static final int ARRIVAL_OFFSET_BLOCKS = 16;

    /** Safety buffer (blocks) between the station footprint and village pieces (matches StationCommitter). */
    private static final int STATION_COLLISION_MARGIN = 5;

    /**
     * Plans an arrival pose for the given village layout. Returns null if no approach
     * side yields a usable target (e.g. all sides are over ocean).
     *
     */
    @Nullable
    public static StationPlan planStation(
            NoiseTerrainSampler sampler,
            PredictedVillageLayout layout,
            BlockPos headPosition,
            Direction headDirection,
            int currentTrackY,
            SelectedStation selectedStation) {
        List<StationPlan> ranked = planStationWithAlternatives(
                sampler, layout, headPosition, headDirection, currentTrackY, selectedStation);
        return ranked.isEmpty() ? null : ranked.get(0);
    }

    /**
     * Returns a ranked list of viable arrival poses for the village, best first.
     *
     */
    public static List<StationPlan> planStationWithAlternatives(
            NoiseTerrainSampler sampler,
            PredictedVillageLayout layout,
            BlockPos headPosition,
            Direction headDirection,
            int currentTrackY,
            SelectedStation selectedStation) {

        List<Direction> rankedSides = NoiseVillageApproachSelector.selectRankedApproaches(
                sampler, layout, headPosition, currentTrackY, selectedStation);

        if (rankedSides.isEmpty()) {
            LOGGER.warn("[STATION-PLAN] No viable approach side for village at {}", layout.actualCenter());
            return List.of();
        }

        // Buildability filter: reject any approach side whose station footprint would overlap the
        // village's pieces, choosing a clear slot UP FRONT instead of discovering the collision at
        // commit (which abandons the village and orphans the approach track). With surveyed exact
        // pieceBounds this is ground truth; with a prediction it's still accurate enough to catch the
        // common case. Colliding sides are kept as last-resort fallbacks so we never return nothing.
        List<StationPlan> plans = new ArrayList<>(rankedSides.size());
        List<StationPlan> colliding = new ArrayList<>();
        for (Direction side : rankedSides) {
            BlockPos arrivalPos = computeArrivalPos(sampler, layout.totalBounds(), side);
            Direction arrivalDir = pickTangentDirection(side, headDirection, headPosition, arrivalPos);
            StationPlan plan = StationPlan.of(arrivalPos, arrivalDir);
            if (StationPlacementGeometry.wouldCollideWithVillagePieces(
                    arrivalPos, arrivalDir, selectedStation, layout.pieceBounds(), STATION_COLLISION_MARGIN)) {
                colliding.add(plan);
            } else {
                plans.add(plan);
            }
        }
        if (plans.isEmpty()) {
            return colliding;
        }

        return plans;
    }

    /**
     * Midpoint of the chosen edge of the village bounds, offset outward by
     * {@link #ARRIVAL_OFFSET_BLOCKS} so the head lands clear of village pieces.
     *
     */
    private static BlockPos computeArrivalPos(NoiseTerrainSampler sampler, BoundingBox bounds,
                                               Direction approachSide) {
        int midX = (bounds.minX() + bounds.maxX()) / 2;
        int midZ = (bounds.minZ() + bounds.maxZ()) / 2;

        int edgeX, edgeZ;
        switch (approachSide) {
            case NORTH -> { edgeX = midX; edgeZ = bounds.minZ(); }
            case SOUTH -> { edgeX = midX; edgeZ = bounds.maxZ(); }
            case EAST  -> { edgeX = bounds.maxX(); edgeZ = midZ; }
            case WEST  -> { edgeX = bounds.minX(); edgeZ = midZ; }
            default -> throw new IllegalArgumentException("Not horizontal: " + approachSide);
        }

        int arrivalX = edgeX + approachSide.getStepX() * ARRIVAL_OFFSET_BLOCKS;
        int arrivalZ = edgeZ + approachSide.getStepZ() * ARRIVAL_OFFSET_BLOCKS;
        int arrivalY = sampler.getBaseHeight(arrivalX, arrivalZ);
        return new BlockPos(arrivalX, arrivalY, arrivalZ);
    }

    /**
     * Picks one of the two tangent directions perpendicular to {@code approachSide}.
     * Prefers the tangent that is closer to the head's current heading or that points
     * away from the head (so the route doesn't need to double back).
     */
    private static Direction pickTangentDirection(Direction approachSide, Direction headDirection,
                                                  BlockPos headPosition, BlockPos arrivalPos) {
        Direction tangentA = approachSide.getClockWise();
        Direction tangentB = approachSide.getCounterClockWise();

        // If the head is heading along one of the tangents already, take it.
        if (headDirection == tangentA) return tangentA;
        if (headDirection == tangentB) return tangentB;

        // Otherwise, pick the tangent that points away from the head's current position
        // along the runway axis - that way the route can curve in and continue forward
        // through the station without doubling back.
        int dx = arrivalPos.getX() - headPosition.getX();
        int dz = arrivalPos.getZ() - headPosition.getZ();
        int projA = dx * tangentA.getStepX() + dz * tangentA.getStepZ();
        int projB = dx * tangentB.getStepX() + dz * tangentB.getStepZ();
        return projA >= projB ? tangentA : tangentB;
    }
}
