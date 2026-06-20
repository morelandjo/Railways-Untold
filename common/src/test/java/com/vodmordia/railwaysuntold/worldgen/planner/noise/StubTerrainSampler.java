package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainSampler;

import java.util.function.IntBinaryOperator;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Deterministic {@link NoiseTerrainSampler} stub for golden tests. The real
 * sampler has a private constructor and reads live world noise, so it is mocked;
 * height is a closed-form function of (x,z) and water predicates default to false
 * unless a scenario overrides them.
 */
final class StubTerrainSampler {

    static final int SEA_LEVEL = 63;

    private StubTerrainSampler() {}

    /** Flat terrain at a constant height everywhere. */
    static NoiseTerrainSampler flat(int height) {
        return withHeights((x, z) -> height, SEA_LEVEL);
    }

    /** Terrain whose height is a closed-form function of (x, z). */
    static NoiseTerrainSampler withHeights(IntBinaryOperator heightFn, int seaLevel) {
        NoiseTerrainSampler s = mock(NoiseTerrainSampler.class);
        when(s.getBaseHeight(anyInt(), anyInt()))
                .thenAnswer(inv -> heightFn.applyAsInt(inv.getArgument(0), inv.getArgument(1)));
        when(s.getSeaLevel()).thenReturn(seaLevel);
        return s;
    }

    /** A boolean predicate over a terrain column, used to drive {@code isLikelyWater}. */
    @FunctionalInterface
    interface XZPredicate {
        boolean test(int x, int z);
    }

    /**
     * Terrain with heights plus an explicit water predicate: a column reports {@code isLikelyWater}
     * exactly when {@code water} says so (everything else defaults to false). The water-bridge pass
     * reads only {@code isLikelyWater} + {@code getSeaLevel}, so that is all this stubs.
     */
    static NoiseTerrainSampler withWater(IntBinaryOperator heightFn, int seaLevel, XZPredicate water) {
        NoiseTerrainSampler s = withHeights(heightFn, seaLevel);
        when(s.isLikelyWater(anyInt(), anyInt()))
                .thenAnswer(inv -> water.test(inv.getArgument(0), inv.getArgument(1)));
        return s;
    }
}
