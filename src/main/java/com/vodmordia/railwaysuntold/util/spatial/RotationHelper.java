package com.vodmordia.railwaysuntold.util.spatial;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Utility class for handling rotation transformations for schematics and positions.
 */
public class RotationHelper {

    /**
     * Rotates a direction based on the given rotation.
     *
     * @param dir      The direction to rotate
     * @param rotation The rotation to apply
     * @return The rotated direction
     */
    public static Direction rotateDirection(Direction dir, Rotation rotation) {
        return switch (rotation) {
            case NONE -> dir;
            case CLOCKWISE_90 -> dir.getClockWise();
            case CLOCKWISE_180 -> dir.getOpposite();
            case COUNTERCLOCKWISE_90 -> dir.getCounterClockWise();
        };
    }

    /**
     * Transforms a local schematic position to world position based on rotation.
     *
     * @param local         The local position within the schematic
     * @param schematicSize The size of the schematic (width, height, length)
     * @param worldOrigin   The world position of the schematic origin
     * @param rotation      The rotation to apply
     * @return The world position after transformation
     */
    public static BlockPos transformPosition(BlockPos local, Vec3i schematicSize, BlockPos worldOrigin, Rotation rotation) {
        int x = local.getX();
        int y = local.getY();
        int z = local.getZ();

        int width = schematicSize.getX();
        int length = schematicSize.getZ();

        int transformedX, transformedZ;

        switch (rotation) {
            case CLOCKWISE_90:
                // Rotate 90° clockwise: (x, z) -> (length - 1 - z, x)
                transformedX = length - 1 - z;
                transformedZ = x;
                break;
            case CLOCKWISE_180:
                // Rotate 180°: (x, z) -> (width - 1 - x, length - 1 - z)
                transformedX = width - 1 - x;
                transformedZ = length - 1 - z;
                break;
            case COUNTERCLOCKWISE_90:
                // Rotate 90° counter-clockwise: (x, z) -> (z, width - 1 - x)
                transformedX = z;
                transformedZ = width - 1 - x;
                break;
            default:
                transformedX = x;
                transformedZ = z;
        }

        return worldOrigin.offset(transformedX, y, transformedZ);
    }

    /**
     * Gets the rotated size of a schematic.
     * For 90° rotations, width and length are swapped.
     *
     * @param size     The original size
     * @param rotation The rotation to apply
     * @return The size after rotation
     */
    public static Vec3i getRotatedSize(Vec3i size, Rotation rotation) {
        if (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90) {
            return new Vec3i(size.getZ(), size.getY(), size.getX());
        }
        return size;
    }

    /**
     * Rotates a block state by the given rotation.
     * Returns the state unchanged if rotation is NONE.
     *
     * @param level    The level accessor
     * @param pos      The block position
     * @param state    The block state to rotate
     * @param rotation The rotation to apply
     * @return The rotated block state
     */
    public static BlockState rotateBlockState(LevelAccessor level, BlockPos pos, BlockState state, Rotation rotation) {
        if (rotation == Rotation.NONE) {
            return state;
        }
        return state.rotate(level, pos, rotation);
    }
}
