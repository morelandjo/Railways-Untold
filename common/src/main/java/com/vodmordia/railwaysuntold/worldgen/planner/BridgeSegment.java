package com.vodmordia.railwaysuntold.worldgen.planner;

import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * A bridge segment that crosses over gaps in terrain.
 */
public class BridgeSegment extends PathSegment {

    private final BlockPos end;
    private final int elevationChange;

    /**
     * Creates a bridge segment with an explicit end position, allowing curves
     * and lateral offsets. The Create track connector will generate a smooth
     * bezier curve between the start and end, and bridge decking/pillars will
     * follow that curve.
     *
     * @param start     Starting position
     * @param direction Cardinal direction of travel
     * @param end       Explicit end position (may be laterally offset from cardinal axis)
     */
    public BridgeSegment(BlockPos start, Direction direction, BlockPos end) {
        super(Type.BRIDGE, start, direction);
        this.end = end;
        this.elevationChange = end.getY() - start.getY();
    }

    @Override
    public BlockPos getEndPosition() {
        return end;
    }

    @Override
    public Direction getEndDirection() {
        // Bridges don't change direction
        return startDirection;
    }


    @Override
    public String toString() {
        return String.format("Bridge[%s -> %s, elevation=%+d]",
                start, end, elevationChange);
    }

    @Override
    public PlacementDecision execute(PathExecutionContext ctx) {
        return PlacementDecision.bridge(ctx.currentPos(), getEndPosition(), ctx.currentDir(), ctx.scan());
    }
}
