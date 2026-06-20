package com.vodmordia.railwaysuntold.worldgen.terrain;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Noise-based terrain height and climate sampler.
 */
public class NoiseTerrainSampler {

    private static final int MAX_CACHE_SIZE = 16384;
    private static final double OCEAN_CONTINENTALNESS_THRESHOLD = -0.19;

    private final ChunkGenerator generator;
    private final RandomState randomState;
    private final LevelHeightAccessor heightAccessor;
    private final int seaLevel;

    private final Long2IntOpenHashMap heightCache = new Long2IntOpenHashMap();

    /**
     * Everything a single plan sampled, enough to reproduce it with no world: the queried
     * heights, plus the coordinates where {@code isLikelyOcean} / {@code hasWaterAtPosition}
     * returned true (false answers are the replay default, so only true ones are stored).
     */
    public record Capture(java.util.Map<Long, Integer> heights,
                          java.util.Set<Long> oceanCoords,
                          java.util.Set<Long> waterCoords) {}

    // Optional per-thread capture of every getBaseHeight / water query, used to emit the [REPLAY]
    // record at the plan site. Null when not capturing (a single null-check on the hot path). The key
    // packing here MUST match ReplayRecord.packKey so the captured data round-trips. Sorted containers
    // keep the emitted record stable and diffable.
    private static final ThreadLocal<java.util.Map<Long, Integer>> HEIGHT_CAPTURE = new ThreadLocal<>();
    private static final ThreadLocal<java.util.Set<Long>> OCEAN_CAPTURE = new ThreadLocal<>();
    private static final ThreadLocal<java.util.Set<Long>> WATER_CAPTURE = new ThreadLocal<>();

    /** Begins recording every height/water query on this thread for a [REPLAY] record. */
    public static void beginCapture() {
        HEIGHT_CAPTURE.set(new java.util.TreeMap<>());
        OCEAN_CAPTURE.set(new java.util.TreeSet<>());
        WATER_CAPTURE.set(new java.util.TreeSet<>());
    }

    /** Ends recording and returns what was sampled (empty containers if none was active). */
    public static Capture endCapture() {
        java.util.Map<Long, Integer> heights = HEIGHT_CAPTURE.get();
        java.util.Set<Long> oceans = OCEAN_CAPTURE.get();
        java.util.Set<Long> waters = WATER_CAPTURE.get();
        HEIGHT_CAPTURE.remove();
        OCEAN_CAPTURE.remove();
        WATER_CAPTURE.remove();
        return new Capture(
                heights == null ? java.util.Map.of() : heights,
                oceans == null ? java.util.Set.of() : oceans,
                waters == null ? java.util.Set.of() : waters);
    }

    private static int recordHeight(int x, int z, int height) {
        java.util.Map<Long, Integer> captured = HEIGHT_CAPTURE.get();
        if (captured != null) {
            captured.put(((long) x << 32) | (z & 0xFFFFFFFFL), height);
        }
        return height;
    }

    private static boolean recordOcean(int x, int z, boolean isOcean) {
        java.util.Set<Long> captured = OCEAN_CAPTURE.get();
        if (captured != null && isOcean) {
            captured.add(((long) x << 32) | (z & 0xFFFFFFFFL));
        }
        return isOcean;
    }

    private static boolean recordWater(int x, int z, boolean hasWater) {
        java.util.Set<Long> captured = WATER_CAPTURE.get();
        if (captured != null && hasWater) {
            captured.add(((long) x << 32) | (z & 0xFFFFFFFFL));
        }
        return hasWater;
    }

    private NoiseTerrainSampler(ChunkGenerator generator, RandomState randomState,
                                 LevelHeightAccessor heightAccessor, int seaLevel) {
        this.generator = generator;
        this.randomState = randomState;
        this.heightAccessor = heightAccessor;
        this.seaLevel = seaLevel;
        this.heightCache.defaultReturnValue(Integer.MIN_VALUE);
    }

    public static NoiseTerrainSampler forLevel(ServerLevel level) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();
        return new NoiseTerrainSampler(generator, randomState, level, level.getSeaLevel());
    }

    /** Gets base terrain height at (x, z) without requiring loaded chunks. */
    public int getBaseHeight(int x, int z) {
        long key = packCoords(x, z);
        int cached = heightCache.get(key);
        if (cached != Integer.MIN_VALUE) {
            return recordHeight(x, z, cached);
        }

        int height = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG,
                heightAccessor, randomState);

        // OCEAN_FLOOR_WG returns the seabed. Over ocean, floor to sea level
        // so the coarse route plans tracks at the water surface, not underwater.
        if (height < seaLevel && isLikelyOcean(x, z)) {
            height = seaLevel;
        }

        if (heightCache.size() >= MAX_CACHE_SIZE) {
            heightCache.clear();
        }
        heightCache.put(key, height);
        return recordHeight(x, z, height);
    }

    /**
     * Returns true if there is water at (x, z) by comparing WORLD_SURFACE_WG (stops at water)
     * with OCEAN_FLOOR_WG (passes through water). A gap means fluid blocks are present.
     */
    public boolean hasWaterAtPosition(int x, int z) {
        int surface = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG,
                heightAccessor, randomState);
        int floor = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG,
                heightAccessor, randomState);
        return recordWater(x, z, surface > floor);
    }

    /** Gets continentalness at (x, z). Below -0.19 is typically ocean. */
    public double getContinentalness(int x, int z) {
        DensityFunction.SinglePointContext ctx = new DensityFunction.SinglePointContext(
                x, seaLevel, z);
        return randomState.router().continents().compute(ctx);
    }

    /** Returns true if the position is likely ocean. */
    public boolean isLikelyOcean(int x, int z) {
        return recordOcean(x, z, getContinentalness(x, z) < OCEAN_CONTINENTALNESS_THRESHOLD);
    }

    /** Returns true if the position is likely a river (water present but not ocean). */
    public boolean isLikelyRiver(int x, int z) {
        return hasWaterAtPosition(x, z) && !isLikelyOcean(x, z);
    }

    /** Returns true if the position is likely water (ocean or river). */
    public boolean isLikelyWater(int x, int z) {
        return isLikelyOcean(x, z) || isLikelyRiver(x, z);
    }

    public int getSeaLevel() {
        return seaLevel;
    }

    private static long packCoords(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
