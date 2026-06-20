package com.vodmordia.railwaysuntold.worldgen.village;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import javax.annotation.Nullable;

/**
 * Pre-computed target pose for a track head approaching a village station.
 *
 */
public record StationPlan(
        BlockPos arrivalPos,
        Direction arrivalDir
) {

    public static StationPlan of(BlockPos arrivalPos, Direction arrivalDir) {
        return new StationPlan(arrivalPos, arrivalDir);
    }

    /**
     * NBT serialization as int array for persistence.
     * Format: [arrivalX, arrivalY, arrivalZ, arrivalDirOrdinal]
     */
    public int[] toIntArray() {
        return new int[]{
                arrivalPos.getX(), arrivalPos.getY(), arrivalPos.getZ(),
                arrivalDir.get3DDataValue()
        };
    }

    /**
     * Reconstructs a StationPlan from an int array, or null if the data is missing
     * or in an older incompatible format.
     */
    @Nullable
    public static StationPlan fromIntArray(int[] data) {
        if (data == null || data.length < 4) {
            return null;
        }
        return new StationPlan(
                new BlockPos(data[0], data[1], data[2]),
                Direction.from3DDataValue(data[3])
        );
    }
}
