package com.vodmordia.railwaysuntold.util.spatial;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Represents the four diagonal (45-degree) directions for track placement.
 * These correspond to Create mod's diagonal track shapes (PD and ND variants).
 */
public enum DiagonalDirection {
    NORTHEAST(new Vec3(1, 0, -1)),   // +X, -Z
    SOUTHEAST(new Vec3(1, 0, 1)),    // +X, +Z (Create's PD axis)
    SOUTHWEST(new Vec3(-1, 0, 1)),   // -X, +Z (Create's ND axis)
    NORTHWEST(new Vec3(-1, 0, -1));  // -X, -Z

    private final Vec3 axis;

    DiagonalDirection(Vec3 axis) {
        this.axis = axis;
    }

    public Vec3 getAxis() {
        return axis;
    }

    /**
     * Gets the diagonal direction resulting from a 45-degree turn from a cardinal direction.
     *
     * @param cardinal The starting cardinal direction
     * @param turnLeft True to turn left (counter-clockwise), false to turn right (clockwise)
     * @return The resulting diagonal direction
     */
    public static DiagonalDirection from45Turn(Direction cardinal, boolean turnLeft) {
        return switch (cardinal) {
            case NORTH -> turnLeft ? NORTHWEST : NORTHEAST;
            case SOUTH -> turnLeft ? SOUTHEAST : SOUTHWEST;
            case EAST -> turnLeft ? NORTHEAST : SOUTHEAST;
            case WEST -> turnLeft ? SOUTHWEST : NORTHWEST;
            default -> NORTHEAST; // UP/DOWN shouldn't happen
        };
    }

    /**
     * Checks if this diagonal uses Create's PD (positive diagonal) track shape.
     */
    public boolean isPositiveDiagonal() {
        return this == SOUTHEAST || this == NORTHWEST;
    }

    /**
     * Returns the X step for this diagonal direction.
     */
    public int getStepX() {
        return (int) axis.x;
    }

    /**
     * Returns the Z step for this diagonal direction.
     */
    public int getStepZ() {
        return (int) axis.z;
    }

    /**
     * Determines whether entering this diagonal from the given cardinal direction
     * constitutes a left turn.
     */
    public boolean isLeftTurnFrom(Direction cardinal) {
        return switch (cardinal) {
            case NORTH -> this == NORTHWEST;
            case SOUTH -> this == SOUTHEAST;
            case EAST -> this == NORTHEAST;
            case WEST -> this == SOUTHWEST;
            default -> false;
        };
    }

    /**
     * Returns true when {@code cardinal} is one of the two cardinal axes the
     * diagonal spans - i.e. a 45° transition between this diagonal and the
     * cardinal is geometrically valid. SE spans EAST and SOUTH, NE spans
     * EAST and NORTH, and so on. A transition to any other cardinal requires
     * 135° in a single arc, which Create renders as a hook / Y shape.
     */
    public boolean supportsCardinal(Direction cardinal) {
        return switch (this) {
            case NORTHEAST -> cardinal == Direction.NORTH || cardinal == Direction.EAST;
            case SOUTHEAST -> cardinal == Direction.SOUTH || cardinal == Direction.EAST;
            case SOUTHWEST -> cardinal == Direction.SOUTH || cardinal == Direction.WEST;
            case NORTHWEST -> cardinal == Direction.NORTH || cardinal == Direction.WEST;
        };
    }

    /**
     * Returns the compatible cardinal nearest to {@code preferred}. If
     * {@code preferred} is already compatible, it is returned unchanged.
     * Otherwise returns whichever of this diagonal's two valid cardinals is
     * 90° away from {@code preferred} rather than 180°. Used to keep a
     * diagonal exit geometrically valid when the planner picked a next-run
     * direction incompatible with the diagonal.
     */
    public Direction nearestCardinal(Direction preferred) {
        if (supportsCardinal(preferred)) return preferred;
        return switch (this) {
            case NORTHEAST -> preferred == Direction.WEST ? Direction.NORTH : Direction.EAST;
            case SOUTHEAST -> preferred == Direction.WEST ? Direction.SOUTH : Direction.EAST;
            case SOUTHWEST -> preferred == Direction.EAST ? Direction.SOUTH : Direction.WEST;
            case NORTHWEST -> preferred == Direction.EAST ? Direction.NORTH : Direction.WEST;
        };
    }
}
