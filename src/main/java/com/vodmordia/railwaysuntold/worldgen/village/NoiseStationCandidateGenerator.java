package com.vodmordia.railwaysuntold.worldgen.village;

import com.vodmordia.railwaysuntold.util.chunk.ChunkBounds;
import com.vodmordia.railwaysuntold.worldgen.placement.village.StationPositionEvaluator.CandidatePosition;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates candidate station positions along a runway using noise-based terrain height.
 */
public class NoiseStationCandidateGenerator {

    private static final int DEFAULT_SAMPLE_INTERVAL = 16;

    /**
     * Generate candidate positions every sampleInterval blocks along the runway.
     * Uses noise-based terrain height for each position.
     */
    public static List<CandidatePosition> generateCandidates(
            NoiseTerrainSampler sampler,
            ChunkBounds villageBounds,
            Direction approachDir,
            int sampleInterval) {

        List<CandidatePosition> candidates = new ArrayList<>();

        BlockPos start = NoiseStationPositionCalculator.getRunwayEndpoint(sampler, approachDir, villageBounds, true);
        BlockPos end = NoiseStationPositionCalculator.getRunwayEndpoint(sampler, approachDir, villageBounds, false);
        BlockPos center = NoiseStationPositionCalculator.getStationPosition(sampler, approachDir, villageBounds);

        int dx = end.getX() - start.getX();
        int dz = end.getZ() - start.getZ();
        int runwayLength = (int) Math.sqrt(dx * dx + dz * dz);

        if (runwayLength < 1) {
            candidates.add(new CandidatePosition(center, center, 0));
            return candidates;
        }

        double normDx = (double) dx / runwayLength;
        double normDz = (double) dz / runwayLength;

        for (int dist = 0; dist <= runwayLength; dist += sampleInterval) {
            int x = start.getX() + (int) (normDx * dist);
            int z = start.getZ() + (int) (normDz * dist);
            int y = sampler.getBaseHeight(x, z);

            BlockPos stationPos = new BlockPos(x, y, z);

            int distFromCenter = (int) Math.sqrt(
                    Math.pow(x - center.getX(), 2) +
                    Math.pow(z - center.getZ(), 2)
            );

            candidates.add(new CandidatePosition(stationPos, stationPos, distFromCenter));
        }

        boolean centerIncluded = candidates.stream()
                .anyMatch(c -> c.stationPosition().distManhattan(center) < sampleInterval / 2);
        if (!centerIncluded) {
            candidates.add(new CandidatePosition(center, center, 0));
        }

        return candidates;
    }

    /**
     * Generate candidates with the default 16-block sample interval.
     */
    public static List<CandidatePosition> generateCandidates(
            NoiseTerrainSampler sampler,
            ChunkBounds villageBounds,
            Direction approachDir) {
        return generateCandidates(sampler, villageBounds, approachDir, DEFAULT_SAMPLE_INTERVAL);
    }
}
