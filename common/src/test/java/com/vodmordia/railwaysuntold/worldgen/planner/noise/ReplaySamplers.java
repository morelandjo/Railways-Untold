package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainSampler;

import java.util.Map;
import java.util.Set;
import java.util.function.IntBinaryOperator;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link NoiseTerrainSampler} stubs for the replay round-trip:
 *   {@link #recording} - runs real height/ocean/water functions while capturing every query the
 *       planner makes (this is what production would emit), and
 *   {@link #fromCapture} - a lookup sampler that returns the captured answers, reconstructing exactly
 *       what the planner saw with no world.
 * The capture mirrors production: only true ocean/water answers are stored, false is the replay default,
 * and {@code getBaseHeight} floors to sea level over ocean just as the real sampler does.
 */
final class ReplaySamplers {

    private ReplaySamplers() {}

    /** A predicate over a terrain coordinate, used to drive ocean/water in a recording sampler. */
    interface XZPredicate {
        boolean test(int x, int z);
    }

    /**
     * A sampler backed by {@code heightFn}/{@code oceanFn}/{@code waterFn} that records every queried
     * coordinate into the given containers, matching what {@link NoiseTerrainSampler#beginCapture} emits.
     */
    static NoiseTerrainSampler recording(IntBinaryOperator heightFn, XZPredicate oceanFn, XZPredicate waterFn,
                                         int seaLevel, Map<Long, Integer> heightsInto,
                                         Set<Long> oceanInto, Set<Long> waterInto) {
        NoiseTerrainSampler s = mock(NoiseTerrainSampler.class);
        when(s.getBaseHeight(anyInt(), anyInt())).thenAnswer(inv -> {
            int x = inv.getArgument(0);
            int z = inv.getArgument(1);
            int h = heightFn.applyAsInt(x, z);
            // Mirror production: OCEAN_FLOOR_WG seabed is floored to sea level over ocean.
            if (h < seaLevel && oceanFn.test(x, z)) {
                h = seaLevel;
            }
            heightsInto.put(ReplayRecord.packKey(x, z), h);
            return h;
        });
        when(s.isLikelyOcean(anyInt(), anyInt())).thenAnswer(inv -> {
            int x = inv.getArgument(0);
            int z = inv.getArgument(1);
            boolean ocean = oceanFn.test(x, z);
            if (ocean) {
                oceanInto.add(ReplayRecord.packKey(x, z));
            }
            return ocean;
        });
        when(s.hasWaterAtPosition(anyInt(), anyInt())).thenAnswer(inv -> {
            int x = inv.getArgument(0);
            int z = inv.getArgument(1);
            boolean water = waterFn.test(x, z);
            if (water) {
                waterInto.add(ReplayRecord.packKey(x, z));
            }
            return water;
        });
        when(s.isLikelyRiver(anyInt(), anyInt())).thenAnswer(inv -> {
            int x = inv.getArgument(0);
            int z = inv.getArgument(1);
            return s.hasWaterAtPosition(x, z) && !s.isLikelyOcean(x, z);
        });
        when(s.isLikelyWater(anyInt(), anyInt())).thenAnswer(inv -> {
            int x = inv.getArgument(0);
            int z = inv.getArgument(1);
            return s.isLikelyOcean(x, z) || s.hasWaterAtPosition(x, z);
        });
        when(s.getSeaLevel()).thenReturn(seaLevel);
        return s;
    }

    /**
     * A sampler that replays a capture: heights from the map (sea level for an unrecorded coord), and the
     * ocean/water predicates from the captured true-coordinate sets (false for anything not in them).
     */
    static NoiseTerrainSampler fromCapture(Map<Long, Integer> heights, Set<Long> oceanCoords,
                                           Set<Long> waterCoords, int seaLevel) {
        NoiseTerrainSampler s = mock(NoiseTerrainSampler.class);
        when(s.getBaseHeight(anyInt(), anyInt())).thenAnswer(inv -> {
            Integer h = heights.get(ReplayRecord.packKey(inv.getArgument(0), inv.getArgument(1)));
            return h != null ? h : seaLevel;
        });
        when(s.isLikelyOcean(anyInt(), anyInt())).thenAnswer(inv ->
                oceanCoords.contains(ReplayRecord.packKey(inv.getArgument(0), inv.getArgument(1))));
        when(s.hasWaterAtPosition(anyInt(), anyInt())).thenAnswer(inv ->
                waterCoords.contains(ReplayRecord.packKey(inv.getArgument(0), inv.getArgument(1))));
        when(s.isLikelyRiver(anyInt(), anyInt())).thenAnswer(inv -> {
            long key = ReplayRecord.packKey(inv.getArgument(0), inv.getArgument(1));
            return waterCoords.contains(key) && !oceanCoords.contains(key);
        });
        when(s.isLikelyWater(anyInt(), anyInt())).thenAnswer(inv -> {
            long key = ReplayRecord.packKey(inv.getArgument(0), inv.getArgument(1));
            return oceanCoords.contains(key) || waterCoords.contains(key);
        });
        when(s.getSeaLevel()).thenReturn(seaLevel);
        return s;
    }
}
