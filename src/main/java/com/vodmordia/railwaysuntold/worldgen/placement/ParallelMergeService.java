package com.vodmordia.railwaysuntold.worldgen.placement;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.worldgen.head.TrackExpansionHead;
import com.vodmordia.railwaysuntold.worldgen.integration.create.CreateTrackConnector;
import com.vodmordia.railwaysuntold.worldgen.tracking.BoundarySegmentData.ConnectedSegment;
import com.vodmordia.railwaysuntold.worldgen.tracking.ConnectedBoundaryTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Detects when a head is running PARALLEL and adjacent to another head's already-placed track and merges
 * the head into it, instead of letting the two lines run side-by-side (which produces strange artifacts).
 * The merge reuses {@link CreateTrackConnector#connectTracks} (the same primitive sidings/junctions use):
 * it curves the head's tip across into the nearest physical block of the parallel line and joins the two
 * track graphs, then the head completes. Conservative by design - only fires for a foreign line on the
 * head's own axis, offset laterally within a band, level with the head, and right beside it.
 */
public final class ParallelMergeService {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Lateral offset band that counts as "parallel adjacent": closer than MIN is the same/just-merged
     *  line; farther than MAX is not adjacent. */
    private static final int MIN_LATERAL = 2;
    private static final int MAX_LATERAL = 8;
    /** The foreign block must be roughly level with the head (not a track far above/below). */
    private static final int MAX_VERTICAL = 4;

    /** Long-range convergence band: a parallel line farther than the short-range MAX_LATERAL but within
     *  CONVERGE_MAX_LATERAL is steered toward (the head curves over to it, then the short-range merge
     *  finishes the join). Closer than CONVERGE_MIN_LATERAL is the short-range merge's job. */
    private static final int CONVERGE_MIN_LATERAL = MAX_LATERAL + 1;
    private static final int CONVERGE_MAX_LATERAL = 30;
    /** Convergence tolerates a larger vertical gap than the touching merge - the approach route ramps to
     *  the line's Y as it curves over. */
    private static final int CONVERGE_MAX_VERTICAL = 8;
    /** Chunk radius scanned for a convergeable line (covers CONVERGE_MAX_LATERAL plus margin). */
    private static final int CONVERGE_SCAN_CHUNKS = 3;

    /** Before converging, confirm the foreign line is genuinely parallel - not a momentary alignment
     *  (a crossing, a curve tangent, or a short siding) and not a line that curves away before the head
     *  reaches the merge point. The line must still carry track at the same lateral all the way out to the
     *  converge target, sampled this far apart along the way. */
    private static final int PARALLEL_CONFIRM_STEP = 10;
    /** Half-extents of the look-ahead probe box: tight in lateral (a diverging line drops out) and Y,
     *  wide enough in along to bridge the sample spacing. */
    private static final int PARALLEL_PROBE_LATERAL = 4;
    private static final int PARALLEL_PROBE_ALONG = 6;

    private ParallelMergeService() {}

    /**
     * If a foreign track is running parallel and adjacent to {@code head}, merge the head into it and
     * return true (the caller should then complete the head). Returns false if no parallel line is beside
     * it or the merge curve could not be placed.
     */
    public static boolean tryMergeIntoParallel(ServerLevel level, TrackExpansionHead head) {
        BlockPos tip = head.getPosition();
        Direction dir = head.getDirection();
        UUID headId = head.getHeadId();

        BlockPos target = findParallelAdjacentBlock(level, tip, dir, headId);
        if (target == null) {
            return false;
        }
        Object connection = CreateTrackConnector.connectTracks(level, tip, target);
        if (connection == null) {
            return false;
        }
        // Build bridge decking / supports under the merge bezier so it doesn't float over a void.
        com.vodmordia.railwaysuntold.worldgen.placement.handler.SupportPlacementService
                .placeMergeSupports(level, head, tip, connection, target);
        LOGGER.warn("[PARALLEL-MERGE] Head {} at {} merged into a parallel adjacent line at {} - completing head",
                head.getHeadNumber(), tip, target);
        return true;
    }

    /**
     * The nearest foreign physical track block on a same-axis line that the head can curve into as a
     * legal junction - a point down the track far enough ahead that the joining curve holds the minimum
     * radius (see {@link JunctionMergeGeometry#canConnectLegally}), within the parallel-adjacent lateral
     * band. Returns null when the only foreign blocks beside the head would force a sub-radius hairpin.
     */
    private static BlockPos findParallelAdjacentBlock(ServerLevel level, BlockPos tip, Direction dir, UUID headId) {
        Direction.Axis axis = dir.getAxis();
        int minRadius = RailwaysUntoldConfig.getMinCurveRadius();
        int tipChunkX = tip.getX() >> 4;
        int tipChunkZ = tip.getZ() >> 4;
        BlockPos best = null;

        for (int cx = tipChunkX - 1; cx <= tipChunkX + 1; cx++) {
            for (int cz = tipChunkZ - 1; cz <= tipChunkZ + 1; cz++) {
                for (ConnectedSegment seg : ConnectedBoundaryTracker.getSegmentsInChunk(level, new ChunkPos(cx, cz))) {
                    if (headId.equals(seg.headId)) continue;
                    if (segmentAxis(seg) != axis) continue; // not parallel
                    best = nearerCurveInPoint(best, curveInPointOnSegment(seg, tip, dir, minRadius), tip, dir);
                }
            }
        }
        return best;
    }

    /** The point on the segment with the smallest forward run that the head can legally curve into and
     *  that lies in the parallel-adjacent lateral band, or null if the segment offers none. */
    private static BlockPos curveInPointOnSegment(ConnectedSegment seg, BlockPos tip, Direction dir, int minRadius) {
        BlockPos best = nearerCurveInPoint(null, mergeCandidate(seg.start, tip, dir, minRadius), tip, dir);
        best = nearerCurveInPoint(best, mergeCandidate(seg.end, tip, dir, minRadius), tip, dir);
        if (seg.curvePositions != null) {
            for (BlockPos p : seg.curvePositions) {
                best = nearerCurveInPoint(best, mergeCandidate(p, tip, dir, minRadius), tip, dir);
            }
        }
        return best;
    }

    /** {@code p} if the head can legally curve into it and it is within the adjacency lateral band, else null. */
    private static BlockPos mergeCandidate(BlockPos p, BlockPos tip, Direction dir, int minRadius) {
        if (p == null) return null;
        // Joining a parallel line is an S-curve: validate the S-curve radius, not a single-turn radius.
        if (!JunctionMergeGeometry.canConnectLegally(p, tip, dir, true, minRadius, MAX_VERTICAL)) return null;
        int lateral = dir.getAxis() == Direction.Axis.X ? Math.abs(p.getZ() - tip.getZ()) : Math.abs(p.getX() - tip.getX());
        return (lateral >= MIN_LATERAL && lateral <= MAX_LATERAL) ? p : null;
    }

    /** Returns whichever of {@code current}/{@code candidate} has the smaller forward run from the tip. */
    private static BlockPos nearerCurveInPoint(BlockPos current, BlockPos candidate, BlockPos tip, Direction dir) {
        if (candidate == null) return current;
        if (current == null) return candidate;
        return forwardRun(candidate, tip, dir) < forwardRun(current, tip, dir) ? candidate : current;
    }

    private static int forwardRun(BlockPos p, BlockPos tip, Direction dir) {
        return (p.getX() - tip.getX()) * dir.getStepX() + (p.getZ() - tip.getZ()) * dir.getStepZ();
    }

    /** Cardinal axis a segment runs along (by its larger span), or null if degenerate/diagonal-ambiguous. */
    private static Direction.Axis segmentAxis(ConnectedSegment seg) {
        if (seg.start == null || seg.end == null) return null;
        int dx = Math.abs(seg.end.getX() - seg.start.getX());
        int dz = Math.abs(seg.end.getZ() - seg.start.getZ());
        if (dx == dz) return null; // diagonal / point - not a clean parallel axis
        return dx > dz ? Direction.Axis.X : Direction.Axis.Z;
    }

    /**
     * If a parallel same-axis foreign line runs within the long-range convergence band beside
     * {@code head}, returns a target point ON that line, ahead of the head, for the head to route toward.
     * The head curves over via the normal planner; once it reaches the short-range band beside the line,
     * {@link #tryMergeIntoParallel} completes the join. Returns null when no convergeable line is beside it.
     *
     * The target is placed ahead by the lateral gap plus two curve radii so the planner has room to turn
     * toward the line and curve back onto its heading rather than stab into it.
     */
    public static BlockPos findParallelConvergeTarget(ServerLevel level, TrackExpansionHead head) {
        BlockPos tip = head.getPosition();
        Direction dir = head.getDirection();
        Direction.Axis axis = dir.getAxis();
        boolean alongX = (axis == Direction.Axis.X);
        UUID headId = head.getHeadId();

        int tipChunkX = tip.getX() >> 4;
        int tipChunkZ = tip.getZ() >> 4;
        BlockPos bestPoint = null;
        int bestLateral = Integer.MAX_VALUE;

        for (int cx = tipChunkX - CONVERGE_SCAN_CHUNKS; cx <= tipChunkX + CONVERGE_SCAN_CHUNKS; cx++) {
            for (int cz = tipChunkZ - CONVERGE_SCAN_CHUNKS; cz <= tipChunkZ + CONVERGE_SCAN_CHUNKS; cz++) {
                for (ConnectedSegment seg : ConnectedBoundaryTracker.getSegmentsInChunk(level, new ChunkPos(cx, cz))) {
                    if (headId.equals(seg.headId)) continue;
                    if (segmentAxis(seg) != axis) continue;
                    BlockPos cand = convergePointOnSegment(seg, tip, alongX);
                    if (cand == null) continue;
                    int lateral = alongX ? Math.abs(cand.getZ() - tip.getZ()) : Math.abs(cand.getX() - tip.getX());
                    if (lateral < bestLateral) {
                        bestLateral = lateral;
                        bestPoint = cand;
                    }
                }
            }
        }
        if (bestPoint == null) {
            return null;
        }

        int forwardMargin = bestLateral + 2 * RailwaysUntoldConfig.getMinCurveRadius();

        // Only converge into a SUSTAINED parallel line. A momentary alignment (a crossing line, a curve
        // tangent, or a short siding) is not worth diverting onto and produces the wrong merge, and a line
        // that curves away before the merge point steers the head onto a curve (the bad merge fixed by
        // JunctionMergeGeometry). Require the line to still carry track at this lateral all the way out to
        // the converge target the head would aim for.
        int lineLateral = alongX ? bestPoint.getZ() : bestPoint.getX();
        if (!lineParallelAhead(level, tip, dir, headId, lineLateral, alongX, forwardMargin)) {
            return null;
        }
        if (alongX) {
            return new BlockPos(tip.getX() + dir.getStepX() * forwardMargin, bestPoint.getY(), bestPoint.getZ());
        }
        return new BlockPos(bestPoint.getX(), bestPoint.getY(), tip.getZ() + dir.getStepZ() * forwardMargin);
    }

    /** Point on the segment whose lateral offset from the tip is in the convergence band and roughly level,
     *  preferring the smallest lateral; or null if the segment has none. */
    private static BlockPos convergePointOnSegment(ConnectedSegment seg, BlockPos tip, boolean alongX) {
        BlockPos best = null;
        for (BlockPos p : new BlockPos[]{seg.start, seg.end}) {
            best = betterConvergePoint(best, p, tip, alongX);
        }
        if (seg.curvePositions != null) {
            for (BlockPos p : seg.curvePositions) {
                best = betterConvergePoint(best, p, tip, alongX);
            }
        }
        return best;
    }

    /** Returns whichever of {@code current}/{@code candidate} is the smaller-lateral in-band point. */
    private static BlockPos betterConvergePoint(BlockPos current, BlockPos candidate, BlockPos tip, boolean alongX) {
        if (candidate == null) return current;
        int lateral = alongX ? Math.abs(candidate.getZ() - tip.getZ()) : Math.abs(candidate.getX() - tip.getX());
        int vertical = Math.abs(candidate.getY() - tip.getY());
        if (lateral < CONVERGE_MIN_LATERAL || lateral > CONVERGE_MAX_LATERAL || vertical > CONVERGE_MAX_VERTICAL) {
            return current;
        }
        if (current == null) return candidate;
        int currentLateral = alongX ? Math.abs(current.getZ() - tip.getZ()) : Math.abs(current.getX() - tip.getX());
        return lateral < currentLateral ? candidate : current;
    }

    /**
     * True if a foreign line still carries track at {@code lineLateral} (the same lateral as the detected
     * candidate) when sampled from PARALLEL_CONFIRM_STEP up to {@code confirmDist} ahead of the tip along
     * {@code dir}. A short siding, a crossing, or a line that curves away before the merge point fails to
     * carry track at some sample and is rejected, so convergence only fires for a line that stays parallel
     * across the whole approach the head would drive to reach it.
     */
    private static boolean lineParallelAhead(ServerLevel level, BlockPos tip, Direction dir, UUID headId,
                                             int lineLateral, boolean alongX, int confirmDist) {
        for (int ahead = PARALLEL_CONFIRM_STEP; ahead <= confirmDist; ahead += PARALLEL_CONFIRM_STEP) {
            int cx = alongX ? tip.getX() + dir.getStepX() * ahead : lineLateral;
            int cz = alongX ? lineLateral : tip.getZ() + dir.getStepZ() * ahead;
            int latHalf = PARALLEL_PROBE_LATERAL;
            int alongHalf = PARALLEL_PROBE_ALONG;
            BlockPos min = alongX
                    ? new BlockPos(cx - alongHalf, tip.getY() - CONVERGE_MAX_VERTICAL, cz - latHalf)
                    : new BlockPos(cx - latHalf, tip.getY() - CONVERGE_MAX_VERTICAL, cz - alongHalf);
            BlockPos max = alongX
                    ? new BlockPos(cx + alongHalf, tip.getY() + CONVERGE_MAX_VERTICAL, cz + latHalf)
                    : new BlockPos(cx + latHalf, tip.getY() + CONVERGE_MAX_VERTICAL, cz + alongHalf);
            if (!ConnectedBoundaryTracker.hasSegmentIntersecting(level, min, max, seg -> headId.equals(seg.headId))) {
                return false;
            }
        }
        return true;
    }
}
