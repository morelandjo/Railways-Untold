package com.vodmordia.railwaysuntold.worldgen.placement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Geometry for curving a head's tip into a foreign track as a junction, validating that the connecting
 * curve holds at least the minimum radius - so a merge never lays track a train can't drive.
 *
 * Two cases, by the foreign line's heading relative to the tip's:
 * <ul>
 *   <li>PERPENDICULAR (a 90-degree branch run backwards): a turn of up to 90 degrees at radius R lands R
 *       forward and at most R to the side, so the point holds the minimum radius when its forward run is
 *       at least minRadius and its lateral offset is no greater than that forward run.</li>
 *   <li>PARALLEL (merging into a same-heading line): the connection is an S-curve - shift laterally and
 *       re-align. A symmetric S of lateral L over forward F holds minimum radius R = (F^2 + L^2)/(4L),
 *       about HALF the single-turn radius, so it needs more forward room. {@code lateral <= forward} is
 *       NOT enough here (it admits R = F/2); the point must be far enough down the line for a legal S.</li>
 * </ul>
 * A point beside, behind, or too close to the tip forces a sub-radius hairpin and is rejected, so the
 * merge picks a point further down the foreign track where the curve is legal.
 */
public final class JunctionMergeGeometry {

    private JunctionMergeGeometry() {}

    /**
     * True if a curve from {@code tip} (travelling {@code dir}) into {@code foreign} holds at least
     * {@code minRadius}, given whether the foreign line runs {@code parallel} to the heading (S-curve)
     * or across it (single &le;90-degree turn), and the point is within {@code maxVertical} of the tip.
     */
    public static boolean canConnectLegally(BlockPos foreign, BlockPos tip, Direction dir,
                                            boolean parallel, int minRadius, int maxVertical) {
        int forward = (foreign.getX() - tip.getX()) * dir.getStepX()
                + (foreign.getZ() - tip.getZ()) * dir.getStepZ();
        if (forward < minRadius) {
            return false; // behind, or too close for even a single min-radius arc
        }
        if (Math.abs(foreign.getY() - tip.getY()) > maxVertical) {
            return false;
        }
        int lateral = dir.getAxis() == Direction.Axis.X
                ? Math.abs(foreign.getZ() - tip.getZ())
                : Math.abs(foreign.getX() - tip.getX());

        if (parallel) {
            if (lateral == 0) {
                return true; // already aligned - a straight join
            }
            // R = (F^2 + L^2) / (4L) >= minRadius  <=>  F^2 + L^2 >= 4 * minRadius * L
            return (long) forward * forward + (long) lateral * lateral >= 4L * minRadius * lateral;
        }
        // Perpendicular single turn: lateral <= forward is a turn of <=90 degrees, and forward >= minRadius
        // already gives R = forward/sin(theta) >= minRadius.
        return lateral <= forward;
    }
}
