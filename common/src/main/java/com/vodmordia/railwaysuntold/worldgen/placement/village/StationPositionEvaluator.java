package com.vodmordia.railwaysuntold.worldgen.placement.village;

import net.minecraft.core.BlockPos;

/**
 * Plan-time helpers for validating candidate station positions.
 *
 */
public class StationPositionEvaluator {

    /**
     * Candidate station position sampled along a village runway. Used by
     * {@link StationCandidateGenerator} and {@code NoiseStationCandidateGenerator}.
     */
    public record CandidatePosition(
        BlockPos stationPosition,
        BlockPos entryPoint,
        int distanceFromCenter
    ) {}
}
