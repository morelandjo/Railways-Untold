package com.vodmordia.railwaysuntold.worldgen.planner;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Forward-distance helper along a cardinal axis, shared by the precision compiler and
 * the exploration target decider.
 */
public class PathPlanner {

    public static int calculateForwardDistance(BlockPos from, Direction dir, BlockPos to) {
        return switch (dir) {
            case NORTH -> from.getZ() - to.getZ();
            case SOUTH -> to.getZ() - from.getZ();
            case EAST -> to.getX() - from.getX();
            case WEST -> from.getX() - to.getX();
            default -> 0;
        };
    }
}
