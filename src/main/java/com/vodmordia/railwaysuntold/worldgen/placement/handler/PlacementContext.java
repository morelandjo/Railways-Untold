package com.vodmordia.railwaysuntold.worldgen.placement.handler;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.worldgen.head.ExpansionHeadManager;
import com.vodmordia.railwaysuntold.worldgen.head.TrackExpansionHead;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import com.vodmordia.railwaysuntold.worldgen.placement.TrackPlacementSavedData;
import com.vodmordia.railwaysuntold.worldgen.placement.decider.TunnelEscapeSystem;
import com.vodmordia.railwaysuntold.worldgen.terrain.TerrainScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import java.util.Random;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Context object containing all data needed for placement handling.
 */
public record PlacementContext(
        ServerLevel level,
        TrackExpansionHead head,
        PlacementDecision decision,
        TerrainScanner.TerrainScan scan,
        BlockPos currentPos,
        Direction expandDir,
        RailwaysUntoldConfig config,
        ExpansionHeadManager headManager,
        TrackPlacementSavedData savedData,
        Consumer<TrackExpansionHead> expandHeadCallback,
        Runnable scheduleParentContinuationCallback,
        TunnelEscapeFunction tunnelEscapeCallback,
        Random random
) {

    /**
     * Functional interface for tunnel escape logic.
     */
    @FunctionalInterface
    public interface TunnelEscapeFunction {
        TunnelEscapeSystem.EscapeResult tryEscape(ServerLevel level, BlockPos tunnelEnd, Direction direction,
                                                  RailwaysUntoldConfig config, int torchOffset,
                                                  IntConsumer onClearingComplete);
    }

    /**
     * Requests expansion of a head.
     */
    public void requestExpandHead(TrackExpansionHead headToExpand) {
        if (expandHeadCallback != null) {
            expandHeadCallback.accept(headToExpand);
        }
    }

    /**
     * Schedules continuation of the parent head after a branch (deferred execution).
     */
    public void scheduleParentContinuation() {
        if (scheduleParentContinuationCallback != null) {
            scheduleParentContinuationCallback.run();
        }
    }

    /**
     * Attempts to escape from a tunnel to the surface.
     *
     * @param tunnelEnd The position at the end of the tunnel segment
     * @return EscapeResult indicating whether escape was attempted and succeeded
     */
    public TunnelEscapeSystem.EscapeResult tryTunnelEscape(BlockPos tunnelEnd) {
        if (tunnelEscapeCallback != null) {
            return tunnelEscapeCallback.tryEscape(level, tunnelEnd, expandDir, config,
                    head.getTorchPlacementOffset(), head::addToTorchPlacementOffset);
        }
        return TunnelEscapeSystem.EscapeResult.notAttempted();
    }
}
