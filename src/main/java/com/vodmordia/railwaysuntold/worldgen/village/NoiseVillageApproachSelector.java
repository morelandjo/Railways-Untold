package com.vodmordia.railwaysuntold.worldgen.village;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.datapack.SelectedStation;
import com.vodmordia.railwaysuntold.util.spatial.DirectionUtil;
import com.vodmordia.railwaysuntold.util.track.SlopeValidator;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Evaluates approach directions to a village using noise terrain data.
 * Selects the best approach direction by scoring elevation change, terrain smoothness,
 * water crossings, and angle from the current heading.
 */
public class NoiseVillageApproachSelector {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int APPROACH_SAMPLE_DISTANCE = 128;
    private static final int SAMPLE_INTERVAL = 16;

    private static final double ELEVATION_WEIGHT = 3.0;
    private static final double SMOOTHNESS_WEIGHT = 2.0;
    private static final double WATER_PENALTY = 50.0;
    private static final double ANGLE_WEIGHT = 1.0;
    private static final double WAYPOINT_BEHIND_PENALTY = 200.0;
    private static final int ROUTE_SAMPLE_INTERVAL = 32;
    private static final double ROUTE_SLOPE_VIOLATION_PENALTY = 40.0;
    private static final int MAX_ROUTE_SLOPE_VIOLATIONS_FOR_REJECT = 5;
    private static final double ROUTE_SLOPE_REJECT_PENALTY = 500.0;

    public record ApproachCandidate(
            Direction approachDirection,
            int terrainHeightAtEdge,
            int elevationDelta,
            double terrainSmoothness,
            boolean crossesWater,
            double score
    ) {}

    /**
     * Selects the best approach direction to a village based on noise terrain analysis.
     * Only considers approach directions that produce a runway parallel to the track's
     * travel axis, so the track can run alongside the village edge without turning 90°.
     *
     * @param sampler           Noise terrain sampler
     * @param villageCenter     Center position of the target village
     * @param trackHeadPosition Current position of the track head
     * @param currentTrackY     Current Y level of the track
     * @return Best approach direction
     */
    public static Direction selectBestApproach(
            NoiseTerrainSampler sampler,
            BlockPos villageCenter,
            BlockPos trackHeadPosition,
            int currentTrackY) {

        Direction currentHeading = getApproximateHeading(trackHeadPosition, villageCenter);
        List<ApproachCandidate> candidates = new ArrayList<>(3);

        for (Direction dir : getApproachCandidateDirections(currentHeading)) {
            ApproachCandidate candidate = evaluateApproach(
                    sampler, villageCenter, dir, currentTrackY, currentHeading);
            candidates.add(candidate);
        }

        candidates.sort(Comparator.comparingDouble(ApproachCandidate::score));

        ApproachCandidate best = candidates.get(0);
        for (ApproachCandidate c : candidates) {
            LOGGER.debug("[APPROACH-SELECT]   {} - score={}, elevDelta={}, smooth={}, water={}",
                    c.approachDirection, String.format("%.1f", c.score), c.elevationDelta,
                    String.format("%.1f", c.terrainSmoothness), c.crossesWater);
        }

        return best.approachDirection;
    }

    /**
     * Selects the best approach direction using pre-computed village layout bounds.
     *
     * @param sampler           Noise terrain sampler
     * @param layout            Pre-computed village layout with actual bounds
     * @param trackHeadPosition Current position of the track head
     * @param currentTrackY     Current Y level of the track
     * @return Best approach direction
     */
    public static Direction selectBestApproach(
            NoiseTerrainSampler sampler,
            PredictedVillageLayout layout,
            BlockPos trackHeadPosition,
            int currentTrackY) {

        Direction currentHeading = getApproximateHeading(trackHeadPosition, layout.actualCenter());
        List<ApproachCandidate> candidates = new ArrayList<>(3);

        for (Direction dir : getApproachCandidateDirections(currentHeading)) {
            ApproachCandidate candidate = evaluateApproachFromEdge(
                    sampler, layout, dir, currentTrackY, currentHeading);

            // Compute actual waypoint and penalize if it's behind the head.
            // The coarse route can handle detours, but a waypoint behind the head
            // requires complex looping that often fails during fine approach steering.
            BlockPos waypoint = NoiseStationPositionCalculator.getApproachWaypoint(
                    sampler, dir, layout.chunkBounds(), trackHeadPosition);
            double reachabilityPenalty = calculateReachabilityPenalty(
                    trackHeadPosition, currentHeading, waypoint);
            double routeSlopePenalty = calculateRouteSlopePenalty(
                    sampler, trackHeadPosition, currentTrackY, waypoint);
            candidate = new ApproachCandidate(
                    candidate.approachDirection(), candidate.terrainHeightAtEdge(),
                    candidate.elevationDelta(), candidate.terrainSmoothness(),
                    candidate.crossesWater(), candidate.score() + reachabilityPenalty + routeSlopePenalty);

            candidates.add(candidate);
        }

        candidates.sort(Comparator.comparingDouble(ApproachCandidate::score));

        ApproachCandidate best = candidates.get(0);

        return best.approachDirection;
    }

    /**
     * Ranked list of viable approach sides with radial filtering applied.
     *
     *
     * @param station station schematic whose dimensions are used for the tangency probe
     */
    public static List<Direction> selectRankedApproaches(
            NoiseTerrainSampler sampler,
            PredictedVillageLayout layout,
            BlockPos trackHeadPosition,
            int currentTrackY,
            SelectedStation station) {

        Direction currentHeading = getApproximateHeading(trackHeadPosition, layout.actualCenter());
        List<ApproachCandidate> scored = new ArrayList<>(3);
        int kept = 0, dropped = 0;

        for (Direction dir : getApproachCandidateDirections(currentHeading)) {
            ApproachCandidate candidate = evaluateApproachFromEdge(
                    sampler, layout, dir, currentTrackY, currentHeading);
            BlockPos waypoint = NoiseStationPositionCalculator.getApproachWaypoint(
                    sampler, dir, layout.chunkBounds(), trackHeadPosition);
            double reachabilityPenalty = calculateReachabilityPenalty(
                    trackHeadPosition, currentHeading, waypoint);
            double routeSlopePenalty = calculateRouteSlopePenalty(
                    sampler, trackHeadPosition, currentTrackY, waypoint);
            candidate = new ApproachCandidate(
                    candidate.approachDirection(), candidate.terrainHeightAtEdge(),
                    candidate.elevationDelta(), candidate.terrainSmoothness(),
                    candidate.crossesWater(), candidate.score() + reachabilityPenalty + routeSlopePenalty);

            // Predict how the track will naturally arrive at this side's
            // arrival pose and drop the candidate if the resulting station runway
            // would be radial (pointing into the village body). Prediction is the
            // straight-line cardinal from head to the predicted arrival position.
            BlockPos predictedArrivalPos = computePredictedArrivalPos(layout.totalBounds(), dir);
            Direction predictedArrivalDir = DirectionUtil.getSegmentDirection(
                    trackHeadPosition, predictedArrivalPos);
            if (predictedArrivalDir == null) predictedArrivalDir = dir.getClockWise();
            if (StationFitValidator.isRadialArrival(predictedArrivalPos, predictedArrivalDir, layout, station)) {
                dropped++;
                continue;
            }
            scored.add(candidate);
            kept++;
        }

        scored.sort(Comparator.comparingDouble(ApproachCandidate::score));

        List<Direction> ranked = new ArrayList<>(scored.size());
        for (ApproachCandidate c : scored) ranked.add(c.approachDirection());

        if (ranked.isEmpty()) {
            LOGGER.warn("[APPROACH-SELECT] Village at {} - all {} candidate sides rejected (radial arrival predicted)",
                    layout.actualCenter(), dropped);
        } else {
        }
        return ranked;
    }

    /**
     * Midpoint of the chosen edge of the village bounds, offset outward by
     * {@code ARRIVAL_OFFSET_BLOCKS} blocks.
     */
    private static final int ARRIVAL_OFFSET_BLOCKS = 16;

    private static BlockPos computePredictedArrivalPos(BoundingBox bounds, Direction approachSide) {
        int midX = (bounds.minX() + bounds.maxX()) / 2;
        int midZ = (bounds.minZ() + bounds.maxZ()) / 2;
        int midY = (bounds.minY() + bounds.maxY()) / 2;
        int edgeX, edgeZ;
        switch (approachSide) {
            case NORTH -> { edgeX = midX; edgeZ = bounds.minZ(); }
            case SOUTH -> { edgeX = midX; edgeZ = bounds.maxZ(); }
            case EAST  -> { edgeX = bounds.maxX(); edgeZ = midZ; }
            case WEST  -> { edgeX = bounds.minX(); edgeZ = midZ; }
            default -> throw new IllegalArgumentException("Not horizontal: " + approachSide);
        }
        return new BlockPos(edgeX, midY, edgeZ).relative(approachSide, ARRIVAL_OFFSET_BLOCKS);
    }

    private static ApproachCandidate evaluateApproach(
            NoiseTerrainSampler sampler,
            BlockPos villageCenter,
            Direction approachDirection,
            int currentTrackY,
            Direction currentHeading) {

        int edgeX = villageCenter.getX() + approachDirection.getStepX() * APPROACH_SAMPLE_DISTANCE;
        int edgeZ = villageCenter.getZ() + approachDirection.getStepZ() * APPROACH_SAMPLE_DISTANCE;

        int sampleCount = APPROACH_SAMPLE_DISTANCE / SAMPLE_INTERVAL + 1;
        int[] heights = new int[sampleCount];
        boolean crossesWater = false;

        for (int i = 0; i < sampleCount; i++) {
            double t = (double) i / (sampleCount - 1);
            int x = (int) Math.round(villageCenter.getX() + (edgeX - villageCenter.getX()) * t);
            int z = (int) Math.round(villageCenter.getZ() + (edgeZ - villageCenter.getZ()) * t);
            heights[i] = sampler.getBaseHeight(x, z);

            if (!crossesWater && sampler.isLikelyOcean(x, z)) {
                crossesWater = true;
            }
        }

        int terrainHeightAtEdge = heights[sampleCount - 1];
        int elevationDelta = Math.abs(terrainHeightAtEdge - currentTrackY);

        double smoothness = calculateSmoothness(heights);
        double anglePenalty = calculateAnglePenalty(approachDirection, currentHeading);

        double score = elevationDelta * ELEVATION_WEIGHT
                + smoothness * SMOOTHNESS_WEIGHT
                + (crossesWater ? WATER_PENALTY : 0)
                + anglePenalty * ANGLE_WEIGHT;

        return new ApproachCandidate(approachDirection, terrainHeightAtEdge, elevationDelta,
                smoothness, crossesWater, score);
    }

    /**
     * Evaluates an approach direction by sampling outward from the actual village edge
     */
    private static ApproachCandidate evaluateApproachFromEdge(
            NoiseTerrainSampler sampler,
            PredictedVillageLayout layout,
            Direction approachDirection,
            int currentTrackY,
            Direction currentHeading) {

        // Calculate the edge position - the point where the village boundary meets this direction
        int edgeX, edgeZ;
        switch (approachDirection) {
            case NORTH -> { edgeX = (layout.totalBounds().minX() + layout.totalBounds().maxX()) / 2; edgeZ = layout.totalBounds().minZ(); }
            case SOUTH -> { edgeX = (layout.totalBounds().minX() + layout.totalBounds().maxX()) / 2; edgeZ = layout.totalBounds().maxZ(); }
            case EAST  -> { edgeX = layout.totalBounds().maxX(); edgeZ = (layout.totalBounds().minZ() + layout.totalBounds().maxZ()) / 2; }
            case WEST  -> { edgeX = layout.totalBounds().minX(); edgeZ = (layout.totalBounds().minZ() + layout.totalBounds().maxZ()) / 2; }
            default -> throw new IllegalArgumentException("Not horizontal: " + approachDirection);
        }

        // Sample outward from the edge
        int farX = edgeX + approachDirection.getStepX() * APPROACH_SAMPLE_DISTANCE;
        int farZ = edgeZ + approachDirection.getStepZ() * APPROACH_SAMPLE_DISTANCE;

        int sampleCount = APPROACH_SAMPLE_DISTANCE / SAMPLE_INTERVAL + 1;
        int[] heights = new int[sampleCount];
        boolean crossesWater = false;

        for (int i = 0; i < sampleCount; i++) {
            double t = (double) i / (sampleCount - 1);
            int x = (int) Math.round(edgeX + (farX - edgeX) * t);
            int z = (int) Math.round(edgeZ + (farZ - edgeZ) * t);
            heights[i] = sampler.getBaseHeight(x, z);

            if (!crossesWater && sampler.isLikelyOcean(x, z)) {
                crossesWater = true;
            }
        }

        int terrainHeightAtEdge = heights[0];
        int elevationDelta = Math.abs(terrainHeightAtEdge - currentTrackY);

        double smoothness = calculateSmoothness(heights);
        double anglePenalty = calculateAnglePenalty(approachDirection, currentHeading);

        double score = elevationDelta * ELEVATION_WEIGHT
                + smoothness * SMOOTHNESS_WEIGHT
                + (crossesWater ? WATER_PENALTY : 0)
                + anglePenalty * ANGLE_WEIGHT;

        return new ApproachCandidate(approachDirection, terrainHeightAtEdge, elevationDelta,
                smoothness, crossesWater, score);
    }

    /**
     * Returns all candidate approach directions except the far side (same as heading).
     */
    private static Direction[] getApproachCandidateDirections(Direction heading) {
        return Direction.Plane.HORIZONTAL.stream()
                .filter(d -> d != heading)
                .toArray(Direction[]::new);
    }

    private static double calculateSmoothness(int[] heights) {
        if (heights.length < 2) return 0;

        double sumSqDiff = 0;
        for (int i = 1; i < heights.length; i++) {
            int diff = heights[i] - heights[i - 1];
            sumSqDiff += diff * diff;
        }
        return sumSqDiff / (heights.length - 1);
    }

    /**
     * Penalizes approach directions where the resulting waypoint is behind the head.
     * A waypoint behind the head requires the coarse route to loop back, which often
     * fails during fine approach steering when the coarse route gets cleared.
     */
    private static double calculateReachabilityPenalty(BlockPos headPos, Direction heading, BlockPos waypoint) {
        int forwardDistance = switch (heading) {
            case EAST -> waypoint.getX() - headPos.getX();
            case WEST -> headPos.getX() - waypoint.getX();
            case SOUTH -> waypoint.getZ() - headPos.getZ();
            case NORTH -> headPos.getZ() - waypoint.getZ();
            default -> 0;
        };
        if (forwardDistance < 0) {
            return WAYPOINT_BEHIND_PENALTY;
        }
        return 0;
    }

    /**
     * Samples terrain along the corridor from head to approach waypoint and penalizes
     * approaches that route through terrain too steep for bezier track placement.
     */
    private static double calculateRouteSlopePenalty(
            NoiseTerrainSampler sampler,
            BlockPos headPosition,
            int currentTrackY,
            BlockPos waypoint) {

        int dx = waypoint.getX() - headPosition.getX();
        int dz = waypoint.getZ() - headPosition.getZ();
        double totalDist = Math.sqrt((double) dx * dx + (double) dz * dz);

        if (totalDist < ROUTE_SAMPLE_INTERVAL * 2) {
            return 0;
        }

        int sampleCount = Math.max(2, Math.min((int) (totalDist / ROUTE_SAMPLE_INTERVAL), 20));

        // Conservative max Y per sample interval - same formula as CoarseRoutePlanner
        double maxRatio = RailwaysUntoldConfig.getMaxSlopeRatio();
        int effectiveDistance = ROUTE_SAMPLE_INTERVAL - SlopeValidator.STRAIGHT_ENDPOINT_BLOCKS;
        int maxYPerSample = Math.max(1, (int) Math.floor(effectiveDistance * maxRatio * 0.6));

        int violations = 0;
        int prevY = currentTrackY;

        for (int i = 1; i <= sampleCount; i++) {
            double t = (double) i / sampleCount;
            int x = (int) Math.round(headPosition.getX() + dx * t);
            int z = (int) Math.round(headPosition.getZ() + dz * t);
            int terrainY = sampler.getBaseHeight(x, z);

            int yDiff = Math.abs(terrainY - prevY);
            if (yDiff > maxYPerSample) {
                violations++;
            }
            prevY = terrainY;
        }

        if (violations >= MAX_ROUTE_SLOPE_VIOLATIONS_FOR_REJECT) {
            return ROUTE_SLOPE_REJECT_PENALTY;
        }
        return violations * ROUTE_SLOPE_VIOLATION_PENALTY;
    }

    private static double calculateAnglePenalty(Direction approach, Direction heading) {
        // heading points from head toward village (e.g. EAST when head is west of village).
        // The station's runway runs along the approach edge - so approach=WEST produces a
        // N-S runway, approach=NORTH produces an E-W runway, etc.
        // We want the runway PARALLEL to the head's travel direction so the track can
        // naturally align with it.
        if (approach == heading.getOpposite()) return 150;  // near side = perpendicular runway = hard to connect
        if (approach == heading) return 180;                  // far side = worst (filtered out by candidates)
        return 0;                                             // flank side = parallel runway = best
    }

    private static Direction getApproximateHeading(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();

        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        } else {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }
}
