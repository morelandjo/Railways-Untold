package com.vodmordia.railwaysuntold.worldgen.placement.handler;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.worldgen.head.VillageHeadAssigner;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.SchematicPlacer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.slf4j.Logger;

/**
 * Executor for STATION placement decisions.
 *
 * Handles:
 * 1. Moving the track head from the station entry to the station exit
 * 2. Clearing village state
 * 3. Either initiating branch return or assigning a new village target
 * 4. Continuing track placement past the station
 */
public class StationPlacementExecutor extends AbstractPlacementExecutor {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public PlacementDecision.Type getType() {
        return PlacementDecision.Type.STATION;
    }

    @Override
    public HandlerResult handle(PlacementContext ctx) {
        SchematicPlacer.SchematicPlacementResult placedStation = ctx.head().getVillageState().getPlacedStation();

        if (placedStation == null || !placedStation.success) {
            LOGGER.error("[STATION-HANDLER] Station decision without placed station at {}", ctx.currentPos());
            return HandlerResult.failed("Station decision without placed station");
        }

        BlockPos exitPoint = placedStation.trackEnd;
        BlockPos entryPoint = placedStation.trackStart;

        // Determine continuation direction using the track axis from placement,
        // with the sign derived from the entry-to-exit vector.
        Direction exitDirection;
        Direction trackDir = placedStation.trackDirection;
        if (trackDir != null) {
            boolean trackAlongX = trackDir == Direction.EAST || trackDir == Direction.WEST;
            if (trackAlongX) {
                exitDirection = exitPoint.getX() >= entryPoint.getX() ? Direction.EAST : Direction.WEST;
            } else {
                exitDirection = exitPoint.getZ() >= entryPoint.getZ() ? Direction.SOUTH : Direction.NORTH;
            }
        } else {
            // Fallback: determine from position comparison
            if (exitPoint.getX() > entryPoint.getX()) {
                exitDirection = Direction.EAST;
            } else if (exitPoint.getX() < entryPoint.getX()) {
                exitDirection = Direction.WEST;
            } else if (exitPoint.getZ() > entryPoint.getZ()) {
                exitDirection = Direction.SOUTH;
            } else {
                exitDirection = Direction.NORTH;
            }
        }

        ctx.head().getVillageState().clear();

        // Update head position and direction BEFORE assigning new village
        ctx.head().setPosition(exitPoint);
        ctx.head().setDirection(exitDirection);
        ctx.head().markHasPlacedSegments();
        ctx.head().onDirectionChange();

        // Now assign new village using the updated position/direction
        VillageHeadAssigner.assignNewVillageToHead(
                ctx.head(), ctx.level(), ctx.config());


        return HandlerResult.succeeded();
    }

}
