package com.vodmordia.railwaysuntold.worldgen.planner;

import com.vodmordia.railwaysuntold.util.placement.SCurve45Geometry;
import com.vodmordia.railwaysuntold.util.spatial.DiagonalDirection;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * A 45-degree curve between cardinal and diagonal travel, using one half of the SCurve45
 * geometry. An entry curve turns from cardinal into the diagonal; an exit curve turns from
 * the diagonal back to a cardinal. The two share their geometry - an entry is an exit whose
 * exit cardinal equals its start direction - and differ only in their type and the placement
 * decision they emit.
 */
public class DiagonalCurveSegment extends PathSegment {

    public final DiagonalDirection diagonal;
    public final Direction exitCardinal;
    public final int radius;
    public final int elevationChange;
    private final boolean entry;

    /** Entry curve: cardinal {@code cardinalDir} into the diagonal. */
    public static DiagonalCurveSegment entry(BlockPos start, Direction cardinalDir, DiagonalDirection diagonal,
                                             int radius, int elevationChange) {
        return new DiagonalCurveSegment(Type.DIAGONAL_ENTRY, start, cardinalDir, diagonal,
                cardinalDir, radius, elevationChange, true);
    }

    /** Exit curve: diagonal back to {@code exitCardinal}. */
    public static DiagonalCurveSegment exit(BlockPos start, Direction currentCardinal, DiagonalDirection diagonal,
                                            Direction exitCardinal, int radius, int elevationChange) {
        return new DiagonalCurveSegment(Type.DIAGONAL_EXIT, start, currentCardinal, diagonal,
                exitCardinal, radius, elevationChange, false);
    }

    private DiagonalCurveSegment(Type type, BlockPos start, Direction startDirection, DiagonalDirection diagonal,
                                 Direction exitCardinal, int radius, int elevationChange, boolean entry) {
        super(type, start, startDirection);
        this.diagonal = diagonal;
        this.exitCardinal = exitCardinal;
        this.radius = radius;
        this.elevationChange = elevationChange;
        this.entry = entry;
    }

    @Override
    public BlockPos getEndPosition() {
        boolean shiftLeft = diagonal.isLeftTurnFrom(exitCardinal);
        return SCurve45Geometry.calculateFirstCurveEnd(start, exitCardinal, shiftLeft, radius, elevationChange);
    }

    @Override
    public Direction getEndDirection() {
        return exitCardinal;
    }

    @Override
    public String toString() {
        return entry
                ? String.format("DiagEntry[%s -> %s, %s -> %s, r=%d]",
                        start, getEndPosition(), startDirection, diagonal, radius)
                : String.format("DiagExit[%s -> %s, %s -> %s, r=%d]",
                        start, getEndPosition(), diagonal, exitCardinal, radius);
    }

    @Override
    public PlacementDecision execute(PathExecutionContext ctx) {
        return entry
                ? PlacementDecision.diagonalEntry(
                        ctx.currentPos(), ctx.currentDir(), diagonal, radius, elevationChange, ctx.scan())
                : PlacementDecision.diagonalExit(
                        ctx.currentPos(), diagonal, exitCardinal, radius, elevationChange, ctx.scan());
    }
}
