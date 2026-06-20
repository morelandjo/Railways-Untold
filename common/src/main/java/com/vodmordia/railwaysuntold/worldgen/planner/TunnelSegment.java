package com.vodmordia.railwaysuntold.worldgen.planner;

import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * A tunnel segment that passes through solid terrain (mountains). The Create track
 * connector generates a bezier between start and end, so the tunnel may curve. A
 * non-zero elevation change makes it a sloped (elevated) tunnel, which carries a
 * distinct placement decision and type.
 */
public class TunnelSegment extends PathSegment {

    private final BlockPos end;
    private final int elevationChange;

    /**
     * Creates a tunnel segment with an explicit end position. The type is ELEVATED_TUNNEL
     * when the end sits at a different Y than the start, TUNNEL when the tunnel is flat.
     */
    public TunnelSegment(BlockPos start, Direction direction, BlockPos end) {
        super(end.getY() == start.getY() ? Type.TUNNEL : Type.ELEVATED_TUNNEL, start, direction);
        this.end = end;
        this.elevationChange = end.getY() - start.getY();
    }

    @Override
    public BlockPos getEndPosition() {
        return end;
    }

    @Override
    public Direction getEndDirection() {
        // Tunnels don't change direction
        return startDirection;
    }

    public int getLength() {
        return horizontalDistance(start, end);
    }

    @Override
    public String toString() {
        if (elevationChange == 0) {
            int dx = end.getX() - start.getX();
            int dz = end.getZ() - start.getZ();
            int length = (int) Math.sqrt((double) dx * dx + (double) dz * dz);
            return String.format("Tunnel[%s -> %s, length=%d]", start, getEndPosition(), length);
        }
        return String.format("ElevatedTunnel[%s -> %s, elevation=%+d]", start, end, elevationChange);
    }

    @Override
    public PlacementDecision execute(PathExecutionContext ctx) {
        if (elevationChange == 0) {
            return PlacementDecision.tunnel(ctx.currentPos(), ctx.currentDir(), ctx.scan());
        }
        return PlacementDecision.elevatedTunnel(
                ctx.currentPos(), getEndPosition(),
                getLength(), elevationChange, ctx.currentDir(), ctx.scan());
    }
}
