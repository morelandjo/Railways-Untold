package com.vodmordia.railwaysuntold.worldgen.placement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Logging context a single consolidated log.
 */
public class PlacementLogContext {

    private final int headNumber;
    private final String source;
    private BlockPos villageTarget;

    // Geometry info for path debugging
    private CurveInfo curveInfo;
    private Integer bezierDistance;
    private Direction branchDirection;
    private StraightInfo straightInfo;

    /**
     * Creates a new logging context.
     *
     * @param headNumber The track head number
     * @param source The decision source (e.g., "PathPlanner:terrain", "Avoidance:structure")
     */
    public PlacementLogContext(int headNumber, String source) {
        this.headNumber = headNumber;
        this.source = source;
    }

    /**
     * Sets the village target for this placement (if head is targeting a village).
     */
    public PlacementLogContext withVillageTarget(BlockPos target) {
        this.villageTarget = target;
        return this;
    }

    /**
     * Sets curve geometry info (radius and turn direction).
     */
    public PlacementLogContext withCurveInfo(int radius, boolean turnLeft) {
        this.curveInfo = new CurveInfo(radius, turnLeft);
        return this;
    }

    /**
     * Sets bezier distance info.
     */
    public PlacementLogContext withBezierInfo(int distance) {
        this.bezierDistance = distance;
        return this;
    }

    /**
     * Sets branch direction info.
     */
    public PlacementLogContext withBranchInfo(Direction branchDir) {
        this.branchDirection = branchDir;
        return this;
    }

    /**
     * Builds the final consolidated log line.
     *
     * @param start Start position
     * @param end End position
     * @param startDir Direction at start
     * @param endDir Direction at end
     * @param elevationChange Elevation change
     * @param success Whether placement succeeded
     * @param undergroundSegments Number of underground segments (0 if surface)
     * @return Formatted log line
     */
    public String build(BlockPos start, BlockPos end, Direction startDir, Direction endDir,
                        int elevationChange, boolean success, int undergroundSegments) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[TRACK] Head %d: %s | (%d,%d,%d) %s -> (%d,%d,%d) %s | elev=%d",
            headNumber,
            source,
            start.getX(), start.getY(), start.getZ(), dirToChar(startDir),
            end.getX(), end.getY(), end.getZ(), dirToChar(endDir),
            elevationChange));

        if (undergroundSegments > 0) {
            sb.append(" | tunnel=").append(undergroundSegments);
        }

        // Geometry info for path debugging
        if (curveInfo != null) {
            sb.append(" | curve=").append(curveInfo.turnLeft() ? "L" : "R").append("/").append(curveInfo.radius());
        } else if (bezierDistance != null && bezierDistance > 0) {
            sb.append(" | bezier=").append(bezierDistance);
        } else if (branchDirection != null) {
            sb.append(" | branch=").append(dirToChar(branchDirection));
        } else if (straightInfo != null) {
            sb.append(" | straight=").append(straightInfo.actualDistance());
            if (straightInfo.actualDistance() != straightInfo.requestedDistance()) {
                sb.append("/").append(straightInfo.requestedDistance());
            }
            int newlyExpected = straightInfo.actualDistance() + 1;
            if (straightInfo.tracksPlaced() < newlyExpected) {
                sb.append(" (new=").append(straightInfo.tracksPlaced()).append(")");
            }
        }

        if (villageTarget != null) {
            sb.append(String.format(" | target=(%d,%d,%d)", villageTarget.getX(), villageTarget.getY(), villageTarget.getZ()));
        }

        sb.append(" | ").append(success ? "ok" : "FAIL");
        return sb.toString();
    }

    /**
     * Converts direction to single character for compact logging.
     */
    private static String dirToChar(Direction dir) {
        if (dir == null) return "?";
        return switch (dir) {
            case NORTH -> "N";
            case SOUTH -> "S";
            case EAST -> "E";
            case WEST -> "W";
            case UP -> "U";
            case DOWN -> "D";
        };
    }

    private record CurveInfo(int radius, boolean turnLeft) {}

    private record StraightInfo(int requestedDistance, int actualDistance, int tracksPlaced) {}
}
