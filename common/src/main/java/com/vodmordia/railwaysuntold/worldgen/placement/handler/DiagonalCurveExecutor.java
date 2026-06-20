package com.vodmordia.railwaysuntold.worldgen.placement.handler;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil;
import com.vodmordia.railwaysuntold.util.placement.SCurve45Geometry;
import com.vodmordia.railwaysuntold.util.spatial.DiagonalDirection;
import com.vodmordia.railwaysuntold.worldgen.integration.create.SCurve45TrackPlacer;
import com.vodmordia.railwaysuntold.worldgen.integration.deferred.BlockEntitiesNotReadyException;
import com.vodmordia.railwaysuntold.worldgen.placement.ChunkLoadTrackExpander;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import com.vodmordia.railwaysuntold.worldgen.planner.PathSegment;
import com.vodmordia.railwaysuntold.worldgen.planner.PlannedPath;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.PrecisionRoute;
import com.vodmordia.railwaysuntold.worldgen.tracking.BoundarySegmentData.ConnectionType;
import com.vodmordia.railwaysuntold.worldgen.tracking.ConnectedBoundaryTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Executor for both DIAGONAL_ENTRY and DIAGONAL_EXIT decisions.
 * Places a 45-degree curve and transitions the head into or out of diagonal mode.
 */
public class DiagonalCurveExecutor extends AbstractPlacementExecutor {

    private static final Logger LOGGER = LogUtils.getLogger();

    public enum Mode { ENTRY, EXIT }

    private final Mode mode;

    public DiagonalCurveExecutor(Mode mode) {
        this.mode = mode;
    }

    @Override
    public PlacementDecision.Type getType() {
        return mode == Mode.ENTRY ? PlacementDecision.Type.DIAGONAL_ENTRY : PlacementDecision.Type.DIAGONAL_EXIT;
    }

    @Override
    public HandlerResult handle(PlacementContext ctx) {
        PlacementDecision decision = ctx.decision();
        BlockPos start = ctx.currentPos();
        BlockPos end = decision.getEnd();
        DiagonalDirection diagonal = decision.getDiagonalDirection();
        int radius = decision.getScurve45Radius();
        String label = mode == Mode.ENTRY ? "DiagonalEntry" : "DiagonalExit";

        Optional<HandlerResult> preCheck = validatePrePlacement(ctx, start, end, label);
        if (preCheck.isPresent()) return preCheck.get();

        // Entry-mode atomic trio guard: the diagonal sequence is Entry -> one
        // or more DiagonalStraight -> Exit, placed across multiple ticks with
        // independent collision checks per segment.
        if (mode == Mode.ENTRY) {
            Optional<HandlerResult> trioCheck = checkTrioCollision(ctx, start, label);
            if (trioCheck.isPresent()) return trioCheck.get();
        }

        try {
            Object curve;
            if (mode == Mode.ENTRY) {
                Direction cardinalDir = ctx.expandDir();
                curve = SCurve45TrackPlacer.placeFirst45Curve(ctx.level(), start, cardinalDir, end, diagonal);
            } else {
                Direction exitCardinal = decision.getExitCardinalDirection();
                curve = SCurve45TrackPlacer.placeSecond45Curve(ctx.level(), start, diagonal, end, exitCardinal);
            }

            if (curve == null) {
                return HandlerResult.failed(label + " curve placement failed");
            }

            ConnectedBoundaryTracker.confirmSegment(ctx.level(), start, end, ConnectionType.BEZIER, null);

            queueBezierClearing(ctx, curve, start, end);
            SupportPlacementService.placeSupportsAlongRawBezier(ctx, curve, start);

            int distance = SCurve45Geometry.calculate45CurveArcLength(radius);
            applyBasicStateUpdate(ctx.head(), end, distance);

            if (mode == Mode.ENTRY) {
                ctx.head().enterDiagonalMode(diagonal);
                ctx.head().onDirectionChange();
                ctx.head().resetBlocksSinceLastLateral();
                pinTrioChunks(ctx, start);
            } else {
                ctx.head().setDirection(decision.getExitCardinalDirection());
                ctx.head().exitDiagonalMode();
                ctx.head().resetBlocksSinceLastCurve();
                // Trio is finished - drop the release-sweep exemption.
                ChunkLoadTrackExpander.releaseTrioPin(ctx.head().getHeadId());
            }

            return HandlerResult.succeeded();

        } catch (BlockEntitiesNotReadyException e) {
            return deferForBoundingBox(start, end, label);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.error("[{}] Exception: {}", label.toUpperCase(), e.getMessage());
            return HandlerResult.failed(label + " exception: " + e.getMessage());
        }
    }

    /**
     * Scans the precision route forward from the current DiagonalEntry to
     * locate the matching DiagonalStraight* / DiagonalExit sequence and
     * performs a single collision check spanning the whole trio.
     */
    private Optional<HandlerResult> checkTrioCollision(PlacementContext ctx, BlockPos entryStart, String label) {
        PrecisionRoute route = ctx.head().getTerrainPlanState().getPrecisionRoute();
        if (route == null) return Optional.empty();
        PlannedPath path = route.getPrecisionPath();
        if (path == null || path.segments == null) return Optional.empty();

        List<PathSegment> segments = path.segments;
        int entryIdx = route.getCurrentSegmentIndex();
        if (entryIdx < 0 || entryIdx >= segments.size()) return Optional.empty();
        if (segments.get(entryIdx).type != PathSegment.Type.DIAGONAL_ENTRY) return Optional.empty();

        // Walk forward through DIAGONAL_STRAIGHT segments; stop at the
        // DIAGONAL_EXIT or fall off the end if the route is truncated.
        BlockPos trioEnd = null;
        for (int i = entryIdx + 1; i < segments.size(); i++) {
            PathSegment seg = segments.get(i);
            if (seg.type == PathSegment.Type.DIAGONAL_STRAIGHT) {
                trioEnd = seg.getEndPosition();
                continue;
            }
            if (seg.type == PathSegment.Type.DIAGONAL_EXIT) {
                trioEnd = seg.getEndPosition();
            }
            break;
        }

        // If no downstream segments exist yet (route tail truncated at the
        // Entry) fall back to per-segment checks
        if (trioEnd == null) return Optional.empty();

        if (hasCollisionWithExistingTrack(ctx, entryStart, trioEnd)) {
            LOGGER.warn("[{}] Trio collision detected spanning {} -> {}, aborting before placement",
                    label, entryStart, trioEnd);
            return Optional.of(HandlerResult.failed(label + " diagonal trio blocked by existing track"));
        }
        return Optional.empty();
    }

    /**
     * Pins all chunks in the upcoming DIAGONAL_STRAIGHT* + DIAGONAL_EXIT bounding boxes
     * via {@link ChunkLoadTrackExpander#pinTrioChunks}, so chunky's organic tickets can
     * drop without unloading what the head is about to need.
     *
     */
    private void pinTrioChunks(PlacementContext ctx, BlockPos entryStart) {
        PrecisionRoute route = ctx.head().getTerrainPlanState().getPrecisionRoute();
        if (route == null) return;
        PlannedPath path = route.getPrecisionPath();
        if (path == null || path.segments == null) return;

        List<PathSegment> segments = path.segments;
        int entryIdx = route.getCurrentSegmentIndex();
        if (entryIdx < 0 || entryIdx >= segments.size()) return;

        Set<ChunkPos> chunks = new HashSet<>();
        // Include the entry's footprint too - its end is the start of the first
        // diagonal-straight, which the placer's bounding-box check will cover.
        addBoundingBoxChunks(chunks, entryStart, segments.get(entryIdx).getEndPosition());

        for (int i = entryIdx + 1; i < segments.size(); i++) {
            PathSegment seg = segments.get(i);
            if (seg.type != PathSegment.Type.DIAGONAL_STRAIGHT
                    && seg.type != PathSegment.Type.DIAGONAL_EXIT) {
                break;
            }
            addBoundingBoxChunks(chunks, seg.getStart(), seg.getEndPosition());
            if (seg.type == PathSegment.Type.DIAGONAL_EXIT) {
                break;
            }
        }

        if (!chunks.isEmpty()) {
            ChunkLoadTrackExpander.pinTrioChunks(ctx.level(), ctx.head().getHeadId(), chunks);
        }
    }

    /** Adds the chunks spanned by [start, end] (with a 1-block buffer) to {@code into}. */
    private static void addBoundingBoxChunks(Set<ChunkPos> into, BlockPos start, BlockPos end) {
        BlockPos bufStart = start.offset(-1, 0, -1);
        BlockPos bufEnd = end.offset(1, 0, 1);
        into.addAll(ChunkCoordinateUtil.getChunksInBoundingBox(bufStart, bufEnd));
    }
}
