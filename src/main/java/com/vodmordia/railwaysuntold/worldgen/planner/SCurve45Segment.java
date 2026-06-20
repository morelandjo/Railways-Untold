package com.vodmordia.railwaysuntold.worldgen.planner;

import com.vodmordia.railwaysuntold.util.placement.SCurve45Geometry;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * A 45-degree S-curve segment for smooth lateral displacement.
 *
 */
public class SCurve45Segment extends PathSegment {

    public final int radius;
    public final int diagonalLength;
    public final boolean shiftLeft;
    public final int elevationChange;

    public SCurve45Segment(
            BlockPos start,
            Direction direction,
            int radius,
            int diagonalLength,
            boolean shiftLeft,
            int elevationChange) {
        super(Type.SCURVE_45, start, direction);
        this.radius = radius;
        this.diagonalLength = diagonalLength;
        this.shiftLeft = shiftLeft;
        this.elevationChange = elevationChange;
    }

    @Override
    public BlockPos getEndPosition() {
        return SCurve45Geometry.calculateEndpoint(
                start, startDirection, shiftLeft, radius, diagonalLength, elevationChange);
    }

    @Override
    public Direction getEndDirection() {
        // S-curve maintains the original heading
        return startDirection;
    }


    @Override
    public String toString() {
        return String.format("SCurve45[%s -> %s, r=%d, diag=%d, %s, elev=%d]",
                start, getEndPosition(), radius, diagonalLength,
                shiftLeft ? "LEFT" : "RIGHT", elevationChange);
    }

    @Override
    public PlacementDecision execute(PathExecutionContext ctx) {
        return PlacementDecision.scurve45(
                ctx.currentPos(), ctx.currentDir(), radius, diagonalLength,
                shiftLeft, elevationChange, ctx.scan());
    }
}
