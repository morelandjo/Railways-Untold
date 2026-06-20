package com.vodmordia.railwaysuntold.util.spatial;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

public final class DirectionUtil {

    private DirectionUtil() {
    }

    /**
     * Gets the left perpendicular direction relative to the given forward direction.
     *
     * @param forward The forward direction (must be horizontal)
     * @return The direction to the left of forward
     */
    public static Direction getLeftDirection(Direction forward) {
        return switch (forward) {
            case SOUTH -> Direction.EAST;
            case EAST -> Direction.NORTH;
            case WEST -> Direction.SOUTH;
            default -> Direction.WEST;
        };
    }

    /**
     * Gets the right perpendicular direction relative to the given forward direction.
     *
     * @param forward The forward direction (must be horizontal)
     * @return The direction to the right of forward
     */
    public static Direction getRightDirection(Direction forward) {
        return switch (forward) {
            case SOUTH -> Direction.WEST;
            case EAST -> Direction.SOUTH;
            case WEST -> Direction.NORTH;
            default -> Direction.EAST;
        };
    }

    /**
     * Gets the perpendicular direction for a turn.
     *
     * @param forward  The forward direction (must be horizontal)
     * @param turnLeft If true, return left direction; if false, return right direction
     * @return The perpendicular direction
     */
    public static Direction getPerpendicularDirection(Direction forward, boolean turnLeft) {
        return turnLeft ? getLeftDirection(forward) : getRightDirection(forward);
    }

    /**
     * Gets the position along a direction's axis.
     * For X axis directions (EAST/WEST), returns x coordinate.
     * For Z axis directions (NORTH/SOUTH), returns z coordinate.
     * For Y axis directions (UP/DOWN), returns y coordinate.
     *
     * @param pos       The position
     * @param direction The direction whose axis to use
     * @return The coordinate along the direction's axis
     */
    public static int getPositionAlongAxis(BlockPos pos, Direction direction) {
        return switch (direction.getAxis()) {
            case X -> pos.getX();
            case Y -> pos.getY();
            case Z -> pos.getZ();
        };
    }

    /**
     * Gets the signed position along a direction's axis.
     * For POSITIVE axis directions (EAST, SOUTH, UP), returns the raw coordinate.
     * For NEGATIVE axis directions (WEST, NORTH, DOWN), returns the negated coordinate.
     *
     *
     * @param pos       The position
     * @param direction The direction whose axis and sign to use
     * @return The signed coordinate along the direction's axis
     */
    public static int getSignedPositionAlongAxis(BlockPos pos, Direction direction) {
        int sign = direction.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1;
        return switch (direction.getAxis()) {
            case X -> pos.getX() * sign;
            case Y -> pos.getY() * sign;
            case Z -> pos.getZ() * sign;
        };
    }

    /**
     * Checks if a target position is in the specified direction relative to the origin.
     *
     * @param origin    The reference position (e.g., head position)
     * @param target    The target position to check
     * @param direction The direction to check against
     * @return true if target is in the specified direction from origin
     */
    public static boolean isInDirection(BlockPos origin, BlockPos target, Direction direction) {
        return switch (direction) {
            case NORTH -> target.getZ() < origin.getZ();
            case SOUTH -> target.getZ() > origin.getZ();
            case EAST -> target.getX() > origin.getX();
            case WEST -> target.getX() < origin.getX();
            default -> true;
        };
    }

    /**
     * Checks if a target position is dominantly in the specified direction with configurable strictness.
     * Higher strictness values require the target to be more directly in the direction.
     * A strictness of 1.0 allows up to 45° off the cardinal direction.
     * A strictness of 1.5 narrows to ~34°.
     *
     * @param origin     The reference position
     * @param target     The target position to check
     * @param direction  The direction to check against
     * @param strictness Multiplier for the perpendicular component (1.0 = 45°, 1.5 = ~34°)
     * @return true if target is primarily in the specified direction from origin
     */
    public static boolean isDominantInDirection(BlockPos origin, BlockPos target, Direction direction, double strictness) {
        if (!isInDirection(origin, target, direction)) {
            return false;
        }

        int dx = Math.abs(target.getX() - origin.getX());
        int dz = Math.abs(target.getZ() - origin.getZ());

        boolean isEastWest = direction == Direction.EAST || direction == Direction.WEST;
        return isEastWest ? dx >= dz * strictness : dz >= dx * strictness;
    }

    /**
     * Checks if a target position is within the cardinal range of a direction.
     * Cardinal range means the target must be in the general direction, allowing for
     * diagonal movement as long as the majority of movement is in the cardinal direction.
     *
     * @param origin The reference position
     * @param target The target position to check
     * @param cardinalDirection The cardinal direction (NORTH, SOUTH, EAST, or WEST)
     * @return true if target is within the cardinal range
     */
    public static boolean isInCardinalRange(BlockPos origin, BlockPos target, Direction cardinalDirection) {
        int dx = target.getX() - origin.getX();
        int dz = target.getZ() - origin.getZ();
        int absDx = Math.abs(dx);
        int absDz = Math.abs(dz);

        return switch (cardinalDirection) {
            case NORTH -> dz < 0 && absDz >= absDx;
            case SOUTH -> dz > 0 && absDz >= absDx;
            case EAST -> dx > 0 && absDx >= absDz;
            case WEST -> dx < 0 && absDx >= absDz;
            default -> true;
        };
    }

    /**
     * Checks if a target position is NOT behind the head (within 45° of heading).
     * Targets beyond 45° from heading are considered "behind" and rejected.
     *
     * @param origin        The reference position (e.g., head position)
     * @param target        The target position to check
     * @param headDirection The direction the head is facing
     * @return true if target is within 45° of heading (acceptable for retargeting)
     */
    public static boolean isNotBehind(BlockPos origin, BlockPos target, Direction headDirection) {
        return isInCardinalRange(origin, target, headDirection);
    }

    /**
     * Calculates the angle in degrees between the head direction and the direction from origin to target.
     * Returns 0 for directly ahead, 90 for perpendicular, 180 for directly behind.
     */
    public static double angleBetween(BlockPos origin, BlockPos target, Direction headDirection) {
        double dx = target.getX() - origin.getX();
        double dz = target.getZ() - origin.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.001) return 0;

        double headDx = headDirection.getStepX();
        double headDz = headDirection.getStepZ();
        double dot = (dx * headDx + dz * headDz) / dist;
        dot = Math.max(-1.0, Math.min(1.0, dot));
        return Math.toDegrees(Math.acos(dot));
    }

    /**
     * Calculates the horizontal distance between two positions (ignoring Y coordinate).
     *
     * @param pos1 First position
     * @param pos2 Second position
     * @return The horizontal distance between the two positions
     */
    public static double horizontalDistance(BlockPos pos1, BlockPos pos2) {
        double dx = pos1.getX() - pos2.getX();
        double dz = pos1.getZ() - pos2.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Gets the primary direction of a line segment from start to end.
     * Returns the direction based on which axis (X or Z) has the larger delta.
     *
     * @param start The start position
     * @param end   The end position
     * @return The primary cardinal direction, or null if diagonal/zero length
     */
    public static Direction getSegmentDirection(BlockPos start, BlockPos end) {
        int dx = end.getX() - start.getX();
        int dz = end.getZ() - start.getZ();

        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        } else if (Math.abs(dz) > Math.abs(dx)) {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return null;
    }

    /**
     * Checks if two directions are perpendicular (one is N/S and the other is E/W).
     *
     * @param dir1 First direction (may be null)
     * @param dir2 Second direction (may be null)
     * @return true if perpendicular, false otherwise (including if either is null)
     */
    public static boolean arePerpendicular(Direction dir1, Direction dir2) {
        if (dir1 == null || dir2 == null) {
            return false;
        }

        boolean dir1IsNS = (dir1 == Direction.NORTH || dir1 == Direction.SOUTH);
        boolean dir2IsNS = (dir2 == Direction.NORTH || dir2 == Direction.SOUTH);

        return dir1IsNS != dir2IsNS;
    }

    /**
     * Converts a track axis direction to its positive expansion direction.
     * For N/S track axis: returns SOUTH (positive Z)
     * For E/W track axis: returns EAST (positive X)
     *
     * @param trackDir The track axis direction (must be NORTH or EAST)
     * @return The positive expansion direction
     * @throws IllegalArgumentException if trackDir is not NORTH or EAST
     */
    public static Direction getPositiveDirection(Direction trackDir) {
        if (trackDir == Direction.NORTH) {
            return Direction.SOUTH; // NS tracks expand south (positive Z)
        } else if (trackDir == Direction.EAST) {
            return Direction.EAST; // EW tracks expand east (positive X)
        } else {
            throw new IllegalArgumentException("Invalid track direction: " + trackDir);
        }
    }

    /**
     * Gets a position offset from center by the given direction vector and distance.
     *
     * @param center    Center position
     * @param direction Direction vector (should be normalized)
     * @param distance  Distance to offset
     * @return Offset position
     */
    public static BlockPos getOffsetPosition(BlockPos center, Vec3 direction, double distance) {
        return BlockPos.containing(
                Vec3.atCenterOf(center).add(direction.scale(distance))
        );
    }

    public static int calculateDirectionalDistance(BlockPos start, BlockPos end, Direction direction) {
        return switch (direction) {
            case NORTH -> start.getZ() - end.getZ();
            case SOUTH -> end.getZ() - start.getZ();
            case EAST -> end.getX() - start.getX();
            case WEST -> start.getX() - end.getX();
            default -> 0;
        };
    }

    /**
     * Computes the angle in degrees (0-360) from origin to target using atan2.
     * 0 = East (+X), 90 = South (+Z), 180 = West (-X), 270 = North (-Z).
     */
    public static double computeAngleFromOrigin(BlockPos origin, BlockPos target) {
        double dx = target.getX() - origin.getX();
        double dz = target.getZ() - origin.getZ();
        double angle = Math.toDegrees(Math.atan2(dz, dx));
        if (angle < 0) angle += 360.0;
        return angle;
    }

    /**
     * Returns the shortest angular distance (0-180) between two angles.
     */
    public static double angularDistance(double angle1, double angle2) {
        double diff = Math.abs(angle1 - angle2) % 360.0;
        return diff > 180.0 ? 360.0 - diff : diff;
    }

    /**
     * Returns the angle in degrees (0-180) between two targets as seen from origin.
     */
    public static double angularSeparation(BlockPos origin, BlockPos target1, BlockPos target2) {
        double angle1 = computeAngleFromOrigin(origin, target1);
        double angle2 = computeAngleFromOrigin(origin, target2);
        return angularDistance(angle1, angle2);
    }

    public static BlockPos generateExplorationTarget(BlockPos origin, Direction cardinalDirection, int distance, java.util.Random random) {
        int lateralOffset = random.nextInt(distance / 2 + 1) - (distance / 4);

        int dx = switch (cardinalDirection) {
            case EAST -> distance;
            case WEST -> -distance;
            default -> lateralOffset;
        };

        int dz = switch (cardinalDirection) {
            case NORTH -> -distance;
            case SOUTH -> distance;
            default -> lateralOffset;
        };

        return origin.offset(dx, 0, dz);
    }

    /**
     * Minimum horizontal distance (blocks) an exploration target must have from any other head's
     * position or exploration target to prevent track convergence.
     */
    private static final int MIN_EXPLORATION_TARGET_SEPARATION = 200;
    private static final int MAX_EXPLORATION_TARGET_RETRIES = 5;

    /**
     * Generates an exploration target that avoids being too close to other heads' positions
     * or their exploration targets. Retries up to 5 times with different random lateral offsets.
     * Falls back to the last generated target if all attempts are too close.
     *
     * @param origin             Current head position
     * @param cardinalDirection  Direction of travel
     * @param distance           Forward distance for the target
     * @param random             Random source
     * @param otherHeadPositions Positions of other active heads (may include their exploration targets)
     * @return A target position that maintains separation from other heads
     */
    public static BlockPos generateExplorationTargetAvoiding(
            BlockPos origin, Direction cardinalDirection, int distance,
            java.util.Random random, java.util.List<BlockPos> otherHeadPositions) {

        BlockPos best = null;
        double bestMinDist = -1;

        for (int attempt = 0; attempt < MAX_EXPLORATION_TARGET_RETRIES; attempt++) {
            BlockPos candidate = generateExplorationTarget(origin, cardinalDirection, distance, random);
            double minDist = Double.MAX_VALUE;
            for (BlockPos other : otherHeadPositions) {
                double dist = horizontalDistance(candidate, other);
                if (dist < minDist) {
                    minDist = dist;
                }
            }

            if (minDist >= MIN_EXPLORATION_TARGET_SEPARATION) {
                return candidate;
            }

            // Track the best candidate in case all are too close
            if (minDist > bestMinDist) {
                bestMinDist = minDist;
                best = candidate;
            }
        }

        // All attempts were too close - return the least-bad one
        return best != null ? best : generateExplorationTarget(origin, cardinalDirection, distance, random);
    }
}
