package com.vodmordia.railwaysuntold.worldgen.planner;

import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * A bezier segment connecting two points with explicit start and end positions/directions.
 */
public class BezierSegment extends PathSegment {

    public final BlockPos end;
    public final Direction endDirection;

    public BezierSegment(BlockPos start, Direction startDirection, BlockPos end, Direction endDirection) {
        super(Type.BEZIER, start, startDirection);
        this.end = end;
        this.endDirection = endDirection;
    }

    @Override
    public BlockPos getEndPosition() {
        return end;
    }

    @Override
    public Direction getEndDirection() {
        return endDirection;
    }


    @Override
    public String toString() {
        int elevationChange = end.getY() - start.getY();
        return String.format("Bezier[%s -> %s, elevation=%+d]",
                start, end, elevationChange);
    }

    @Override
    public PlacementDecision execute(PathExecutionContext ctx) {
        // Emit the planner's start/direction into the decision, not the live head position.
        boolean isStraightX = start.getZ() == end.getZ();
        boolean isStraightZ = start.getX() == end.getX();
        boolean isFlatY = start.getY() == end.getY();

        Direction bezierDirection = null;
        if (isStraightX && !isStraightZ) {
            bezierDirection = end.getX() > start.getX() ? Direction.EAST : Direction.WEST;
        } else if (isStraightZ && !isStraightX) {
            bezierDirection = end.getZ() > start.getZ() ? Direction.SOUTH : Direction.NORTH;
        }

        if (bezierDirection != null && bezierDirection == startDirection && isFlatY) {
            int distance = Math.abs(end.getX() - start.getX()) +
                           Math.abs(end.getZ() - start.getZ());
            if (!terrainExceedsTrackY(ctx, distance)) {
                return PlacementDecision.straight(start, distance);
            }
        }

        return PlacementDecision.bezierWithExemption(start, end, startDirection);
    }

    /**
     * Checks whether terrain between start and end rises above the track Y level.
     * If so, placing straight track would cut into the hill - a bezier should be used instead.
     */
    private boolean terrainExceedsTrackY(PathExecutionContext ctx, int distance) {
        int[] heightProfile = ctx.scan() != null ? ctx.scan().getHeightProfile() : null;
        if (heightProfile == null) return false;

        int trackY = start.getY();
        // Height profile is indexed from scan origin (1 block ahead of scan start).
        // Check each block along the segment for terrain above track Y.
        for (int i = 0; i < distance && i < heightProfile.length; i++) {
            if (heightProfile[i] > trackY) {
                return true;
            }
        }
        return false;
    }
}
