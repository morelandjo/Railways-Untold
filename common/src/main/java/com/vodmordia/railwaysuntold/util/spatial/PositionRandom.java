package com.vodmordia.railwaysuntold.util.spatial;

import net.minecraft.core.BlockPos;

import java.util.Random;

/**
 * Utility for creating position-based Random instances.
 *
 */
public class PositionRandom {

    /**
     * Creates a Random instance seeded by position, world seed, and an additional salt.
     *
     * @param pos The block position
     * @param worldSeed The world seed
     * @param salt Additional seed modifier for different random sequences at same position
     * @return A deterministically seeded Random instance
     */
    public static Random createWithSalt(BlockPos pos, long worldSeed, long salt) {
        long positionSeed = ((long)pos.getX() * 31L + pos.getY()) * 31L + pos.getZ();
        return new Random(worldSeed ^ positionSeed ^ salt);
    }
}
