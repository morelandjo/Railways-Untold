package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Detects terrain dips ("camel humps") that the track should bridge over
 * rather than following down and back up.
 */
public class CamelHumpDetector {

    private static final int MIN_DIP_DEPTH = 12;
    private static final int MAX_BRIDGE_SPAN = 256;
    /** Minimum descent steepness (blocks drop per block span) to qualify as a bridgeable dip.
  */
    private static final double MIN_DIP_STEEPNESS = 0.35;

    public record DipRegion(int startIndex, int endIndex, int dipDepth, int spanLength) {}

    /**
     * Detects camel humps in a terrain profile.
     * A camel hump is a terrain dip where the ground drops significantly below
     * the entry height and recovers within a bridgeable span.
     *
     * @param profile      Noise terrain profile to analyze
     * @param currentTrackY Current track Y level
     * @return List of detected dip regions
     */
    public static List<DipRegion> detectCamelHumps(NoiseTerrainProfile profile, int currentTrackY) {
        if (profile.isEmpty() || profile.size() < 3) {
            return Collections.emptyList();
        }

        List<NoiseTerrainProfile.SamplePoint> samples = profile.getSamples();
        List<DipRegion> dips = new ArrayList<>();

        int i = 0;
        while (i < samples.size() - 2) {
            // Use the actual terrain height at the scan position, not the global station height.
            // currentTrackY would classify flat land far from the station as a dip (terrain at 64
            // vs station at 76 = 12-block "dip" that isn't real).
            int entryHeight = samples.get(i).height();

            // Scan forward looking for a significant drop
            int lowestHeight = entryHeight;
            int lowestIdx = i;
            boolean foundDip = false;

            for (int j = i + 1; j < samples.size(); j++) {
                int h = samples.get(j).height();

                if (h < lowestHeight) {
                    lowestHeight = h;
                    lowestIdx = j;
                }

                // Check if this is deep enough to be a camel hump
                if (entryHeight - lowestHeight >= MIN_DIP_DEPTH) {
                    foundDip = true;
                }

                // If terrain has recovered after finding a dip
                if (foundDip && h >= entryHeight - MIN_DIP_DEPTH / 2) {
                    // Find the actual dip start - the last index before terrain drops
                    // below the entry height. This avoids bridging over solid ground
                    // that happens to precede the dip.
                    int actualDipStart = i;
                    for (int k = i + 1; k <= lowestIdx; k++) {
                        if (samples.get(k).height() < entryHeight - 2) {
                            actualDipStart = Math.max(i, k - 1);
                            break;
                        }
                    }

                    int spanLength = (j - actualDipStart) * profile.getInterval();
                    int dipDepth = entryHeight - lowestHeight;
                    // Distance from actual dip start to the lowest point (the descent portion)
                    int descentSpan = (lowestIdx - actualDipStart) * profile.getInterval();
                    double descentSteepness = descentSpan > 0
                            ? (double) dipDepth / descentSpan : Double.MAX_VALUE;
                    if (spanLength <= MAX_BRIDGE_SPAN && descentSteepness >= MIN_DIP_STEEPNESS) {
                        dips.add(new DipRegion(actualDipStart, j, dipDepth, spanLength));
                    }
                    i = j;
                    foundDip = false;
                    break;
                }

                // If we've gone too far without recovery, stop looking for this dip
                int currentSpan = (j - i) * profile.getInterval();
                if (currentSpan > MAX_BRIDGE_SPAN) {
                    i = lowestIdx;
                    foundDip = false;
                    break;
                }
            }

            if (foundDip) {
                // Dip never recovered - advance past the lowest point to avoid infinite loop
                i = lowestIdx + 1;
            } else {
                i++;
            }
        }

        return dips;
    }

    /**
     * Detects terrain peaks that might warrant a tunnel.
     * A peak is where terrain rises significantly above the local entry height
     * and then drops back down.
     *
     * @param profile      Noise terrain profile
     * @param currentTrackY Current track Y (used only for logging)
     * @param maxPeakRise  Minimum rise above the local entry height to qualify
     * @return List of peak regions as DipRegion (reusing the structure with inverted semantics)
     */
    public static List<DipRegion> detectPeaks(NoiseTerrainProfile profile, int currentTrackY, int maxPeakRise) {
        if (profile.isEmpty() || profile.size() < 3) {
            return Collections.emptyList();
        }

        List<NoiseTerrainProfile.SamplePoint> samples = profile.getSamples();
        List<DipRegion> peaks = new ArrayList<>();

        int i = 0;
        while (i < samples.size() - 2) {
            // Use the actual terrain height at the scan position, not the global track Y.
            // Measuring rise against currentTrackY mis-classified terrain on a climb (where
            // the track is already well above its start) - a whole elevated stretch read as
            // one never-recovering peak. This mirrors detectCamelHumps' local entryHeight.
            int entryHeight = samples.get(i).height();

            int highestHeight = entryHeight;
            int highestIdx = i;
            boolean foundPeak = false;

            for (int j = i + 1; j < samples.size(); j++) {
                int h = samples.get(j).height();

                if (h > highestHeight) {
                    highestHeight = h;
                    highestIdx = j;
                }

                if (highestHeight - entryHeight >= maxPeakRise) {
                    foundPeak = true;
                }

                // Terrain drops back to near the entry height
                if (foundPeak && h <= entryHeight + maxPeakRise / 2) {
                    // Clamp the peak region to where terrain actually rises above the entry.
                    int tunnelThreshold = entryHeight + 4; // tunnel if terrain > 4 blocks above entry
                    int actualStart = i;
                    for (int k = i; k <= highestIdx; k++) {
                        if (samples.get(k).height() > tunnelThreshold) {
                            actualStart = Math.max(i, k - 1);
                            break;
                        }
                    }
                    int actualEnd = j;
                    for (int k = j; k >= highestIdx; k--) {
                        if (samples.get(k).height() > tunnelThreshold) {
                            actualEnd = Math.min(j, k + 1);
                            break;
                        }
                    }

                    int spanLength = (actualEnd - actualStart) * profile.getInterval();
                    peaks.add(new DipRegion(actualStart, actualEnd, highestHeight - entryHeight, spanLength));
                    i = j;
                    foundPeak = false;
                    break;
                }
            }

            if (foundPeak) {
                // Peak never recovered - advance past the highest point to avoid infinite loop
                i = highestIdx + 1;
            } else {
                i++;
            }
        }

        return peaks;
    }
}
