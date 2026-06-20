package com.vodmordia.railwaysuntold.worldgen.train;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;

/**
 * Calculates rotation for placing raw train schematics into the world.
 */
public final class SchematicRotator {

    /**
     * Calculates the rotation needed to transform from schematic orientation to track orientation.
     *
     * @param schematicAD  Assembly direction in the schematic
     * @param trackAD      Assembly direction on the actual track
     * @param flipForFront If true, add 180° to flip the train end-to-end
     * @return The rotation to apply
     */
    public static Rotation calculateRotation(Direction schematicAD, Direction trackAD, boolean flipForFront) {
        int cwSteps = getClockwiseSteps(schematicAD, trackAD);
        if (flipForFront) {
            cwSteps = (cwSteps + 2) % 4;
        }
        return fromClockwiseSteps(cwSteps);
    }

    /**
     * Rotates a block position by the given number of clockwise 90° steps around the Y axis.
     */
    public static BlockPos rotateBlockPos(BlockPos pos, int cwSteps) {
        int x = pos.getX(), z = pos.getZ();
        for (int s = 0; s < cwSteps; s++) {
            int newX = -z;
            z = x;
            x = newX;
        }
        return new BlockPos(x, pos.getY(), z);
    }

    /**
     * Rotates a block position by the given Rotation.
     */
    public static BlockPos rotateBlockPos(BlockPos pos, Rotation rotation) {
        return rotateBlockPos(pos, toClockwiseSteps(rotation));
    }

    public static int getClockwiseSteps(Direction from, Direction to) {
        int fromYaw = (int) from.toYRot();
        int toYaw = (int) to.toYRot();
        int diff = ((toYaw - fromYaw) % 360 + 360) % 360;
        return diff / 90;
    }

    private static Rotation fromClockwiseSteps(int steps) {
        return switch (steps % 4) {
            case 0 -> Rotation.NONE;
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    public static int toClockwiseSteps(Rotation rotation) {
        return switch (rotation) {
            case NONE -> 0;
            case CLOCKWISE_90 -> 1;
            case CLOCKWISE_180 -> 2;
            case COUNTERCLOCKWISE_90 -> 3;
        };
    }

    private SchematicRotator() {}
}
