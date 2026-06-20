package com.vodmordia.railwaysuntold.worldgen.siding;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.block.ChunkSafeBlockAccess;
import com.vodmordia.railwaysuntold.util.chunk.ChunkVerificationUtil;
import com.vodmordia.railwaysuntold.util.placement.SCurve45Geometry;
import com.vodmordia.railwaysuntold.util.spatial.DirectionUtil;
import com.vodmordia.railwaysuntold.worldgen.integration.create.SCurve45TrackPlacer;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.BezierSegmentExtractor;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.BezierSegmentExtractor.BezierSegmentData;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.CreateTrackUtil;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementConstants;
import com.vodmordia.railwaysuntold.worldgen.placement.handler.SupportPlacementService;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.BezierConnectionPlacer;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.BezierPlacementRequest;
import com.vodmordia.railwaysuntold.worldgen.placement.support.BridgeSchematicPlacer;
import com.vodmordia.railwaysuntold.worldgen.terrain.clearing.ClearingTypes;
import com.vodmordia.railwaysuntold.worldgen.terrain.clearing.DeferredTerrainClearer;
import com.vodmordia.railwaysuntold.worldgen.terrain.clearing.util.RemovalUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Creates rail sidings - parallel passing loops that diverge from and reconnect to the parent track.
 *
 */
public class SidingTrackCreator {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int SIDING_SCURVE_DIAGONAL_LENGTH = 10;

    /**
     * Self-expiring ticket pinning the whole siding footprint while it builds. A siding spans tens
     * of blocks behind the moving head, so under autoload the release sweep would otherwise unload
     * its chunks mid-build - reverting the just-placed track to air and failing the reconnect leg
     * and deferred connections. Pinning holds the footprint loaded across the build's retries and
     * connection resolution. It's a distinct type from autoload's CHUNK_LOAD_TICKET, so the sweep
     * can't remove it, and it auto-purges so nothing leaks. Re-pinned (refreshed) each attempt.
     */
    private static final int SIDING_PIN_TIMEOUT_TICKS = 600;
    private static final TicketType<ChunkPos> SIDING_PIN_TICKET = TicketType.create(
            "railwaysuntold_siding_pin",
            Comparator.comparingLong(ChunkPos::toLong),
            SIDING_PIN_TIMEOUT_TICKS);

    /**
     * Result of siding construction.
     */
    public static class SidingResult {
        public final boolean success;
        public final boolean needsRetry;

        private SidingResult(boolean success, boolean needsRetry) {
            this.success = success;
            this.needsRetry = needsRetry;
        }

        public static SidingResult succeeded() {
            return new SidingResult(true, false);
        }

        public static SidingResult failed() {
            return new SidingResult(false, false);
        }

        public static SidingResult needsRetry() {
            return new SidingResult(false, true);
        }
    }

    /**
     * Builds a complete siding that diverges from the parent track and reconnects.
     *
     * @param level               The server level
     * @param sidingOrigin        Point on the parent track where the siding starts
     * @param parentDir           Parent track travel direction
     * @param placeLeft           True to place siding on the left side
     * @param sidingStraightLength Length of the parallel straight section
     * @param headId              UUID of the expansion head
     * @return SidingResult indicating success/failure
     */
    public static SidingResult buildSiding(
            ServerLevel level,
            BlockPos sidingOrigin,
            Direction parentDir,
            boolean placeLeft,
            int sidingStraightLength,
            UUID headId) {

        int radius = RailwaysUntoldConfig.getMinCurveRadius();
        int diagLen = SIDING_SCURVE_DIAGONAL_LENGTH;

        // Calculate positions
        BlockPos scurveOutEnd = SCurve45Geometry.calculateEndpoint(
                sidingOrigin, parentDir, placeLeft, radius, diagLen, 0);
        BlockPos straightEnd = scurveOutEnd.relative(parentDir, sidingStraightLength);
        BlockPos scurveBackEnd = SCurve45Geometry.calculateEndpoint(
                straightEnd, parentDir, !placeLeft, radius, diagLen, 0);

        // Pin the entire siding footprint so autoload's release sweep can't unload it mid-build
        // (which reverts the just-placed track to air and fails the reconnect/connections). Force-
        // loads any chunk that was already unloaded; the chunk-load checks below then retry until
        // resident. Re-pinned on every attempt.
        Direction lateralDir = DirectionUtil.getPerpendicularDirection(parentDir, placeLeft);
        int lateralExtent = calculateLateralDisplacement(radius, diagLen);
        pinSidingFootprint(level, sidingOrigin, scurveBackEnd, lateralDir, lateralExtent);

        // Verify chunks are loaded for entire siding footprint
        if (!ChunkVerificationUtil.areBoundingBoxChunksLoaded(level, sidingOrigin, scurveBackEnd)) {
            return SidingResult.needsRetry();
        }

        // Also check the lateral extent (the S-curve goes sideways)
        BlockPos lateralCheck = sidingOrigin.relative(lateralDir, lateralExtent);
        if (!ChunkVerificationUtil.areBoundingBoxChunksLoaded(level, sidingOrigin, lateralCheck)) {
            return SidingResult.needsRetry();
        }

        List<Object> allConnections = new ArrayList<>();

        // 0. Remove any decking_end wall blocks along the siding path that would obstruct new track
        clearDeckingEndWalls(level, sidingOrigin, scurveBackEnd, parentDir, placeLeft, radius, diagLen);

        // 1. Place SCurve OUT (diverge from parent). SCurve45TrackPlacer self-cleans on failure.
        SCurve45TrackPlacer.SCurve45Result outResult = SCurve45TrackPlacer.place(
                level, sidingOrigin, parentDir, radius, diagLen, placeLeft, 0, headId);

        if (outResult.needsRetry) {
            return SidingResult.needsRetry();
        }
        if (!outResult.success) {
            LOGGER.warn("[SIDING] SCurve OUT failed at {}", sidingOrigin);
            return SidingResult.failed();
        }
        allConnections.addAll(outResult.connections);

        // 2. Place straight section
        BezierPlacementRequest straightRequest = BezierPlacementRequest.builder(
                        scurveOutEnd, straightEnd, parentDir)
                .config(RailwaysUntoldConfig.getDefault())
                .build();
        BezierConnectionPlacer.BezierConnectionResult straightResult = BezierConnectionPlacer.place(level, straightRequest);

        if (!straightResult.success) {
            boolean retry = straightResult.needsRetry;
            LOGGER.warn("[SIDING] straight section failed (needsRetry={}) at {} - rolling back siding", retry, scurveOutEnd);
            rollbackSCurve(level, sidingOrigin, parentDir, placeLeft, radius, diagLen);
            rollbackStraightSection(level, scurveOutEnd, straightEnd, parentDir);
            return retry ? SidingResult.needsRetry() : SidingResult.failed();
        }

        // 3. Place SCurve BACK (reconnect to parent). SCurve45TrackPlacer self-cleans on failure.
        SCurve45TrackPlacer.SCurve45Result backResult = SCurve45TrackPlacer.place(
                level, straightEnd, parentDir, radius, diagLen, !placeLeft, 0, headId);

        if (!backResult.success) {
            boolean retry = backResult.needsRetry;
            LOGGER.warn("[SIDING] BACK reconnect failed (needsRetry={}) at {} - rolling back siding (OUT curve + straight section)", retry, straightEnd);
            rollbackSCurve(level, sidingOrigin, parentDir, placeLeft, radius, diagLen);
            rollbackStraightSection(level, scurveOutEnd, straightEnd, parentDir);
            return retry ? SidingResult.needsRetry() : SidingResult.failed();
        }
        allConnections.addAll(backResult.connections);

        // 4. Clear terrain along the siding's straight section
        DeferredTerrainClearer.queueStraightClearing(level, scurveOutEnd, straightEnd, parentDir, null);

        // 4b. Clear terrain along S-curve sections (bezier curves)
        queueSCurveClearing(level, outResult.connections, sidingOrigin, scurveOutEnd);
        queueSCurveClearing(level, backResult.connections, straightEnd, scurveBackEnd);

        // 5. Clear transition areas at both junction points on parent track
        clearTransitionArea(level, sidingOrigin, parentDir);
        clearTransitionArea(level, scurveBackEnd, parentDir);

        // 6. Place girders under the entire straight section
        for (int i = 0; i <= sidingStraightLength; i++) {
            BlockPos pos = scurveOutEnd.relative(parentDir, i);
            if (ChunkVerificationUtil.areGirderChunksLoaded(level, pos)) {
                CreateTrackUtil.placeGirdersUnderStraightTrack(level, pos, parentDir);
            }
        }

        // 7. Place bridge decking and pillars under the entire siding if on a bridge
        placeBridgeSupportsForSiding(level, scurveOutEnd, parentDir, sidingStraightLength,
                outResult.connections, sidingOrigin, backResult.connections, scurveBackEnd, lateralDir);


        return SidingResult.succeeded();
    }

    /**
     * Calculates the total forward distance consumed by a siding.
     */
    public static int calculateTotalForwardDistance(int sidingStraightLength) {
        int radius = RailwaysUntoldConfig.getMinCurveRadius();
        int diagLen = SIDING_SCURVE_DIAGONAL_LENGTH;
        int scurveForward = calculateScurveForwardDistance(radius, diagLen);
        return 2 * scurveForward + sidingStraightLength;
    }

    /**
     * Calculates the forward distance of a single S-curve.
     */
    private static int calculateScurveForwardDistance(int radius, int diagLen) {
        int curveForward = 2 * (int) Math.ceil(radius * SCurve45Geometry.SIN_45);
        int diagForward = (int) Math.ceil(diagLen * SCurve45Geometry.COS_45);
        return curveForward + diagForward;
    }

    /**
     * Calculates the lateral displacement of a single S-curve.
     */
    static int calculateLateralDisplacement(int radius, int diagLen) {
        int curveLateral = 2 * (int) Math.ceil(radius * (1 - SCurve45Geometry.COS_45));
        int diagLateral = (int) Math.ceil(diagLen * SCurve45Geometry.SIN_45);
        return curveLateral + diagLateral;
    }

    /**
     * Clears blocks in the transition area around a junction point.
     */
    private static void clearTransitionArea(ServerLevel level, BlockPos transitionPos, Direction direction) {
        int horizontalRadius = RailwaysUntoldConfig.getHorizontalExpansion();
        int verticalHeight = RailwaysUntoldConfig.getVerticalExpansion();
        Direction perpDir = DirectionUtil.getLeftDirection(direction);

        RemovalUtil.ClearingContext ctx = new RemovalUtil.ClearingContext(level);

        clearAreaSlice(ctx, transitionPos, perpDir, horizontalRadius, verticalHeight);
        clearAreaSlice(ctx, transitionPos.relative(direction, 1), perpDir, horizontalRadius, verticalHeight);

        ctx.finish();
    }

    /**
     * Queues bezier terrain clearing for each connection in an S-curve.
     */
    private static void queueSCurveClearing(ServerLevel level, List<Object> connections,
                                             BlockPos start, BlockPos end) {
        if (connections.isEmpty()) {
            return;
        }
        ClearingTypes.ClearingRequest request = ClearingTypes.ClearingRequest.builder(start, RailwaysUntoldConfig.getDefault())
                .villageBounds(ClearingTypes.findNearbyVillageBounds(level, start, end))
                .build();
        for (Object connection : connections) {
            DeferredTerrainClearer.queueBezierClearing(level, connection, request, null);
        }
    }

    private static void clearAreaSlice(RemovalUtil.ClearingContext ctx, BlockPos center,
                                       Direction perpDir, int horizontalRadius, int verticalHeight) {
        for (int y = -1; y <= verticalHeight; y++) {
            int effectiveRadius = (y == -1) ? 1 : horizontalRadius;
            for (int perpOffset = -effectiveRadius; perpOffset <= effectiveRadius; perpOffset++) {
                BlockPos clearPos = center.relative(perpDir, perpOffset).above(y);
                if (y == -1) {
                    ctx.clearBlockPreserveWater(clearPos);
                } else {
                    ctx.clearBlock(clearPos);
                }
            }
        }
    }

    /**
     * Removes a siding track block as part of rollback after partial construction failure.
     * Only removes if the block is actually a track block (avoids clearing parent track or terrain).
     */
    private static void rollbackTrackBlock(ServerLevel level, BlockPos pos) {
        BlockState state = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, pos);
        if (state != null && CreateTrackUtil.isTrackBlock(state)) {
            level.removeBlock(pos, false);
        }
    }

    /**
     * Pins every chunk in the siding's footprint (the box spanning {@code a}..{@code b} plus the
     * lateral excursion) with the self-expiring {@link #SIDING_PIN_TICKET}, keeping the area loaded
     * through the build and connection resolution so autoload can't unload it mid-flight.
     */
    private static void pinSidingFootprint(ServerLevel level, BlockPos a, BlockPos b,
                                           Direction lateralDir, int lateralExtent) {
        BlockPos aLat = a.relative(lateralDir, lateralExtent);
        BlockPos bLat = b.relative(lateralDir, lateralExtent);
        int minX = Math.min(Math.min(a.getX(), b.getX()), Math.min(aLat.getX(), bLat.getX()));
        int maxX = Math.max(Math.max(a.getX(), b.getX()), Math.max(aLat.getX(), bLat.getX()));
        int minZ = Math.min(Math.min(a.getZ(), b.getZ()), Math.min(aLat.getZ(), bLat.getZ()));
        int maxZ = Math.max(Math.max(a.getZ(), b.getZ()), Math.max(aLat.getZ(), bLat.getZ()));
        var chunkSource = level.getChunkSource();
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                ChunkPos cp = new ChunkPos(cx, cz);
                chunkSource.addRegionTicket(SIDING_PIN_TICKET, cp, 1, cp);
            }
        }
    }

    /**
     * Removes every track block along the siding's straight section (scurveOutEnd -> straightEnd).
     * Removing only straightEnd would leave the straight middle as a disconnected
     * stub parallel to the parent line when a later leg (BACK reconnect) fails.
     */
    private static void rollbackStraightSection(ServerLevel level, BlockPos scurveOutEnd,
                                                BlockPos straightEnd, Direction parentDir) {
        int length = Math.abs(straightEnd.getX() - scurveOutEnd.getX())
                + Math.abs(straightEnd.getZ() - scurveOutEnd.getZ());
        for (int i = 0; i <= length; i++) {
            rollbackTrackBlock(level, scurveOutEnd.relative(parentDir, i));
        }
    }

    /**
     * Removes the track blocks placed by a successful SCurve45 placement. Preserves the
     * start position (parent track) and only deletes positions that still hold a track block.
     */
    private static void rollbackSCurve(ServerLevel level, BlockPos start, Direction parentDir,
                                        boolean shiftLeft, int radius, int diagLen) {
        SCurve45TrackPlacer.rollbackPlacement(level, start, parentDir, radius, diagLen, shiftLeft, 0);
    }

    /**
     * Places bridge decking and pillars under the entire siding (S-curves and straight section),
     * if the track is on a bridge (over water or elevated).
     */
    private static void placeBridgeSupportsForSiding(ServerLevel level, BlockPos straightStart,
                                                      Direction parentDir, int straightLength,
                                                      List<Object> outConnections, BlockPos sidingOrigin,
                                                      List<Object> backConnections,
                                                      BlockPos scurveBackEnd, Direction lateralDir) {
        Vec3 tangent = Vec3.atLowerCornerOf(parentDir.getNormal());
        BridgeSchematicPlacer bridgePlacer = new BridgeSchematicPlacer();
        // Resolve biome settings up front so addStraightTrackExclusion uses the configured half-width.
        bridgePlacer.init(level, sidingOrigin);
        boolean usedBridge = false;

        // Exclude parent track corridor at both junction points so S-curve decking
        // doesn't overlap the parent track's decking
        bridgePlacer.addStraightTrackExclusion(sidingOrigin, parentDir, PlacementConstants.STANDARD_SEGMENT_LENGTH);
        bridgePlacer.addStraightTrackExclusion(scurveBackEnd, parentDir, PlacementConstants.STANDARD_SEGMENT_LENGTH);

        // Suppress railing tops at the two fork points and keep pillars/portals out of them, so the
        // siding's diverge/reconnect doesn't wall off the parent with clashing rails.
        bridgePlacer.addJunctionZone(sidingOrigin, 3, parentDir, lateralDir);
        bridgePlacer.addPillarExclusion(sidingOrigin, SupportPlacementService.JUNCTION_PILLAR_CLEARANCE_RADIUS);
        bridgePlacer.addJunctionZone(scurveBackEnd, 3, parentDir, lateralDir);
        bridgePlacer.addPillarExclusion(scurveBackEnd, SupportPlacementService.JUNCTION_PILLAR_CLEARANCE_RADIUS);

        // Place bridge supports along the OUT S-curve (diverge from parent)
        if (placeBridgeSupportsForSCurve(level, outConnections, bridgePlacer)) {
            usedBridge = true;
        }

        // Place bridge supports along the straight section
        for (int i = 0; i <= straightLength; i++) {
            BlockPos trackPos = straightStart.relative(parentDir, i);
            if (SupportPlacementService.shouldUseBridgeDecking(level, trackPos)) {
                bridgePlacer.placeDeckingAt(level, trackPos, tangent);
                usedBridge = true;
            }
        }

        // Place bridge supports along the BACK S-curve (reconnect to parent)
        if (placeBridgeSupportsForSCurve(level, backConnections, bridgePlacer)) {
            usedBridge = true;
        }

        if (usedBridge) {
            // Piers come from the railing-pattern boundaries the placer collected during decking.
            for (Vec3[] pier : bridgePlacer.getPillarBoundaries()) {
                bridgePlacer.placePillarAt(level, pier[0], pier[1]);
            }
            bridgePlacer.updateConnections(level);
        }
    }

    /**
     * Places bridge decking and pillars along an S-curve's bezier connections.
     * Returns true if any bridge decking was placed.
     */
    private static boolean placeBridgeSupportsForSCurve(ServerLevel level, List<Object> connections,
                                                         BridgeSchematicPlacer bridgePlacer) {
        boolean usedBridge = false;

        for (Object connection : connections) {
            List<BezierSegmentData> segments = BezierSegmentExtractor.extractSegments(connection);
            if (segments.isEmpty()) continue;

            BezierSegmentExtractor.BezierEndpoints endpoints = BezierSegmentExtractor.extractEndpoints(connection);
            if (endpoints == null) continue;

            Vec3 origin = Vec3.atLowerCornerOf(endpoints.first());

            // Build track footprint to prevent decking from overlapping with the track curve
            bridgePlacer.buildTrackFootprint(segments, origin);

            // Collect bridge segments for batch curve decking placement.
            List<BezierSegmentData> bridgeSegments = new ArrayList<>();

            // Place decking at the start endpoint (bezier sampling can miss it)
            if (SupportPlacementService.shouldUseBridgeDecking(level, endpoints.first())) {
                Vec3 firstDeriv = segments.get(0).derivative();
                if (firstDeriv == null) firstDeriv = new Vec3(1, 0, 0);
                bridgePlacer.placeDeckingAt(level, endpoints.first(), firstDeriv);
                usedBridge = true;
            }

            for (BezierSegmentData segment : segments) {
                Vec3 worldPos = segment.position().add(origin);
                BlockPos trackPos = BlockPos.containing(worldPos);

                if (SupportPlacementService.shouldUseBridgeDecking(level, trackPos)) {
                    bridgeSegments.add(segment);
                    usedBridge = true;
                }
            }

            // Batch place decking along curve (handles proper perpendicular calculation).
            // Piers are collected as railing-pattern boundaries and placed by the caller.
            if (!bridgeSegments.isEmpty()) {
                bridgePlacer.placeDeckingAlongCurve(level, bridgeSegments, origin);
            }

            // Place decking at the end endpoint (bezier sampling can miss it)
            if (SupportPlacementService.shouldUseBridgeDecking(level, endpoints.second())) {
                Vec3 lastDeriv = segments.get(segments.size() - 1).derivative();
                if (lastDeriv == null) lastDeriv = new Vec3(1, 0, 0);
                bridgePlacer.placeDeckingAt(level, endpoints.second(), lastDeriv);
                usedBridge = true;
            }
        }

        return usedBridge;
    }

    /**
     * Removes decking_end wall blocks along the siding path that would obstruct new track.
     */
    private static void clearDeckingEndWalls(ServerLevel level, BlockPos sidingOrigin,
                                              BlockPos scurveBackEnd, Direction parentDir,
                                              boolean placeLeft, int radius, int diagLen) {
        Direction lateralDir = DirectionUtil.getPerpendicularDirection(parentDir, placeLeft);
        int lateralDisplacement = calculateLateralDisplacement(radius, diagLen);

        // Calculate the forward span of the entire siding
        int forwardDist = Math.abs(
                (scurveBackEnd.getX() - sidingOrigin.getX()) * parentDir.getStepX() +
                (scurveBackEnd.getZ() - sidingOrigin.getZ()) * parentDir.getStepZ());

        // Clear wall blocks along the lateral edge where the siding diverges
        // Scan from origin to reconnect point, at each lateral offset the S-curve might cross
        for (int forward = 0; forward <= forwardDist; forward++) {
            BlockPos basePos = sidingOrigin.relative(parentDir, forward);
            for (int lateral = 1; lateral <= lateralDisplacement + 1; lateral++) {
                BlockPos checkPos = basePos.relative(lateralDir, lateral);
                // Check at track level and decking level
                for (int dy = -1; dy <= 0; dy++) {
                    BlockPos wallPos = checkPos.above(dy);
                    BlockState state = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, wallPos);
                    if (state != null && !state.isAir()
                            && !CreateTrackUtil.isTrackBlock(state)
                            && !CreateTrackUtil.isGirderBlock(state)
                            && isConnectable(state)) {
                        level.removeBlock(wallPos, false);
                    }
                }
            }
        }
    }

    private static boolean isConnectable(BlockState state) {
        return state.getBlock() instanceof net.minecraft.world.level.block.WallBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.FenceBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock;
    }
}
