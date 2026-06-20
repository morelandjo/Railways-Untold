package com.vodmordia.railwaysuntold.worldgen.planner;

import com.vodmordia.railwaysuntold.worldgen.integration.create.util.CreateTrackUtil;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import com.vodmordia.railwaysuntold.worldgen.placement.decider.CurveParameterDecider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * A 90-degree curve segment with configurable radius and turn direction.
 */
public class CurveSegment extends PathSegment {

    public final int radius;
    public final boolean turnLeft;
    public final int elevationChange;

    public CurveSegment(BlockPos start, Direction direction, int radius, boolean turnLeft, int elevationChange) {
        super(Type.CURVE, start, direction);
        this.radius = radius;
        this.turnLeft = turnLeft;
        this.elevationChange = elevationChange;
    }

    @Override
    public BlockPos getEndPosition() {
        return CreateTrackUtil.calculateCurveEndpoint(start, startDirection, turnLeft, 90, radius, elevationChange);
    }

    @Override
    public Direction getEndDirection() {
        return turnLeft ? startDirection.getCounterClockWise() : startDirection.getClockWise();
    }

    @Override
    public String toString() {
        return String.format("Curve[%s -> %s, r=%d, %s]",
                start, getEndPosition(), radius, turnLeft ? "LEFT" : "RIGHT");
    }

    @Override
    public PlacementDecision execute(PathExecutionContext ctx) {
        CurveParameterDecider.CurveParameters curveParams = CurveParameterDecider.createWithExactRadius(
                ctx.currentPos(), ctx.currentDir(), radius, turnLeft, elevationChange);
        return PlacementDecision.curve(curveParams, ctx.scan());
    }
}
