package com.vodmordia.railwaysuntold.worldgen.planner;

import com.vodmordia.railwaysuntold.util.spatial.DiagonalDirection;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * A straight diagonal track segment at 45 degrees.
 * Length is measured in diagonal steps (each step moves 1 block in X and 1 in Z).
 */
public class DiagonalStraightSegment extends PathSegment {

    public final DiagonalDirection diagonal;
    public final int length;
    public final int elevationChange;

    public DiagonalStraightSegment(BlockPos start, Direction cardinalDir, DiagonalDirection diagonal,
                                    int length, int elevationChange) {
        super(Type.DIAGONAL_STRAIGHT, start, cardinalDir);
        this.diagonal = diagonal;
        this.length = length;
        this.elevationChange = elevationChange;
    }

    @Override
    public BlockPos getEndPosition() {
        return start.offset(
                diagonal.getStepX() * length, elevationChange, diagonal.getStepZ() * length);
    }

    @Override
    public Direction getEndDirection() {
        return startDirection;
    }


    @Override
    public String toString() {
        return String.format("DiagStraight[%s -> %s, %s, len=%d, elev=%d]",
                start, getEndPosition(), diagonal, length, elevationChange);
    }

    @Override
    public PlacementDecision execute(PathExecutionContext ctx) {
        return PlacementDecision.diagonalStraight(
                ctx.currentPos(), diagonal, length, elevationChange, ctx.scan());
    }
}
