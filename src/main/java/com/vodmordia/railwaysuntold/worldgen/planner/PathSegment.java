package com.vodmordia.railwaysuntold.worldgen.planner;

import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Abstract base class for path segments used in approach planning.
 */
public abstract class PathSegment {

    public enum Type {
        CURVE,              // 90-degree turn
        BEZIER,             // Straight or sloped connection
        TUNNEL,             // Through-mountain passage
        BRIDGE,             // Over-ravine crossing
        ELEVATED_TUNNEL,    // Bezier inside tunnel (sloped tunnel)
        SCURVE_45,          // 45-degree S-curve for lateral displacement
        DIAGONAL_ENTRY,     // 45-degree curve from cardinal to diagonal
        DIAGONAL_STRAIGHT,  // Straight diagonal track segment
        DIAGONAL_EXIT       // 45-degree curve from diagonal to cardinal
    }

    public final Type type;
    protected final BlockPos start;
    protected final Direction startDirection;

    protected PathSegment(Type type, BlockPos start, Direction startDirection) {
        this.type = type;
        this.start = start;
        this.startDirection = startDirection;
    }

    public BlockPos getStart() {
        return start;
    }

    public Direction getStartDirection() {
        return startDirection;
    }

    /**
     * Returns the end position after this segment is placed.
     */
    public abstract BlockPos getEndPosition();

    /**
     * Returns the direction the track will be facing at the end of this segment.
     */
    public abstract Direction getEndDirection();

    /**
     * Calculates the horizontal (Manhattan) distance between two positions, ignoring Y.
     *
     * @param start Start position
     * @param end   End position
     * @return The sum of absolute X and Z deltas
     */
    protected static int horizontalDistance(BlockPos start, BlockPos end) {
        return Math.abs(end.getX() - start.getX()) + Math.abs(end.getZ() - start.getZ());
    }

    /**
     * Executes this segment and returns the placement decision.
     * Each segment type implements its own execution logic.
     *
     * @param ctx Execution context with state and environment
     * @return PlacementDecision for track placement
     */
    public abstract PlacementDecision execute(PathExecutionContext ctx);
}
