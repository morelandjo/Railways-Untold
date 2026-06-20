package com.vodmordia.railwaysuntold.worldgen.placement;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.worldgen.head.TrackExpansionHead;
import com.vodmordia.railwaysuntold.worldgen.integration.create.CreateTrackConnector;
import com.vodmordia.railwaysuntold.worldgen.integration.deferred.DeferredConnectionManager;
import com.vodmordia.railwaysuntold.worldgen.placement.handler.SupportPlacementService;
import com.vodmordia.railwaysuntold.worldgen.tracking.BoundarySegmentData.ConnectedSegment;
import com.vodmordia.railwaysuntold.worldgen.tracking.ConnectedBoundaryTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Last-resort resolution for a head that is permanently blocked by another head's already-placed
 * line and cannot cross it (a near-grade head-on meeting where neither an over nor an under ramp
 * fits within the slope limit from the stuck tip). Instead of retiring the head into a dead stub,
 * terminate it into a T-JUNCTION with the foreign line: curve the head's tip into the nearest
 * physical track block on that line and let Create merge the two track graphs.
 *
 * Reuses {@link CreateTrackConnector#connectTracks} - the same primitive sidings use - which reads
 * both endpoints' real block tangents, builds a legal tangent-matched bezier, and queues the
 * deferred graph join. {@code connectTracks} validates that both endpoints are real connectable
 * track blocks (not virtual {@code fake_track} filler) and returns null if not, so this only ever
 * forms a junction onto genuine physical track.
 */
public final class JunctionTerminator {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How far from the stuck tip to look for the foreign line to junction into. The head is stuck
     *  because the foreign track is right in front of it, so the connection point is always close. */
    private static final int SEARCH_RADIUS = 12;
    /** A blocked placement fails when the obstacle is too CLOSE for {@code capSpanBeforeObstacle} to cap
     *  it (forwardToObstacle &lt; obstacleStopMargin + MIN_CAPPED_ADVANCE ≈ 16) - yet a crossing at 13-15
     *  blocks is also beyond {@link #SEARCH_RADIUS}=12, a dead zone where the head can neither cap nor
     *  junction and retires with a dead-end stub. The blocked-line merge searches
     *  out this far (>= the cap give-up boundary, and a full segment span for the non-capped executors)
     *  so any blocked obstacle is reachable. Nearest-first keeps the join tight when the obstacle is close. */
    private static final int BLOCKED_AHEAD_SEARCH_RADIUS = 64;
    /** Cap connection attempts (nearest candidates first) so a dense junction area can't spin. */
    private static final int MAX_ATTEMPTS = 6;
    /** Ticks to spend flushing the freshly-queued junction connection (mirrors the starting-train flush). */
    private static final int JUNCTION_FLUSH_ATTEMPTS = 20;
    /** Vertical window for a legal-curve junction (a converging head ramps to the line's Y on approach). */
    private static final int LEGAL_CURVE_MAX_VERTICAL = 8;

    private JunctionTerminator() {}

    /**
     * Attempts to terminate {@code head} into the nearest foreign line as a junction. Returns true if
     * a connecting curve was placed (the head should then be marked complete); false if no physical
     * foreign track block was reachable, in which case the caller falls back to retiring the head.
     */
    public static boolean tryTerminateIntoForeignLine(ServerLevel level, TrackExpansionHead head) {
        return tryTerminateIntoForeignLine(level, head, SEARCH_RADIUS);
    }

    /**
     * Resolve a "blocked by existing track" failure by junctioning into the line the head ran into.
     * Unlike the stuck-tip case the blocker can be a whole segment span ahead (a bridge collides with
     * a crossing at its far end), so this searches out to {@link #BLOCKED_AHEAD_SEARCH_RADIUS}. Returns
     * true if a (legal-curve) junction was placed; otherwise the caller retires the head as a dead stub.
     */
    public static boolean tryTerminateIntoBlockingLine(ServerLevel level, TrackExpansionHead head) {
        return tryTerminateIntoForeignLine(level, head, BLOCKED_AHEAD_SEARCH_RADIUS);
    }

    /**
     * Terminate {@code head} into the nearest foreign line within {@code searchRadius} whose joining curve
     * is LEGAL - at least the minimum radius. Candidates that would force a sub-radius hairpin (incl. a
     * too-tight S-curve into a parallel line, see {@link JunctionMergeGeometry#canConnectLegally}) are
     * dropped, so the merge takes the nearest point far enough DOWN the foreign line for a real curve. If
     * none is reachable it returns false and the caller retires the head - a clean dead stub beats track a
     * train can't drive. A converging head passes a larger radius (its joining curve needs more room).
     */
    public static boolean tryTerminateIntoForeignLine(ServerLevel level, TrackExpansionHead head, int searchRadius) {
        return tryTerminateIntoForeignLine(level, head, searchRadius, false);
    }

    /**
     * As above, but when {@code parallelOnly} is set, only foreign segments running on the head's own axis
     * are eligible. A parallel CONVERGE must finish onto the line it was steering alongside - joining a
     * perpendicular/cross segment that merely happens to sit a legal distance ahead makes Create's bezier
     * connector resolve the opposing tangents into a backwards loop (a hairpin that inverts past the join
     * point). The blocked-head junction keeps {@code parallelOnly=false}: a head driven head-on into a
     * crossing line legitimately T's into it.
     */
    public static boolean tryTerminateIntoForeignLine(ServerLevel level, TrackExpansionHead head,
                                                      int searchRadius, boolean parallelOnly) {
        BlockPos tip = head.getPosition();
        UUID headId = head.getHeadId();
        Direction dir = head.getDirection();
        int minRadius = RailwaysUntoldConfig.getMinCurveRadius();

        List<Candidate> candidates = foreignTrackCandidatesNear(level, tip, headId, searchRadius);
        if (parallelOnly) {
            candidates.removeIf(c -> c.foreignAxis() != dir.getAxis());
        }
        candidates.removeIf(c -> !JunctionMergeGeometry.canConnectLegally(
                c.pos(), tip, dir, c.foreignAxis() == dir.getAxis(), minRadius, LEGAL_CURVE_MAX_VERTICAL));
        if (candidates.isEmpty()) {
            return false;
        }
        // Nearest legal point first (for a parallel line that is the nearest with enough forward run).
        candidates.sort((a, b) -> Integer.compare(distSq(a.pos(), tip), distSq(b.pos(), tip)));

        int attempts = 0;
        for (Candidate target : candidates) {
            if (attempts++ >= MAX_ATTEMPTS) break;
            if (target.pos().equals(tip)) continue;
            logMergeGeometry(head, tip, dir, target, parallelOnly);
            if (connectAndFlush(level, head, tip, target.pos(), false)) {
                return true;
            }
        }
        return false;
    }

    /** Verbose capture of the chosen merge geometry - whether the join is into the head's own (parallel)
     *  axis or a cross axis, plus the forward/lateral/vertical offsets - so an inverting/looping connection
     *  can be diagnosed from a log. */
    private static void logMergeGeometry(TrackExpansionHead head, BlockPos tip, Direction dir,
                                         Candidate target, boolean parallelOnly) {
        if (!RailwaysUntoldConfig.isVerboseLoggingEnabled()) {
            return;
        }
        BlockPos p = target.pos();
        int forward = (p.getX() - tip.getX()) * dir.getStepX() + (p.getZ() - tip.getZ()) * dir.getStepZ();
        int lateral = dir.getAxis() == Direction.Axis.X
                ? Math.abs(p.getZ() - tip.getZ()) : Math.abs(p.getX() - tip.getX());
        LOGGER.info("[MERGE-DIAG] head {} tip {} dir {} -> foreign {} foreignAxis {} forward {} lateral {} dy {} parallel {} parallelOnly {}",
                head.getHeadNumber(), tip, dir, p, target.foreignAxis(), forward, lateral,
                p.getY() - tip.getY(), target.foreignAxis() == dir.getAxis(), parallelOnly);
    }

    /**
     * Connects the tip into a foreign track block and immediately flushes the queued connection while the
     * endpoint chunks are still loaded. A head completes right after junctioning, so its area can unload
     * before the deferred connection manager processes the connection - discarding it and leaving the
     * junction's track missing. The priority flush applies the connection now. Returns true on a placed
     * connection.
     */
    private static boolean connectAndFlush(ServerLevel level, TrackExpansionHead head,
                                           BlockPos tip, BlockPos target, boolean ahead) {
        Object connection = CreateTrackConnector.connectTracks(level, tip, target);
        if (connection == null) {
            return false;
        }
        // Build bridge decking / supports under the merge bezier - connectTracks lays only the rail, so a
        // junction spanning a valley would otherwise float. Same support pass normal placement runs.
        SupportPlacementService.placeMergeSupports(level, head, tip, connection, target);
        int headNumber = head.getHeadNumber();
        DeferredConnectionManager.processPriorityConnectionsAsync(
                level, java.util.Set.of(tip, target), JUNCTION_FLUSH_ATTEMPTS, success -> {
                    if (!success) {
                        LOGGER.warn("[JUNCTION-TERMINATE] Head {} junction {} -> {} did not apply within {} ticks - junction track may be missing",
                                headNumber, tip, target, JUNCTION_FLUSH_ATTEMPTS);
                    }
                });
        return true;
    }

    /** How far ahead along travel to look for a line the head is driving head-on toward but can't reach
     *  within the tip search - e.g. a bridge that collides with a near-grade crossing far along its span.
     *  Bounded so the joining curve stays a plausible approach rather than a wild long sweep. */
    private static final int FORWARD_REACH = 160;
    /** Lateral/vertical half-window of the forward beam. */
    private static final int BEAM_HALF_WINDOW = 8;

    /**
     * Last-resort merge for a head blocked by a foreign line it is driving head-on toward but whose
     * crossing point is beyond the tip search (the bridge-spans-into-a-crossing case): searches a beam
     * extending {@code FORWARD_REACH} along {@code travelDir} for the nearest foreign track and junctions
     * into it, so the head ends on the network instead of dangling as a dead stub. Returns true if a
     * connecting curve was placed.
     */
    public static boolean tryTerminateIntoForeignLineAhead(ServerLevel level, TrackExpansionHead head,
                                                           Direction travelDir) {
        BlockPos tip = head.getPosition();
        UUID headId = head.getHeadId();
        int minRadius = RailwaysUntoldConfig.getMinCurveRadius();

        List<Candidate> candidates = foreignTrackCandidatesAhead(level, tip, headId, travelDir);
        candidates.removeIf(c -> !JunctionMergeGeometry.canConnectLegally(
                c.pos(), tip, travelDir, c.foreignAxis() == travelDir.getAxis(), minRadius, LEGAL_CURVE_MAX_VERTICAL));
        if (candidates.isEmpty()) {
            return false;
        }
        candidates.sort((a, b) -> Integer.compare(distSq(a.pos(), tip), distSq(b.pos(), tip)));

        int attempts = 0;
        for (Candidate target : candidates) {
            if (attempts++ >= MAX_ATTEMPTS) break;
            if (target.pos().equals(tip)) continue;
            if (connectAndFlush(level, head, tip, target.pos(), true)) {
                return true;
            }
        }
        return false;
    }

    /** Foreign track points inside the forward beam (ahead along {@code travelDir}), tagged with axis. */
    private static List<Candidate> foreignTrackCandidatesAhead(ServerLevel level, BlockPos tip, UUID headId,
                                                               Direction travelDir) {
        List<Candidate> out = new ArrayList<>();
        int beamChunks = (FORWARD_REACH >> 4) + 1;
        int tipChunkX = tip.getX() >> 4;
        int tipChunkZ = tip.getZ() >> 4;
        for (int cx = tipChunkX - beamChunks; cx <= tipChunkX + beamChunks; cx++) {
            for (int cz = tipChunkZ - beamChunks; cz <= tipChunkZ + beamChunks; cz++) {
                for (ConnectedSegment seg : ConnectedBoundaryTracker.getSegmentsInChunk(level, new ChunkPos(cx, cz))) {
                    if (headId.equals(seg.headId)) continue;
                    Direction.Axis axis = segmentAxis(seg);
                    addIfInBeam(out, seg.start, tip, travelDir, axis);
                    addIfInBeam(out, seg.end, tip, travelDir, axis);
                    if (seg.curvePositions != null) {
                        for (BlockPos p : seg.curvePositions) {
                            addIfInBeam(out, p, tip, travelDir, axis);
                        }
                    }
                }
            }
        }
        return out;
    }

    private static void addIfInBeam(List<Candidate> out, BlockPos p, BlockPos tip, Direction travelDir, Direction.Axis axis) {
        if (p != null && inForwardBeam(p, tip, travelDir, FORWARD_REACH, BEAM_HALF_WINDOW)) {
            out.add(new Candidate(p, axis));
        }
    }

    /**
     * True when {@code p} lies in the forward beam from {@code tip}: ahead along {@code travelDir} within
     * {@code reach}, within {@code halfWindow} laterally (perpendicular to travel) and vertically.
     */
    static boolean inForwardBeam(BlockPos p, BlockPos tip, Direction travelDir, int reach, int halfWindow) {
        int dx = p.getX() - tip.getX();
        int dy = p.getY() - tip.getY();
        int dz = p.getZ() - tip.getZ();
        int forward = dx * travelDir.getStepX() + dz * travelDir.getStepZ();
        if (forward <= 0 || forward > reach) {
            return false;
        }
        int lateral = travelDir.getStepX() != 0 ? Math.abs(dz) : Math.abs(dx);
        return lateral <= halfWindow && Math.abs(dy) <= halfWindow;
    }

    /** A foreign track point and the axis the line runs along there, so the merge can tell a parallel
     *  (S-curve) join from a perpendicular (single-turn) one when checking the curve is legal. */
    private record Candidate(BlockPos pos, Direction.Axis foreignAxis) {}

    /** The axis a segment runs along, from its endpoints (the longer span wins). */
    private static Direction.Axis segmentAxis(ConnectedSegment seg) {
        return Math.abs(seg.end.getX() - seg.start.getX()) >= Math.abs(seg.end.getZ() - seg.start.getZ())
                ? Direction.Axis.X : Direction.Axis.Z;
    }

    /** Physical track points belonging to OTHER heads within {@code searchRadius} of the tip, tagged with
     *  their line's axis. */
    private static List<Candidate> foreignTrackCandidatesNear(ServerLevel level, BlockPos tip, UUID headId, int searchRadius) {
        List<Candidate> out = new ArrayList<>();
        int chunkSpan = (searchRadius >> 4) + 1;
        int tipChunkX = tip.getX() >> 4;
        int tipChunkZ = tip.getZ() >> 4;
        for (int cx = tipChunkX - chunkSpan; cx <= tipChunkX + chunkSpan; cx++) {
            for (int cz = tipChunkZ - chunkSpan; cz <= tipChunkZ + chunkSpan; cz++) {
                for (ConnectedSegment seg : ConnectedBoundaryTracker.getSegmentsInChunk(level, new ChunkPos(cx, cz))) {
                    if (headId.equals(seg.headId)) continue;
                    Direction.Axis axis = segmentAxis(seg);
                    addIfNear(out, seg.start, tip, searchRadius, axis);
                    addIfNear(out, seg.end, tip, searchRadius, axis);
                    if (seg.curvePositions != null) {
                        for (BlockPos p : seg.curvePositions) {
                            addIfNear(out, p, tip, searchRadius, axis);
                        }
                    }
                }
            }
        }
        return out;
    }

    private static void addIfNear(List<Candidate> out, BlockPos p, BlockPos tip, int searchRadius, Direction.Axis axis) {
        if (p == null) return;
        if (Math.abs(p.getX() - tip.getX()) <= searchRadius
                && Math.abs(p.getZ() - tip.getZ()) <= searchRadius
                && Math.abs(p.getY() - tip.getY()) <= searchRadius) {
            out.add(new Candidate(p, axis));
        }
    }

    private static int distSq(BlockPos a, BlockPos b) {
        int dx = a.getX() - b.getX();
        int dy = a.getY() - b.getY();
        int dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}
