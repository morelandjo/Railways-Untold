package com.vodmordia.railwaysuntold.worldgen.terrain.harness;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A {@link TerrainAtlas} that reproduces a captured route-planner profile in-world: ground heights and
 * water taken from a {@code [REPLAY]} record's sampled data, placed at a world offset so a gametest can
 * drive a head across the exact terrain a production plan saw.
 *
 * Coordinate mapping: world (x,z) maps to record (x - offsetX, z - offsetZ), so record column (0,0)
 * sits at (offsetX, offsetZ).
 *
 * Reconstruction: the planner samples terrain only along its route, at a coarse interval, so the record
 * is a sparse 1-D profile. To make a continuous, traversable surface the atlas reconstructs the
 * along-route (axis) profile by linear interpolation between the nearest captured samples, clamping past
 * the ends so the ridge tails off flat rather than dropping to a cliff, and extrudes it sideways (a
 * straight plan samples only its own z). An exactly-captured column still wins, so any lateral detail
 * the planner did sample is preserved.
 *
 * Height convention: the captured height H is what the planner's {@code getBaseHeight} reported, one
 * above the top solid block (the Heightmap convention). TestTerrainChunkGenerator reports
 * {@code plannerHeight + 1}, so {@code plannerHeight = groundHeight = H - 1} makes the in-world sampler
 * read back exactly H at the captured columns.
 *
 * Water: the record captures only WHERE water was, not its surface Y, so a water column gets a thin
 * synthetic pool ({@code ground + WATER_DEPTH}) - enough for the planner's heightmap-gap water check to
 * fire. Ocean continentalness cannot be driven by this generator, so only river-style water reproduces.
 */
public final class ReplayTerrainAtlas implements TerrainAtlas {

    private static final int WATER_DEPTH = 2;

    private final Map<Long, Integer> heights;
    private final Set<Long> waterCoords;
    /** The along-route profile (local x -> captured height) used to interpolate between samples. */
    private final TreeMap<Integer, Integer> axisProfile = new TreeMap<>();
    /** Local x of the axis samples that were water, for water reconstruction. */
    private final TreeMap<Integer, Boolean> axisWater = new TreeMap<>();
    private final int defaultHeight;
    private final int axisZ;
    private final int offsetX;
    private final int offsetZ;

    public ReplayTerrainAtlas(Map<Long, Integer> heights, Set<Long> waterCoords,
                              int defaultHeight, int axisZ, int offsetX, int offsetZ) {
        this.heights = heights;
        this.waterCoords = waterCoords;
        this.defaultHeight = defaultHeight;
        this.axisZ = axisZ;
        this.offsetX = offsetX;
        this.offsetZ = offsetZ;
        for (Map.Entry<Long, Integer> e : heights.entrySet()) {
            if (unpackZ(e.getKey()) == axisZ) {
                int x = unpackX(e.getKey());
                axisProfile.put(x, e.getValue());
                axisWater.put(x, waterCoords.contains(packLocal(x, axisZ)));
            }
        }
    }

    /** Packs a record-local (x,z) the same way ReplayRecord.packKey does, so captured keys match. */
    private static long packLocal(int localX, int localZ) {
        return ((long) localX << 32) | (localZ & 0xFFFFFFFFL);
    }

    private static int unpackX(long key) {
        return (int) (key >> 32);
    }

    private static int unpackZ(long key) {
        return (int) key;
    }

    /** Captured height at the local column, else the linearly-interpolated (end-clamped) axis profile. */
    private int sampledHeight(int localX, int localZ) {
        Integer exact = heights.get(packLocal(localX, localZ));
        if (exact != null) {
            return exact;
        }
        if (axisProfile.isEmpty()) {
            return defaultHeight;
        }
        Integer atX = axisProfile.get(localX);
        if (atX != null) {
            return atX;
        }
        Map.Entry<Integer, Integer> lo = axisProfile.floorEntry(localX);
        Map.Entry<Integer, Integer> hi = axisProfile.ceilingEntry(localX);
        if (lo == null) {
            return hi.getValue();   // before the first sample: clamp flat
        }
        if (hi == null) {
            return lo.getValue();   // past the last sample: clamp flat
        }
        int span = hi.getKey() - lo.getKey();
        return lo.getValue() + (hi.getValue() - lo.getValue()) * (localX - lo.getKey()) / span;
    }

    @Override
    public int groundHeight(int x, int z) {
        return sampledHeight(x - offsetX, z - offsetZ) - 1;
    }

    @Override
    public boolean isWater(int x, int z) {
        int localX = x - offsetX;
        int localZ = z - offsetZ;
        if (waterCoords.contains(packLocal(localX, localZ))) {
            return true;
        }
        // Reconstruct the water band from the axis samples: water if the nearest captured axis sample
        // (within reach of the next one) was water.
        Map.Entry<Integer, Boolean> lo = axisWater.floorEntry(localX);
        return lo != null && lo.getValue();
    }

    @Override
    public int waterSurface(int x, int z) {
        return groundHeight(x, z) + WATER_DEPTH;
    }
}
