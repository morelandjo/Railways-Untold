package com.vodmordia.railwaysuntold.worldgen.placement.handler;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil;
import com.vodmordia.railwaysuntold.worldgen.head.TrackExpansionHead;
import com.vodmordia.railwaysuntold.worldgen.placement.analyzer.CrossingDetectionService;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.BezierConnectionPlacer;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.BezierPlacementRequest;
import com.vodmordia.railwaysuntold.worldgen.placement.support.SupportResult;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.AtGradeCrossingRegistry;
import com.vodmordia.railwaysuntold.worldgen.terrain.clearing.ClearingTypes;
import com.vodmordia.railwaysuntold.worldgen.terrain.clearing.DeferredTerrainClearer;
import com.vodmordia.railwaysuntold.worldgen.tracking.BoundarySegmentData.ConnectedSegment;
import com.vodmordia.railwaysuntold.worldgen.tracking.ConnectedBoundaryTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Base class for placement executors.
 */
public abstract class AbstractPlacementExecutor implements PlacementExecutor {

    protected static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Drift guard for executors that place a segment from a PLANNER-supplied start (bezier, bridge,
     * tunnel, elevated-tunnel, curve). If that start has drifted from the head's live tip, placing it
     * lays a disconnected stub off the tip; instead, fail so the orchestrator replans from the true tip.
     * Returns a failed result to return immediately, or empty to proceed. Executors anchored to
     * {@code ctx.currentPos()} (straight, scurve45, diagonal) can't drift off the tip and don't call this.
     */
    protected Optional<HandlerResult> plannerDriftGuard(PlacementContext ctx, BlockPos plannedStart, String label) {
        BlockPos liveHead = ctx.head().getPosition();
        if (plannedStart != null && !plannedStart.equals(liveHead)) {
            // Self-healing: the head halts and replans from its true tip. Verbose-only so the normal log
            // isn't filled by a recovered halt; a head that can't recover still surfaces as [HEAD-FAIL].
            if (com.vodmordia.railwaysuntold.util.core.TrackLogger.isVerboseEnabled()) {
                LOGGER.warn("[PLANNER-DRIFT] Head {} live pos {} != planner start {} - upstream placer drifted, halting {}",
                        ctx.head().getHeadNumber(), liveHead, plannedStart, label);
            }
            return Optional.of(HandlerResult.failed("planner start drift (" + label + ")"));
        }
        return Optional.empty();
    }

    /** Max forward drift (blocks) re-anchored to the live tip rather than halted. A mid-route branch
     *  junction shifts the head ~16 forward; beyond a segment span a forward drift is suspicious. */
    protected static final int MAX_REANCHOR_DRIFT = 48;

    /**
     * Resolves the start a PLANNER-anchored segment should actually place from when the head's live tip
     * has drifted off the planner's start. A small PURELY-FORWARD drift (the head is a bit further along
     * its own heading than the stale compiled start - e.g. a branch junction was inserted between the
     * compiled segments, advancing the tip) is re-anchored to the live tip: placing from where the head
     * actually is connects to the real track and avoids the halt->replan loop the drift guard otherwise
     * spins (which can strand the head idle). Returns the live tip to re-anchor, the planner start when
     * there's no drift, or {@code null} for a genuine drift (lateral, backward, past-the-end, or beyond
     * {@link #MAX_REANCHOR_DRIFT}) - the caller then halts and replans via {@link #plannerDriftGuard}.
     */
    protected BlockPos reanchorForwardDrift(PlacementContext ctx, BlockPos plannedStart, BlockPos plannedEnd,
                                            Direction dir, String label) {
        BlockPos live = ctx.head().getPosition();
        BlockPos resolved = resolveForwardReanchor(plannedStart, live, plannedEnd, dir);
        if (resolved != null && resolved.equals(live) && !live.equals(plannedStart)
                && com.vodmordia.railwaysuntold.util.core.TrackLogger.isVerboseEnabled()) {
            int forward = (live.getX() - plannedStart.getX()) * dir.getStepX()
                    + (live.getZ() - plannedStart.getZ()) * dir.getStepZ();
            LOGGER.info("[DRIFT-REANCHOR] Head {} {}: live tip {} is {} ahead of stale planner start {} - re-anchoring to the tip",
                    ctx.head().getHeadNumber(), label, live, forward, plannedStart);
        }
        return resolved;
    }

    /**
     * Pure geometry for {@link #reanchorForwardDrift}: {@code plannedStart} when the live tip hasn't
     * drifted, {@code live} to re-anchor a small purely-forward drift (with room left to the end), or
     * {@code null} for a genuine drift (lateral, backward, past-the-end, or beyond
     * {@link #MAX_REANCHOR_DRIFT}). World-free so it can be unit-tested directly.
     */
    static BlockPos resolveForwardReanchor(BlockPos plannedStart, BlockPos live, BlockPos plannedEnd, Direction dir) {
        if (plannedStart == null || live.equals(plannedStart)) {
            return plannedStart;
        }
        int dx = live.getX() - plannedStart.getX();
        int dz = live.getZ() - plannedStart.getZ();
        int forward = dx * dir.getStepX() + dz * dir.getStepZ();
        int lateral = Math.abs(dx * dir.getStepZ() - dz * dir.getStepX());
        boolean forwardOnly = lateral == 0 && forward > 0 && forward <= MAX_REANCHOR_DRIFT;
        boolean roomToEnd = plannedEnd == null
                || ((plannedEnd.getX() - live.getX()) * dir.getStepX()
                  + (plannedEnd.getZ() - live.getZ()) * dir.getStepZ()) > 0;
        return forwardOnly && roomToEnd ? live : null;
    }

    /**
     * Applies a SupportResult to the head, updating position, direction, and counters.
     *
     * @param head   The expansion head to update
     * @param result The strategy result containing endpoint and metadata
     */
    protected void applySupportResult(TrackExpansionHead head, SupportResult result) {
        applyStateUpdateCore(head, result.endpoint, result.endDirection, result.blocksTraveled,
                result.isEmergencyCurve, result.shouldResetCurveCounter);
    }

    /**
     * Applies state updates from a BezierConnectionResult.
     *
     * @param head     The expansion head to update
     * @param endpoint The final position after placement
     * @param distance The distance traveled
     */
    protected void applyBasicStateUpdate(TrackExpansionHead head, BlockPos endpoint, int distance) {
        applyStateUpdateCore(head, endpoint, null, distance, false, false);
    }

    /**
     * Core state update logic.
     *
     * @param head              The expansion head to update
     * @param endpoint          The final position after placement
     * @param newDirection      New direction (null to keep current)
     * @param distance          The distance traveled
     * @param isEmergencyCurve  Whether this was an emergency curve
     * @param resetCurveCounter Whether to reset the curve counter
     */
    private void applyStateUpdateCore(TrackExpansionHead head, BlockPos endpoint,
                                      Direction newDirection, int distance,
                                      boolean isEmergencyCurve, boolean resetCurveCounter) {
        head.setPosition(endpoint);
        if (newDirection != null) {
            head.setDirection(newDirection);
        }
        head.incrementBlocksSinceLastCurve(distance);
        head.incrementBlocksSinceLastLateral(distance);
        head.incrementBranchDistance(distance);
        head.incrementEventDistance(distance);
        com.vodmordia.railwaysuntold.worldgen.placement.AutoLoadController.addTrackBlocks(distance);
        head.setLastPlacementWasEmergencyCurve(isEmergencyCurve);
        // After the first successful placement the head owns a track tip. Any future
        // replan from this head must respect the tangent there (see
        // CoarseRouteFactory.createAndAttach / PrecisionRouteCompiler first-segment
        // handling).
        head.markHasPlacedSegments();

        if (resetCurveCounter) {
            head.resetBlocksSinceLastCurve();
        }
    }

    /**
     * Creates a BezierPlacementRequest builder with common options pre-configured.
     *
     * @param ctx       The placement context
     * @param start     Start position
     * @param end       End position
     * @param direction Track direction
     * @return A pre-configured builder
     */
    protected BezierPlacementRequest.Builder createBezierRequestBuilder(
            PlacementContext ctx, BlockPos start, BlockPos end, Direction direction) {
        TrackExpansionHead head = ctx.head();
        return BezierPlacementRequest.builder(start, end, direction)
                .config(ctx.config())
                .torchOffset(head.getTorchPlacementOffset())
                .logContext(ctx.decision().getLogContext())
                .headId(head.getHeadId())
                .onClearingComplete(head::addToTorchPlacementOffset);
    }

    /**
     * Result that includes both the handler result and the bezier connection result.
     */
    protected record BezierPlacementOutcome(
            HandlerResult handlerResult,
            BezierConnectionPlacer.BezierConnectionResult bezierResult,
            BlockPos startPos,
            int distance,
            Direction startDirection
    ) {}

    /**
     * Places a bezier and updates head state, returning both results.
     * Used by subclasses that need access to bezier segments for custom support placement.
     */
    protected BezierPlacementOutcome placeBezierWithResult(
            PlacementContext ctx,
            BezierPlacementRequest request,
            String operationName) {

        if (hasCollisionWithExistingTrack(ctx, request.start, request.end)) {
            LOGGER.warn("[{}] Collision detected at {} -> {}, aborting placement",
                    operationName, request.start, request.end);
            return new BezierPlacementOutcome(
                    HandlerResult.failed(operationName + " blocked by existing track"),
                    BezierConnectionPlacer.BezierConnectionResult.failure(),
                    request.start, 0, request.startDirection);
        }

        BezierConnectionPlacer.BezierConnectionResult result = BezierConnectionPlacer.place(ctx.level(), request);

        if (result.needsRetry) {
            return new BezierPlacementOutcome(
                    deferForBoundingBox(request.start, request.end, operationName), result, request.start,
                    0, request.startDirection);
        }

        if (!result.success) {
            return new BezierPlacementOutcome(
                    HandlerResult.failed(operationName + " placement failed"), result, request.start,
                    0, request.startDirection);
        }

        BlockPos endpoint = result.actualEndpoint != null ? result.actualEndpoint : request.end;
        int distance = result.actualEndpoint != null
                ? com.vodmordia.railwaysuntold.util.spatial.DirectionUtil.calculateDirectionalDistance(
                        request.start, endpoint, request.startDirection)
                : request.getDistance();
        applyStateUpdateCore(ctx.head(), endpoint, request.endDirection, distance, false, false);

        return new BezierPlacementOutcome(HandlerResult.succeeded(), result, request.start,
                distance, request.startDirection);
    }

    /**
     * Places a bezier and updates head state.
     *
     * @param ctx           The placement context
     * @param request       The bezier placement request
     * @param operationName Name for logging and deferral
     * @param placeSupports If true, places supports along the bezier
     * @return HandlerResult indicating success or deferral
     */
    protected HandlerResult placeBezierAndUpdateState(
            PlacementContext ctx,
            BezierPlacementRequest request,
            String operationName,
            boolean placeSupports) {

        if (hasCollisionWithExistingTrack(ctx, request.start, request.end)) {
            LOGGER.warn("[{}] Collision detected at {} -> {}, aborting placement",
                    operationName, request.start, request.end);
            return HandlerResult.failed(operationName + " blocked by existing track");
        }

        BezierConnectionPlacer.BezierConnectionResult result = BezierConnectionPlacer.place(ctx.level(), request);

        if (result.needsRetry) {
            return deferForBoundingBox(request.start, request.end, operationName);
        }

        if (!result.success) {
            return HandlerResult.failed(operationName + " placement failed");
        }

        // Use actual endpoint from straight-line fallback when available.
        BlockPos endpoint = result.actualEndpoint != null ? result.actualEndpoint : request.end;
        int distance = result.actualEndpoint != null
                ? com.vodmordia.railwaysuntold.util.spatial.DirectionUtil.calculateDirectionalDistance(
                        request.start, endpoint, request.startDirection)
                : request.getDistance();
        applyStateUpdateCore(ctx.head(), endpoint, request.endDirection, distance, false, false);

        // Track outgoing slope for smooth normal matching at the next segment junction
        int horizDist = Math.abs(endpoint.getX() - request.start.getX())
                + Math.abs(endpoint.getZ() - request.start.getZ());
        double slope = (horizDist > 0) ? (double)(endpoint.getY() - request.start.getY()) / horizDist : 0.0;
        ctx.head().setLastSegmentSlope(slope);

        if (placeSupports) {
            if (!result.segments.isEmpty()) {
                SupportPlacementService.placeSupportsAlongBezier(ctx, result, request.start);
            } else if (distance > 0) {
                // Straight-line fallback: bezier was placed as straight track, no segments returned.
                // Use straight support placement so supports aren't skipped entirely.
                SupportPlacementService.placeSupportsAlongStraight(ctx, request.start, request.startDirection, distance);
            }
        }

        return HandlerResult.succeeded();
    }

    /**
     * Validates that chunks are loaded and no collision exists before placement.
     * Returns an empty Optional if validation passes, or a HandlerResult to return early.
     */
    protected Optional<HandlerResult> validatePrePlacement(
            PlacementContext ctx, BlockPos start, BlockPos end, String operationName) {
        if (!com.vodmordia.railwaysuntold.util.chunk.ChunkVerificationUtil.areBoundingBoxChunksLoaded(ctx.level(), start, end)) {
            return Optional.of(deferForBoundingBox(start, end, operationName));
        }
        if (hasCollisionWithExistingTrack(ctx, start, end)) {
            LOGGER.warn("[{}] Collision detected at {} -> {}, aborting", operationName, start, end);
            return Optional.of(HandlerResult.failed(operationName + " blocked by existing track"));
        }
        return Optional.empty();
    }

    /**
     * Rail-to-rail vertical clearance at which a CROSSING counts as grade-separated rather than colliding.
     * The detector's generic clearance is the terrain-clearing envelope (verticalExpansion + buffer ≈ 7) -
     * appropriate for keeping a track clear of overhead terrain and for parallel tracks (which overlap
     * along a whole run), but stricter than a crossing needs: the lower line's train passes through the
     * meeting region under a bridge deck. A grade-separated over/under planned by
     * {@link com.vodmordia.railwaysuntold.worldgen.planner.noise.PlacedTrackConflictDetector} targets exactly
     * that envelope, so interpolation jitter at the crossing leaves it a hair under the limit and the
     * over/under bridge gets rejected to an at-grade junction. Allow a crossing (perpendicular OR diagonal)
     * that clears the existing line by at least this many blocks.
     */
    private static final int GRADE_SEPARATED_CROSSING_CLEARANCE = 5;

    /**
     * Checks for collision between a proposed segment and existing track segments,
     * excluding segments belonging to the same head.
     */
    protected boolean hasCollisionWithExistingTrack(PlacementContext ctx, BlockPos start, BlockPos end) {
        Set<ChunkPos> affectedChunks = ConnectedBoundaryTracker.getChunksSpannedBySegment(start, end);
        java.util.UUID headId = ctx.head().getHeadId();

        for (ChunkPos chunk : affectedChunks) {
            List<ConnectedSegment> segments = ConnectedBoundaryTracker.getSegmentsInChunk(ctx.level(), chunk);
            for (ConnectedSegment existing : segments) {
                if (existing.headId != null && existing.headId.equals(headId)) {
                    continue;
                }
                CrossingDetectionService.CrossingInfo collision =
                        CrossingDetectionService.checkSegmentIntersection(
                                start, end, ctx.expandDir(), existing);
                if (collision != null && !isPassableCrossing(ctx, collision)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * True if the detected crossing is a clean grade-separated crossing - the proposed track passes over
     * or under the existing line with enough rail-to-rail clearance for the lower line's train to pass
     * beneath the upper structure. Applies to a perpendicular crossing OR a diagonal line (which has no
     * cardinal direction) meeting the route at an angle. A parallel/collinear overlap (same cardinal axis)
     * is NOT a crossing - it runs alongside for a whole span - so it keeps the stricter terrain envelope.
     */
    static boolean isGradeSeparatedCrossing(CrossingDetectionService.CrossingInfo info) {
        return isAtGradeCrossable(info)
                && Math.abs(info.proposedTrackY() - info.existingTrackY()) >= GRADE_SEPARATED_CROSSING_CLEARANCE;
    }

    /**
     * True if the collision is a genuine crossing - a perpendicular line, or a diagonal one (which has no
     * cardinal direction) - as opposed to a parallel/collinear overlap that runs alongside for a whole span.
     * A crossing meets the existing line at a point, so it can pass through it (Create forms a crossing);
     * a parallel overlap cannot and must never be placed through. This is the crossing test without the
     * vertical-clearance requirement, so it admits an at-grade pass-through a grade-separated crossing won't.
     */
    static boolean isAtGradeCrossable(CrossingDetectionService.CrossingInfo info) {
        return info.isPerpendicular() || info.existingDirection() == null;
    }

    /**
     * True if a proposed segment may pass through this collision rather than treating it as a blocking
     * obstacle: either it clears the line with grade separation, or the head has been permitted to cross at
     * grade ({@link AtGradeCrossingRegistry}) and the collision is a genuine crossing the pass-through can form.
     */
    protected boolean isPassableCrossing(PlacementContext ctx, CrossingDetectionService.CrossingInfo collision) {
        if (isGradeSeparatedCrossing(collision)) {
            return true;
        }
        return AtGradeCrossingRegistry.isAllowed(ctx.head().getHeadId()) && isAtGradeCrossable(collision);
    }

    /** Minimum forward run worth placing when a segment is capped short of an obstacle; below this the
     *  head is effectively at the obstacle and should resolve the crossing/junction there instead. */
    protected static final int MIN_CAPPED_ADVANCE = 8;

    /** Clearance left between a capped segment's end and the obstacle. Kept above the crossing detector's
     *  horizontal clearance so the shortened segment does not itself read as colliding. */
    protected static int obstacleStopMargin() {
        return RailwaysUntoldConfig.getHorizontalExpansion() + 5;
    }

    /**
     * The nearest point along {@code start->end} where foreign placed track crosses it (a real collision,
     * not a grade-separated perpendicular crossing), or null if the span is clear. Lets a long segment cap
     * short of an obstacle and advance to it rather than failing the whole span and stalling far short.
     */
    protected BlockPos nearestTrackCollisionAlong(PlacementContext ctx, BlockPos start, BlockPos end) {
        Set<ChunkPos> affectedChunks = ConnectedBoundaryTracker.getChunksSpannedBySegment(start, end);
        UUID headId = ctx.head().getHeadId();
        BlockPos best = null;
        long bestDistSq = Long.MAX_VALUE;
        for (ChunkPos chunk : affectedChunks) {
            for (ConnectedSegment existing : ConnectedBoundaryTracker.getSegmentsInChunk(ctx.level(), chunk)) {
                if (existing.headId != null && existing.headId.equals(headId)) {
                    continue;
                }
                CrossingDetectionService.CrossingInfo collision = CrossingDetectionService.checkSegmentIntersection(
                        start, end, ctx.expandDir(), existing);
                if (collision == null || isPassableCrossing(ctx, collision)) {
                    continue;
                }
                BlockPos cp = collision.crossingPoint();
                long dx = cp.getX() - start.getX();
                long dz = cp.getZ() - start.getZ();
                long distSq = dx * dx + dz * dz;
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    best = cp;
                }
            }
        }
        return best;
    }

    /**
     * A truncated end for {@code start->end} that stops {@link #obstacleStopMargin()} short of
     * {@code obstacle} along {@code dir}, with Y interpolated proportionally; or null if that leaves less
     * than {@link #MIN_CAPPED_ADVANCE} to place (the head is at the obstacle and should junction there).
     */
    static BlockPos capSpanBeforeObstacle(BlockPos start, BlockPos end, BlockPos obstacle, Direction dir, int stopMargin) {
        int forwardToObstacle = (obstacle.getX() - start.getX()) * dir.getStepX()
                + (obstacle.getZ() - start.getZ()) * dir.getStepZ();
        int advance = forwardToObstacle - stopMargin;
        if (advance < MIN_CAPPED_ADVANCE) {
            return null;
        }
        int fullForward = (end.getX() - start.getX()) * dir.getStepX()
                + (end.getZ() - start.getZ()) * dir.getStepZ();
        if (fullForward <= 0 || advance >= fullForward) {
            return null;
        }
        int cappedY = start.getY() + Math.round((end.getY() - start.getY()) * (advance / (float) fullForward));
        return new BlockPos(start.getX() + dir.getStepX() * advance, cappedY, start.getZ() + dir.getStepZ() * advance);
    }

    /**
     * For a long segment {@code start->end}, the end to actually place to: the original end if the span is
     * clear, a CAPPED end stopping short of the first foreign track crossing it (so the head advances to
     * the obstacle and resolves there), or empty when the obstacle is so close the head should fail and
     * junction into it now. Callers recompute elevation/slope from the returned end.
     */
    protected Optional<BlockPos> cappedEndOrJunction(PlacementContext ctx, BlockPos start, BlockPos end, String label) {
        BlockPos obstacle = nearestTrackCollisionAlong(ctx, start, end);
        if (obstacle == null) {
            return Optional.of(end);
        }
        BlockPos capped = capSpanBeforeObstacle(start, end, obstacle, ctx.expandDir(), obstacleStopMargin());
        if (capped == null) {
            return Optional.empty();
        }
        LOGGER.info("[SEGMENT-CAP] Head {} capping {} {} -> {} short of existing track at {} - new end {}",
                ctx.head().getHeadNumber(), label, start.toShortString(), end.toShortString(),
                obstacle.toShortString(), capped.toShortString());
        return Optional.of(capped);
    }

    /**
     * Creates a ClearingRequest and queues bezier terrain clearing.
     */
    protected void queueBezierClearing(PlacementContext ctx, Object curve, BlockPos start, BlockPos end) {
        RailwaysUntoldConfig config = ctx.config() != null ? ctx.config() : RailwaysUntoldConfig.getDefault();
        ClearingTypes.ClearingRequest clearingRequest = ClearingTypes.ClearingRequest.builder(start, config)
                .torchOffset(ctx.head().getTorchPlacementOffset())
                .villageBounds(ClearingTypes.findNearbyVillageBounds(ctx.level(), start, end))
                .build();
        DeferredTerrainClearer.queueBezierClearing(
                ctx.level(), curve, clearingRequest, ctx.head()::addToTorchPlacementOffset);
    }

    /**
     * Creates a deferred result for chunk loading with 1-block buffer for geometry calculation.
     *
     * @param start         Start position
     * @param end           End position
     * @param operationName Name of the operation for logging
     * @return HandlerResult indicating deferral
     */
    protected HandlerResult deferForBoundingBox(BlockPos start, BlockPos end, String operationName) {
        BlockPos bufferedStart = start.offset(-1, 0, -1);
        BlockPos bufferedEnd = end.offset(1, 0, 1);
        Set<ChunkPos> chunks = ChunkCoordinateUtil.getChunksInBoundingBox(bufferedStart, bufferedEnd);
        return HandlerResult.defer(chunks, operationName);
    }

    /**
     * Creates a deferred result for chunk loading in a directional range.
     *
     * @param start         Start position
     * @param direction     Direction of travel
     * @param distance      Distance to scan
     * @param operationName Name of the operation for logging
     * @return HandlerResult indicating deferral
     */
    protected HandlerResult deferForDirectionalRange(BlockPos start, Direction direction, int distance, String operationName) {
        BlockPos end = start.relative(direction, distance);
        Set<ChunkPos> chunks = ChunkCoordinateUtil.getChunksInBoundingBox(start, end);
        return HandlerResult.defer(chunks, operationName);
    }
}
