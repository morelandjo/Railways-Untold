package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.util.track.SlopeValidator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Leaf numeric helpers for the precision compiler: forward and lateral distance along a
 * cardinal axis, and the slope-limit clamp.
 */
final class RouteMath {

    private RouteMath() {}

    /**
     * Clamps endY so the segment's slope respects the configured max slope ratio,
     * measured over the cardinal forward distance. Delegates to the shared
     * SlopeValidator clamp so every slope-enforcement site applies one rule.
     */
    static int clampEndYToSlopeLimit(int startY, int endY, int forwardDist) {
        return SlopeValidator.clampEndYToSlopeLimit(startY, endY, forwardDist);
    }

    /**
     * Perpendicular (lateral) offset between two positions relative to the direction axis.
     * Positive = to the right of travel direction, negative = to the left.
     */
    static int lateralOffset(BlockPos from, BlockPos to, Direction direction) {
        return switch (direction) {
            case EAST -> to.getZ() - from.getZ();
            case WEST -> from.getZ() - to.getZ();
            case SOUTH -> from.getX() - to.getX();
            case NORTH -> to.getX() - from.getX();
            default -> 0;
        };
    }

    /**
     * Forward distance along the given cardinal direction axis.
     */
    static int horizontalDistance(BlockPos from, BlockPos to, Direction direction) {
        int dist = switch (direction) {
            case EAST -> to.getX() - from.getX();
            case WEST -> from.getX() - to.getX();
            case SOUTH -> to.getZ() - from.getZ();
            case NORTH -> from.getZ() - to.getZ();
            default -> 0;
        };
        return Math.max(0, dist);
    }
}
