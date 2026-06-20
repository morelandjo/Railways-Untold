package com.vodmordia.railwaysuntold.worldgen.terrain;

import com.vodmordia.railwaysuntold.util.block.BlockTypeUtil;
import com.vodmordia.railwaysuntold.util.block.ChunkSafeBlockAccess;
import com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil;
import com.vodmordia.railwaysuntold.util.chunk.ChunkVerificationUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;

/**
 * Scans terrain ahead of current track position to determine elevation changes.
 */
public class TerrainScanner {

    /**
     * Result of scanning terrain ahead.
     */
    public static class TerrainScan {
        private final boolean canSeeFullRange;
        private final int[] heightProfile;

        public TerrainScan(boolean canSeeFullRange, int[] heightProfile) {
            this.canSeeFullRange = canSeeFullRange;
            this.heightProfile = heightProfile;
        }

        public boolean canSeeFullRange() {
            return canSeeFullRange;
        }

        public int[] getHeightProfile() {
            return heightProfile;
        }
    }

    /**
     * Scans terrain ahead from the given position.
     *
     * @param level     Server level
     * @param start     Starting position
     * @param direction Direction to scan
     * @param distance  How far to scan (typically 30 blocks)
     * @return TerrainScan result, or null if chunks aren't loaded
     */
    /**
     * Scans terrain ahead from the given position with optional config for structure detection.
     *
     * @param level     Server level
     * @param start     Starting position
     * @param direction Direction to scan
     * @param distance  How far to scan (typically 30 blocks)
     * @param config    Configuration for structure avoidance (null to skip structure checks)
     * @return TerrainScan result, or null if chunks aren't loaded
     */
    @Nullable
    public static TerrainScan scanAhead(ServerLevel level, BlockPos start, Direction direction, int distance,
                                        com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig config) {
        if (direction == null) {
            return null;
        }

        if (!areChunksLoaded(level, start, direction, distance)) {
            return null;
        }

        int[] heightProfile = new int[distance];

        boolean hadMissingChunks = false;
        for (int i = 0; i < distance; i++) {
            BlockPos samplePos = start.relative(direction, i + 1);
            int terrainHeight = getGroundLevelAt(level, samplePos, config);
            heightProfile[i] = terrainHeight;

            if (terrainHeight == TerrainConstants.DEFAULT_FALLBACK_HEIGHT) {
                int chunkX = ChunkCoordinateUtil.getChunkX(samplePos.getX());
                int chunkZ = ChunkCoordinateUtil.getChunkZ(samplePos.getZ());
                if (ChunkCoordinateUtil.getLoadedChunk(level, chunkX, chunkZ) == null) {
                    hadMissingChunks = true;
                }
            }
        }

        correctWaterSpikes(heightProfile);
        smoothWaterHumps(heightProfile);
        smoothTerrainDips(heightProfile);
        smoothSlopeReversals(heightProfile);
        smoothLongSlopes(heightProfile);

        return new TerrainScan(!hadMissingChunks, heightProfile);
    }

    /**
     * Corrects isolated upward spikes in the height profile.
     * When a track path clips a body of water, some columns return
     * waterSurface + BRIDGE_WATER_DISTANCE (+5) while adjacent dry columns return
     * ground + TRACK_HEIGHT_OFFSET (+1), creating a ~4-block hump.
     *
     */
    static int correctWaterSpikes(int[] heightProfile) {
        if (heightProfile.length < 3) return 0;

        int corrections = 0;
        int[] corrected = heightProfile.clone();
        int lookback = 3; // check up to 3 neighbors on each side

        for (int i = 0; i < heightProfile.length; i++) {
            int current = heightProfile[i];

            // Find max height among neighbors behind (up to lookback positions)
            int maxBehind = Integer.MIN_VALUE;
            int behindCount = 0;
            for (int j = Math.max(0, i - lookback); j < i; j++) {
                maxBehind = Math.max(maxBehind, heightProfile[j]);
                behindCount++;
            }

            // Find max height among neighbors ahead (up to lookback positions)
            int maxAhead = Integer.MIN_VALUE;
            int aheadCount = 0;
            for (int j = i + 1; j <= Math.min(heightProfile.length - 1, i + lookback); j++) {
                maxAhead = Math.max(maxAhead, heightProfile[j]);
                aheadCount++;
            }

            // Skip edge positions that don't have neighbors on both sides
            if (behindCount == 0 || aheadCount == 0) continue;

            int neighborMax = Math.max(maxBehind, maxAhead);
            int spike = current - neighborMax;

            if (spike >= 3) {
                corrected[i] = neighborMax + 1;
                corrections++;
            }
        }

        System.arraycopy(corrected, 0, heightProfile, 0, heightProfile.length);
        return corrections;
    }

    /**
     * Smooths short upward humps in the height profile.
     * When the track path clips a small water body (pond, 2-8 blocks wide),
     * those columns return waterSurface + BRIDGE_WATER_DISTANCE (+5) while
     * adjacent dry columns return ground + TRACK_HEIGHT_OFFSET (+1), creating
     * a ~4-block hump. correctWaterSpikes only catches isolated 1-block spikes;
     * consecutive water columns protect each other from that correction.
     *
     */
    static int smoothWaterHumps(int[] heightProfile) {
        if (heightProfile.length < 3) return 0;

        int corrections = 0;
        // Minimum rise from entry height to count as a hump
        int HUMP_THRESHOLD = 3;
        // Maximum width of a hump to smooth - wider humps are real water crossings
        int MAX_HUMP_WIDTH = 10;
        // How close the exit must be to entry height to count as "recovered"
        int RECOVERY_TOLERANCE = 2;

        int i = 0;
        while (i < heightProfile.length - 2) {
            int entryHeight = heightProfile[i];

            // Check if next position rises enough to start a hump
            if (heightProfile[i + 1] > entryHeight + HUMP_THRESHOLD) {
                // Look ahead for recovery - terrain returning to near entry height
                int humpEnd = -1;
                for (int j = i + 2; j < Math.min(i + MAX_HUMP_WIDTH, heightProfile.length); j++) {
                    if (heightProfile[j] <= entryHeight + RECOVERY_TOLERANCE) {
                        humpEnd = j;
                        break;
                    }
                }

                if (humpEnd > 0) {
                    // Hump recovers - interpolate linearly from entry to exit
                    int exitHeight = heightProfile[humpEnd];
                    for (int k = i + 1; k < humpEnd; k++) {
                        double t = (double) (k - i) / (humpEnd - i);
                        int interpolated = (int) Math.round(entryHeight + t * (exitHeight - entryHeight));
                        // Only lower - never push above the actual value
                        if (interpolated < heightProfile[k]) {
                            heightProfile[k] = interpolated;
                            corrections++;
                        }
                    }
                    i = humpEnd;
                    continue;
                }
            }
            i++;
        }
        return corrections;
    }

    /**
     * Smooths short downward dips in the height profile.
     * When the terrain drops and then recovers within a short span, this creates
     * humps or stair-step patterns if the track tries to follow the dip down and
     * back up.
     */
    static int smoothTerrainDips(int[] heightProfile) {
        if (heightProfile.length < 3) return 0;

        int corrections = 0;
        // Minimum drop from entry height to count as a dip (not just noise)
        int DIP_THRESHOLD = 2;
        // Maximum width of a dip to smooth over - wider dips are real terrain
        int MAX_DIP_WIDTH = 16;
        // How close the exit must be to entry height to count as "recovered"
        int RECOVERY_TOLERANCE = 1;

        int i = 0;
        while (i < heightProfile.length - 2) {
            int entryHeight = heightProfile[i];

            // Check if next position drops enough to start a dip
            if (heightProfile[i + 1] < entryHeight - DIP_THRESHOLD) {
                // Look ahead for recovery - terrain returning to near entry height
                int dipEnd = -1;
                for (int j = i + 2; j < Math.min(i + MAX_DIP_WIDTH, heightProfile.length); j++) {
                    if (heightProfile[j] >= entryHeight - RECOVERY_TOLERANCE) {
                        dipEnd = j;
                        break;
                    }
                }

                if (dipEnd > 0) {
                    // Dip recovers - interpolate linearly from entry to exit.
                    // This gives a smooth bridge-like trajectory that still trends
                    // toward the exit height (which may be slightly different from entry).
                    int exitHeight = heightProfile[dipEnd];
                    for (int k = i + 1; k < dipEnd; k++) {
                        double t = (double) (k - i) / (dipEnd - i);
                        int interpolated = (int) Math.round(entryHeight + t * (exitHeight - entryHeight));
                        // Only raise - never push below the actual terrain
                        if (interpolated > heightProfile[k]) {
                            heightProfile[k] = interpolated;
                            corrections++;
                        }
                    }
                    i = dipEnd;
                    continue;
                }
            }
            i++;
        }
        return corrections;
    }

    /**
     * Smooths small reversals on consistent slopes.
     * When terrain is on a clear descent or ascent, minor bumps (2-3 blocks
     * against the trend) create false inflection points that fragment the track
     * into many short bezier segments.
     */
    static int smoothSlopeReversals(int[] heightProfile) {
        if (heightProfile.length < 7) return 0;

        int corrections = 0;
        // Window size for assessing the local slope trend (must be odd)
        int TREND_WINDOW = 11;
        int HALF_WINDOW = TREND_WINDOW / 2; // 5
        // Minimum average height change across the window to qualify as a consistent slope
        int MIN_TREND = 3;
        // Never adjust a point by more than this many blocks
        int MAX_CORRECTION = 3;
        // Number of samples at each end of the window used to compute average heights
        int EDGE_SAMPLES = 3;

        // Work on a copy so corrections don't cascade within a single pass
        int[] smoothed = heightProfile.clone();

        for (int i = HALF_WINDOW; i < heightProfile.length - HALF_WINDOW; i++) {
            int windowStart = i - HALF_WINDOW;
            int windowEnd = i + HALF_WINDOW;

            // Compute average height of first and last EDGE_SAMPLES in the window.
            // Using averages instead of single endpoints makes trend detection
            // robust against noise at the window edges.
            double avgFirst = 0;
            double avgLast = 0;
            for (int k = 0; k < EDGE_SAMPLES; k++) {
                avgFirst += heightProfile[windowStart + k];
                avgLast += heightProfile[windowEnd - k];
            }
            avgFirst /= EDGE_SAMPLES;
            avgLast /= EDGE_SAMPLES;

            double totalChange = avgLast - avgFirst;

            // Not a clear slope - leave this point alone
            if (Math.abs(totalChange) < MIN_TREND) {
                continue;
            }

            // Expected height at position i via linear interpolation across the window
            double t = (double) (i - windowStart) / (windowEnd - windowStart);
            int expectedHeight = (int) Math.round(avgFirst + t * totalChange);

            int actual = heightProfile[i];
            int deviation = actual - expectedHeight;

            // Only correct if the point goes against the trend direction:
            // on a descent (totalChange < 0) a positive deviation is an upward bump;
            // on an ascent (totalChange > 0) a negative deviation is a downward dip.
            boolean isAgainstTrend = (totalChange < 0 && deviation > 0)
                    || (totalChange > 0 && deviation < 0);

            if (isAgainstTrend && Math.abs(deviation) <= MAX_CORRECTION) {
                smoothed[i] = expectedHeight;
                corrections++;
            }
        }

        System.arraycopy(smoothed, 0, heightProfile, 0, heightProfile.length);
        return corrections;
    }

    /**
     * Smooths high-frequency undulations on consistent long slopes.
     * When terrain descends (or ascends) at a varying rate, the per-column
     * height variations aren't caught by the other smoothing methods because
     * every point is technically "with the trend". 
     */
    static int smoothLongSlopes(int[] heightProfile) {
        if (heightProfile.length < 20) return 0;

        int corrections = 0;
        int WINDOW = 24;
        int HALF = WINDOW / 2;
        // Maximum deviation from trend line after clamping (blocks)
        int MAX_CLAMP = 1;
        // Deviations larger than this are genuine features - leave alone
        int FEATURE_THRESHOLD = 5;
        // Minimum R² to consider the window a consistent slope
        double MIN_R_SQUARED = 0.85;
        // Minimum height change across the window to qualify as a real slope
        int MIN_TREND_PER_WINDOW = 4;

        int[] smoothed = heightProfile.clone();

        for (int center = HALF; center < heightProfile.length - HALF; center++) {
            int wStart = center - HALF;
            int wEnd = center + HALF;

            // Linear regression over the window
            double sumX = 0, sumY = 0, sumXX = 0, sumXY = 0, sumYY = 0;
            int n = wEnd - wStart + 1;
            for (int i = wStart; i <= wEnd; i++) {
                double x = i - wStart;
                double y = heightProfile[i];
                sumX += x;
                sumY += y;
                sumXX += x * x;
                sumXY += x * y;
                sumYY += y * y;
            }
            double meanX = sumX / n;
            double meanY = sumY / n;
            double sxx = sumXX - n * meanX * meanX;
            double sxy = sumXY - n * meanX * meanY;
            double syy = sumYY - n * meanY * meanY;

            if (sxx == 0 || syy == 0) continue; // flat or constant - no action

            double slope = sxy / sxx;
            double intercept = meanY - slope * meanX;
            double rSquared = (sxy * sxy) / (sxx * syy);

            double totalChange = Math.abs(slope * (n - 1));
            if (totalChange < MIN_TREND_PER_WINDOW) continue;
            if (rSquared < MIN_R_SQUARED) continue;

            // Compute expected value at center
            double x = center - wStart;
            int expected = (int) Math.round(intercept + slope * x);
            int actual = heightProfile[center];
            int deviation = actual - expected;

            if (Math.abs(deviation) > FEATURE_THRESHOLD) continue; // genuine feature
            if (Math.abs(deviation) <= MAX_CLAMP) continue; // already close enough

            // Clamp toward the trend line
            smoothed[center] = expected + (int) (Math.signum(deviation) * MAX_CLAMP);
            corrections++;
        }

        System.arraycopy(smoothed, 0, heightProfile, 0, heightProfile.length);
        return corrections;
    }

    private static boolean areChunksLoaded(ServerLevel level, BlockPos start, Direction direction, int distance) {
        return ChunkVerificationUtil.areRangeChunksLoaded(level, start, direction, distance);
    }

    public static int getGroundLevelAt(ServerLevel level, BlockPos pos) {
        return TerrainHeightUtil.getGroundLevel(level, pos);
    }

    public static int getGroundLevelAt(ServerLevel level, BlockPos pos, com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig config) {
        return TerrainHeightUtil.getGroundLevel(level, pos, config);
    }

    /**
     * Validates that a position has valid solid ground for track placement.
     *
     * @param level    The server level
     * @param trackPos The position where track would be placed
     * @return true if the ground is valid for track placement
     */
    public static boolean hasValidGroundForTrack(ServerLevel level, BlockPos trackPos) {
        BlockPos girderPos = trackPos.below();

        var girderState = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, girderPos);
        if (girderState == null) {
            return false;
        }

        BlockPos groundPos = girderPos.below();
        var groundState = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, groundPos);
        if (groundState == null) {
            return false;
        }

        // Trees/plants will be cleared - just need solid ground below
        if (BlockTypeUtil.isTreeOrWood(girderState) || BlockTypeUtil.isPlant(girderState)) {
            return !groundState.isAir() && !BlockTypeUtil.isTreeOrWood(groundState) && !BlockTypeUtil.isPlant(groundState);
        }

        // Air/water: girder will be placed, need solid ground or water below
        if (girderState.isAir() || BlockTypeUtil.isWater(girderState)) {
            if (groundState.isAir() || BlockTypeUtil.isTreeOrWood(groundState) || BlockTypeUtil.isPlant(groundState)) {
                return BlockTypeUtil.isWater(groundState);
            }
            return true;
        }

        return true;
    }

}
