package com.vodmordia.railwaysuntold.worldgen.terrain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Sampled terrain height profile along a path using noise-based terrain data.
 * Used for long-range route pre-planning without requiring loaded chunks.
 */
public class NoiseTerrainProfile {

    public record SamplePoint(int x, int z, int height, double continentalness, double erosion, boolean isWater) {}

    private final List<SamplePoint> samples;
    private final int interval;

    public NoiseTerrainProfile(List<SamplePoint> samples, int interval) {
        this.samples = Collections.unmodifiableList(new ArrayList<>(samples));
        this.interval = interval;
    }

    public List<SamplePoint> getSamples() {
        return samples;
    }

    public int getInterval() {
        return interval;
    }

    public int size() {
        return samples.size();
    }

    public boolean isEmpty() {
        return samples.isEmpty();
    }

    /** Checks if any sample point is likely ocean. */
    public boolean hasOceanCrossing() {
        for (SamplePoint s : samples) {
            if (s.continentalness < -0.19) return true;
        }
        return false;
    }

}
