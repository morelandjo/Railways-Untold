package com.vodmordia.railwaysuntold.worldgen.village;

import com.vodmordia.railwaysuntold.util.chunk.ChunkBounds;
import com.vodmordia.railwaysuntold.worldgen.placement.village.StationPositionEvaluator.CandidatePosition;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Characterizes {@link NoiseStationCandidateGenerator#generateCandidates} - the sampling of candidate
 * station positions along a village's runway. Pure given a terrain sampler (mocked flat here); also
 * drives the runway-endpoint geometry in {@link NoiseStationPositionCalculator} /
 * {@link com.vodmordia.railwaysuntold.worldgen.placement.village.RunwayPositionCalculator}.
 */
class NoiseStationCandidateGeneratorTest {

    private static final int GROUND_Y = 72;

    /** A flat terrain sampler: every column reads back the same ground height. */
    private static NoiseTerrainSampler flatSampler() {
        NoiseTerrainSampler s = mock(NoiseTerrainSampler.class);
        when(s.getBaseHeight(anyInt(), anyInt())).thenReturn(GROUND_Y);
        return s;
    }

    // A 4x4-chunk village at chunk origin: chunk X/Z in [0,3], so block coords span [0, 63].
    private static final ChunkBounds VILLAGE = new ChunkBounds(0, 3, 0, 3);

    @Test
    void samplesCandidatesAlongTheRunwayAtTheSampleInterval() {
        List<CandidatePosition> candidates = NoiseStationCandidateGenerator.generateCandidates(
                flatSampler(), VILLAGE, Direction.NORTH, 16);

        // A NORTH approach runs the runway E-W across the village (x from chunk 0 to chunk 3, i.e.
        // 8..56), sampled every 16 blocks -> at least the 0/16/32/48 stops plus the centre.
        assertTrue(candidates.size() >= 4,
                "expected several candidates along a 48-block runway, got " + candidates.size());

        // Every candidate sits on the flat ground the sampler reports.
        for (CandidatePosition c : candidates) {
            assertEquals(GROUND_Y, c.stationPosition().getY(),
                    "candidate not on sampled ground: " + c.stationPosition());
        }

        // The candidates span the runway: the X range covers most of the village width.
        int minX = candidates.stream().mapToInt(c -> c.stationPosition().getX()).min().orElseThrow();
        int maxX = candidates.stream().mapToInt(c -> c.stationPosition().getX()).max().orElseThrow();
        assertTrue(maxX - minX >= 32,
                "candidates do not span the runway: X range " + minX + ".." + maxX);
    }

    @Test
    void alwaysIncludesAStationCentreCandidate() {
        List<CandidatePosition> candidates = NoiseStationCandidateGenerator.generateCandidates(
                flatSampler(), VILLAGE, Direction.EAST, 16);

        BlockPos center = NoiseStationPositionCalculator.getStationPosition(
                flatSampler(), Direction.EAST, VILLAGE);
        boolean hasCenter = candidates.stream()
                .anyMatch(c -> c.stationPosition().getX() == center.getX()
                        && c.stationPosition().getZ() == center.getZ());
        assertTrue(hasCenter, "candidate list must include the station centre " + center);
    }

    @Test
    void aLargeSampleIntervalStillReturnsCandidates() {
        // An interval wider than the runway yields just the endpoints/centre rather than an empty list.
        List<CandidatePosition> candidates = NoiseStationCandidateGenerator.generateCandidates(
                flatSampler(), VILLAGE, Direction.SOUTH, 256);
        assertFalse(candidates.isEmpty(), "a wide sample interval must still produce a candidate");
    }

    @Test
    void theDefaultIntervalOverloadDelegatesToTheSixteenBlockInterval() {
        List<CandidatePosition> explicit = NoiseStationCandidateGenerator.generateCandidates(
                flatSampler(), VILLAGE, Direction.NORTH, 16);
        List<CandidatePosition> defaulted = NoiseStationCandidateGenerator.generateCandidates(
                flatSampler(), VILLAGE, Direction.NORTH);
        assertEquals(explicit.size(), defaulted.size(),
                "the no-interval overload should match the explicit 16-block interval");
    }
}
