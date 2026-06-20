package com.vodmordia.railwaysuntold.worldgen.placement.handler;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementConstants;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import com.vodmordia.railwaysuntold.worldgen.placement.companion.CompanionTrackPlacer;
import com.vodmordia.railwaysuntold.worldgen.placement.decider.TunnelEscapeSystem;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.BezierConnectionPlacer;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.BezierPlacementRequest;
import com.vodmordia.railwaysuntold.worldgen.placement.support.SupportConstants;
import com.vodmordia.railwaysuntold.worldgen.placement.support.SupportResult;
import com.vodmordia.railwaysuntold.worldgen.terrain.TerrainConstants;
import com.vodmordia.railwaysuntold.worldgen.terrain.TerrainScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.function.IntConsumer;

/**
 * Executor for TUNNEL placement decisions.
 */
public class TunnelPlacementExecutor extends AbstractPlacementExecutor {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public PlacementDecision.Type getType() {
        return PlacementDecision.Type.TUNNEL;
    }

    @Override
    public HandlerResult handle(PlacementContext ctx) {
        var drift = plannerDriftGuard(ctx, ctx.decision().getStart(), "tunnel");
        if (drift.isPresent()) return drift.get();

        SupportResult tunnelResult = createTunnel(
                ctx.level(), ctx.decision().getStart(), ctx.decision().getDirection(),
                ctx.decision().getScan(), ctx.config(), ctx.head().getTorchPlacementOffset(),
                ctx.head().getHeadId(), null);

        if (!tunnelResult.success) {
            if (tunnelResult.needsRetry) {
                return deferForDirectionalRange(
                        ctx.decision().getStart(),
                        ctx.decision().getDirection(),
                        PlacementConstants.DEFER_RANGE_BLOCKS,
                        "Tunnel"
                );
            }
            LOGGER.warn("[TUNNEL-HANDLER] Tunnel creation FAILED at {}. Reason: {}",
                    ctx.decision().getStart(), tunnelResult.failureReason);
            return HandlerResult.failed("Tunnel creation failed: " + tunnelResult.failureReason);
        }

        // Update torch offset synchronously so the next tunnel segment starts with
        // the correct offset. All segments in a tunnel are underground by definition.
        ctx.head().addToTorchPlacementOffset(tunnelResult.blocksTraveled + 1);

        TunnelEscapeSystem.EscapeResult escapeResult = ctx.tryTunnelEscape(tunnelResult.endpoint);

        if (escapeResult.success) {
            applyBasicStateUpdate(ctx.head(), escapeResult.endpoint, escapeResult.distance);

            // Place companion escape bezier to match the primary escape
            if (RailwaysUntoldConfig.isSideBySideEnabled() && ctx.head().getBranchDepth() == 0) {
                placeCompanionEscape(ctx, tunnelResult.endpoint, escapeResult.endpoint);
            }
        } else {
            applySupportResult(ctx.head(), tunnelResult);
        }

        return HandlerResult.succeeded();
    }

    /**
     * Places a companion bezier to match the primary's tunnel escape segment.
     * The escape goes from the tunnel endpoint upward to the surface.
     */
    private void placeCompanionEscape(PlacementContext ctx, BlockPos tunnelEnd, BlockPos escapeEnd) {
        Direction travelDir = ctx.decision().getDirection();
        int separation = CompanionTrackPlacer.getCompanionSeparation();
        Direction companionDir = CompanionTrackPlacer.getHeadCompanionDirection(ctx.head(), travelDir);

        BlockPos companionTunnelEnd = tunnelEnd.relative(companionDir, separation);
        BlockPos companionEscapeEnd = escapeEnd.relative(companionDir, separation);

        int elevationChange = escapeEnd.getY() - tunnelEnd.getY();


        BezierPlacementRequest request = BezierPlacementRequest.builder(companionTunnelEnd, companionEscapeEnd, travelDir)
                .config(ctx.config())
                .elevationChange(elevationChange)
                .build();

        BezierConnectionPlacer.BezierConnectionResult result = BezierConnectionPlacer.place(ctx.level(), request);
        if (!result.success) {
            com.vodmordia.railwaysuntold.worldgen.placement.companion.DeferredCompanionQueue.enqueue(
                    () -> BezierConnectionPlacer.place(ctx.level(), request).success,
                    companionTunnelEnd, companionEscapeEnd, "tunnel-escape");
        }
    }

    /**
     * Creates a tunnel through terrain with torch offset tracking.
     *
     * @param level       Server level
     * @param start       Starting track position
     * @param direction   Travel direction
     * @param scan        Terrain scan data
     * @param config      World generation config
     * @param torchOffset Starting offset for torch interval calculation
     * @param headId      UUID of the head placing this segment
     * @return SupportResult with tunnel endpoint and success status
     */
    private static SupportResult createTunnel(ServerLevel level, BlockPos start, Direction direction,
                                              TerrainScanner.TerrainScan scan, RailwaysUntoldConfig config,
                                              int torchOffset, UUID headId,
                                              IntConsumer onClearingComplete) {
        int tunnelLength = findTunnelEndpointBeforeRavine(scan, start.getY(), SupportConstants.TUNNEL_LENGTH);
        BlockPos end = start.relative(direction, tunnelLength);

        BezierPlacementRequest request = BezierPlacementRequest.builder(start, end, direction)
                .config(config)
                .torchOffset(torchOffset)
                .headId(headId)
                .onClearingComplete(onClearingComplete)
                .build();

        BezierConnectionPlacer.BezierConnectionResult result = BezierConnectionPlacer.place(level, request);

        if (result.needsRetry) {
            return SupportResult.failure("Bezier needs retry").needsRetry(true).build();
        }
        if (!result.success) {
            LOGGER.warn("[TUNNEL] Bezier placement FAILED at {} -> {}", start, end);
            return SupportResult.failure("Bezier placement failed").build();
        }

        return SupportResult.success(end, direction, tunnelLength)
                .build();
    }

    /**
     * Finds an appropriate tunnel endpoint that doesn't exit over a ravine.
     *
     * @param scan        Terrain scan data
     * @param startHeight Current track height
     * @param maxLength   Maximum tunnel length (typically 16)
     * @return Adjusted tunnel length (may be shorter if ravine detected)
     */
    public static int findTunnelEndpointBeforeRavine(TerrainScanner.TerrainScan scan,
                                                      int startHeight, int maxLength) {
        if (scan == null) {
            return maxLength;
        }

        int[] heightProfile = scan.getHeightProfile();
        if (heightProfile == null || heightProfile.length < maxLength) {
            return maxLength; // Can't analyze, use default
        }

        // Scan from position 1 to maxLength looking for ravine start
        final int ravineDropThreshold = TerrainConstants.AIR_HEIGHT_OFFSET; // -3

        for (int dist = 1; dist <= maxLength && dist < heightProfile.length; dist++) {
            int terrainHeight = heightProfile[dist];
            int heightDiff = terrainHeight - startHeight;

            // Terrain drops significantly below track level - ravine detected
            if (heightDiff < ravineDropThreshold) {
                // End tunnel just before the ravine edge (min 8 blocks for valid track)
                int adjustedLength = Math.max(SupportConstants.MIN_TUNNEL_LENGTH, dist - 1);

                return adjustedLength;
            }
        }

        return maxLength; // No ravine found, use full length
    }
}
