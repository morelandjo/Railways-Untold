package com.vodmordia.railwaysuntold.worldgen.placement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Decides where the starting station origin goes when village footprints sit across the
 * initial straight track corridor. Shifts the origin perpendicular to the track direction
 * until the corridor clears every footprint by a margin, picking whichever perpendicular side
 * lands on the lower surface so the station does not get buried in a mountain.
 *
 * The decision is pure geometry over footprint boxes plus an injected surface-height function,
 * so it can be reproduced and tested without a world.
 */
public final class StationOriginNudge {

    private StationOriginNudge() {
    }

    /** Blocks of clearance kept between the track corridor and a village footprint. */
    public static final int CLEARANCE_MARGIN = 8;

    /** Footprints further behind the origin than this (along travel) do not block the start. */
    private static final int STATION_BODY = 24;

    /** Safety bound so a pathological footprint set can never spin the resolve loop. */
    private static final int MAX_PASSES = 8;

    /**
     * Returns an origin shifted perpendicular to {@code trackDirection} so the straight starting
     * corridor clears every village footprint by {@link #CLEARANCE_MARGIN}, or the original origin
     * if it is already clear. When the origin must move, both perpendicular exits (toward lower and
     * higher perpendicular coordinate) are evaluated and the one with the lower surface height wins,
     * keeping the station off mountainous terrain it would otherwise be buried under.
     *
     * @param surfaceHeight surface Y at a candidate origin, used only to break the tie between the
     *                      two exit sides.
     */
    public static BlockPos resolve(BlockPos origin, Direction trackDirection,
                                   List<BoundingBox> villageFootprints,
                                   ToIntFunction<BlockPos> surfaceHeight) {
        if (villageFootprints.isEmpty()) {
            return origin;
        }

        boolean alongIsX = trackDirection.getAxis() == Direction.Axis.X;
        int alongOrigin = alongIsX ? origin.getX() : origin.getZ();
        int travelStep = alongIsX ? trackDirection.getStepX() : trackDirection.getStepZ();
        int perpOrigin = alongIsX ? origin.getZ() : origin.getX();

        int perpToward = directedResolve(perpOrigin, villageFootprints, alongIsX, alongOrigin, travelStep, +1);
        int perpAway = directedResolve(perpOrigin, villageFootprints, alongIsX, alongOrigin, travelStep, -1);

        if (perpToward == perpOrigin && perpAway == perpOrigin) {
            return origin;
        }
        if (perpToward == perpOrigin) {
            return withPerp(origin, alongIsX, perpAway);
        }
        if (perpAway == perpOrigin) {
            return withPerp(origin, alongIsX, perpToward);
        }

        BlockPos candToward = withPerp(origin, alongIsX, perpToward);
        BlockPos candAway = withPerp(origin, alongIsX, perpAway);
        return surfaceHeight.applyAsInt(candToward) <= surfaceHeight.applyAsInt(candAway)
                ? candToward
                : candAway;
    }

    /**
     * Pushes the perpendicular coordinate clear of every blocking footprint, always exiting on the
     * side given by {@code side} (+1 = higher perpendicular edge, -1 = lower). Returns the original
     * perpendicular coordinate if nothing blocks the corridor.
     */
    private static int directedResolve(int perp, List<BoundingBox> footprints, boolean alongIsX,
                                       int alongOrigin, int travelStep, int side) {
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            boolean moved = false;
            for (BoundingBox box : footprints) {
                int boxAlongMin = alongIsX ? box.minX() : box.minZ();
                int boxAlongMax = alongIsX ? box.maxX() : box.maxZ();

                boolean entirelyBehind = travelStep > 0
                        ? boxAlongMax < alongOrigin - STATION_BODY
                        : boxAlongMin > alongOrigin + STATION_BODY;
                if (entirelyBehind) {
                    continue;
                }

                int boxPerpMin = alongIsX ? box.minZ() : box.minX();
                int boxPerpMax = alongIsX ? box.maxZ() : box.maxX();

                if (perp <= boxPerpMin - CLEARANCE_MARGIN || perp >= boxPerpMax + CLEARANCE_MARGIN) {
                    continue;
                }

                perp = side > 0 ? boxPerpMax + CLEARANCE_MARGIN : boxPerpMin - CLEARANCE_MARGIN;
                moved = true;
            }
            if (!moved) {
                break;
            }
        }
        return perp;
    }

    private static BlockPos withPerp(BlockPos origin, boolean alongIsX, int perp) {
        return alongIsX
                ? new BlockPos(origin.getX(), origin.getY(), perp)
                : new BlockPos(perp, origin.getY(), origin.getZ());
    }
}
