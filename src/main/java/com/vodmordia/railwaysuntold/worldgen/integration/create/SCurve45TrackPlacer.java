package com.vodmordia.railwaysuntold.worldgen.integration.create;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.util.block.ChunkSafeBlockAccess;
import com.vodmordia.railwaysuntold.util.chunk.ChunkVerificationUtil;
import com.vodmordia.railwaysuntold.util.placement.SCurve45Geometry;
import com.vodmordia.railwaysuntold.util.spatial.DiagonalDirection;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.CreateTrackUtil;
import com.vodmordia.railwaysuntold.worldgen.integration.deferred.BlockEntitiesNotReadyException;
import com.vodmordia.railwaysuntold.worldgen.integration.deferred.BlockEntityLinker;
import com.vodmordia.railwaysuntold.worldgen.tracking.BoundarySegmentData.ConnectionType;
import com.vodmordia.railwaysuntold.worldgen.tracking.ConnectedBoundaryTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Places a 45-degree S-curve track consisting of:
 * 1. First 45° bezier curve (cardinal -> diagonal)
 * 2. Diagonal straight segment
 * 3. Second 45° bezier curve (diagonal -> cardinal)
 */
public class SCurve45TrackPlacer {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Result of the S-curve placement.
     */
    public static class SCurve45Result {
        public final boolean success;
        public final boolean needsRetry;
        public final BlockPos endpoint;
        /** Bezier connections created during placement, for terrain clearing. May be empty on failure. */
        public final java.util.List<Object> connections;

        private SCurve45Result(boolean success, boolean needsRetry, BlockPos endpoint, java.util.List<Object> connections) {
            this.success = success;
            this.needsRetry = needsRetry;
            this.endpoint = endpoint;
            this.connections = connections != null ? connections : java.util.List.of();
        }

        public static SCurve45Result failure() {
            return new SCurve45Result(false, false, null, null);
        }

        public static SCurve45Result needsRetry() {
            return new SCurve45Result(false, true, null, null);
        }

        public static SCurve45Result success(BlockPos endpoint, java.util.List<Object> connections) {
            return new SCurve45Result(true, false, endpoint, connections);
        }
    }

    public static SCurve45Result place(
            ServerLevel level,
            BlockPos start,
            Direction direction,
            int radius,
            int diagonalLength,
            boolean shiftLeft,
            int elevationChange,
            UUID headId) {

        SCurvePositions positions = calculatePositions(start, direction, radius, diagonalLength, shiftLeft, elevationChange);

        if (!ChunkVerificationUtil.areBoundingBoxChunksLoaded(level, start, positions.finalEnd)) {
            return SCurve45Result.needsRetry();
        }

        return placeAllTrackSegments(level, start, direction, diagonalLength, positions, headId);
    }

    /**
     * Removes the track blocks an S-curve placement would have written, excluding {@code start}
     * (which is treated as caller-owned, e.g. a parent track). Safe to call after a successful
     * placement to undo it when a downstream step fails. No-ops at positions that are no longer
     * track blocks.
     */
    public static void rollbackPlacement(
            ServerLevel level,
            BlockPos start,
            Direction direction,
            int radius,
            int diagonalLength,
            boolean shiftLeft,
            int elevationChange) {
        SCurvePositions positions = calculatePositions(start, direction, radius, diagonalLength, shiftLeft, elevationChange);
        removeTrackBlockIfPresent(level, positions.firstCurveEnd);
        removeTrackBlockIfPresent(level, positions.diagonalEnd);
        removeTrackBlockIfPresent(level, positions.finalEnd);
    }

    private static void removeTrackBlockIfPresent(ServerLevel level, BlockPos pos) {
        BlockState state = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, pos);
        if (state != null && CreateTrackUtil.isTrackBlock(state)) {
            level.removeBlock(pos, false);
        }
    }

    private record SCurvePositions(BlockPos firstCurveEnd, BlockPos diagonalEnd, BlockPos finalEnd, DiagonalDirection diagonal) {
    }

    private static SCurvePositions calculatePositions(BlockPos start, Direction direction, int radius,
                                                       int diagonalLength, boolean shiftLeft, int elevationChange) {
        DiagonalDirection diagonal = DiagonalDirection.from45Turn(direction, shiftLeft);
        int firstCurveElev = distributeElevation(elevationChange, radius, diagonalLength, 0);

        BlockPos firstCurveEnd = SCurve45Geometry.calculateFirstCurveEnd(start, direction, shiftLeft, radius, firstCurveElev);
        BlockPos finalEnd = SCurve45Geometry.calculateEndpoint(start, direction, shiftLeft, radius, diagonalLength, elevationChange);
        BlockPos diagonalEnd = SCurve45Geometry.calculateDiagonalEndFromFinal(start, direction, shiftLeft, radius, diagonalLength, elevationChange);

        return new SCurvePositions(firstCurveEnd, diagonalEnd, finalEnd, diagonal);
    }

    private static SCurve45Result placeAllTrackSegments(ServerLevel level, BlockPos start, Direction direction,
                                                         int diagonalLength, SCurvePositions positions, UUID headId) {
        PreExistingTrack snapshot = PreExistingTrack.capture(level, start, positions);
        try {
            java.util.List<Object> connections = placeCurvesAndDiagonal(level, start, direction, diagonalLength, positions);
            confirmAllSegments(level, start, positions, headId);
            return SCurve45Result.success(positions.finalEnd, connections);
        } catch (BlockEntitiesNotReadyException e) {
            rollbackNewlyPlaced(level, start, positions, snapshot);
            LOGGER.debug("[SCURVE45] Block entities not ready, will retry: {}", e.getMessage());
            return SCurve45Result.needsRetry();
        } catch (PlacementFailedException e) {
            rollbackNewlyPlaced(level, start, positions, snapshot);
            LOGGER.debug("[SCURVE45] Placement failed for S-curve at {}", start);
            return SCurve45Result.failure();
        } catch (ReflectiveOperationException | RuntimeException e) {
            rollbackNewlyPlaced(level, start, positions, snapshot);
            LOGGER.error("[SCURVE45] Exception placing S-curve: {}", e.getMessage());
            return SCurve45Result.failure();
        }
    }

    /**
     * Snapshot of which candidate positions held a track block before placement began,
     * so the rollback path only removes blocks that this placer newly created.
     */
    private record PreExistingTrack(boolean start, boolean firstCurveEnd, boolean diagonalEnd, boolean finalEnd) {
        static PreExistingTrack capture(ServerLevel level, BlockPos start, SCurvePositions positions) {
            return new PreExistingTrack(
                    isTrack(level, start),
                    isTrack(level, positions.firstCurveEnd),
                    isTrack(level, positions.diagonalEnd),
                    isTrack(level, positions.finalEnd));
        }

        private static boolean isTrack(ServerLevel level, BlockPos pos) {
            BlockState state = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, pos);
            return state != null && CreateTrackUtil.isTrackBlock(state);
        }
    }

    private static void rollbackNewlyPlaced(ServerLevel level, BlockPos start, SCurvePositions positions,
                                             PreExistingTrack snapshot) {
        if (!snapshot.finalEnd) removeTrackBlockIfPresent(level, positions.finalEnd);
        if (!snapshot.diagonalEnd) removeTrackBlockIfPresent(level, positions.diagonalEnd);
        if (!snapshot.firstCurveEnd) removeTrackBlockIfPresent(level, positions.firstCurveEnd);
        if (!snapshot.start) removeTrackBlockIfPresent(level, start);
    }

    private static java.util.List<Object> placeCurvesAndDiagonal(ServerLevel level, BlockPos start, Direction direction,
                                                int diagonalLength, SCurvePositions positions) throws ReflectiveOperationException, PlacementFailedException {
        java.util.List<Object> connections = new java.util.ArrayList<>();

        Object curve1 = placeFirst45Curve(level, start, direction, positions.firstCurveEnd, positions.diagonal);
        if (curve1 == null) {
            throw new PlacementFailedException();
        }
        connections.add(curve1);

        if (diagonalLength > 0) {
            Object diagResult = placeDiagonalStraight(level, positions.firstCurveEnd, positions.diagonalEnd, positions.diagonal);
            if (diagResult == null) {
                throw new PlacementFailedException();
            }
            connections.add(diagResult);
        }

        Object curve2 = placeSecond45Curve(level, positions.diagonalEnd, positions.diagonal, positions.finalEnd, direction);
        if (curve2 == null) {
            throw new PlacementFailedException();
        }
        connections.add(curve2);

        return connections;
    }

    private static void confirmAllSegments(ServerLevel level, BlockPos start, SCurvePositions positions, UUID headId) {
        ConnectedBoundaryTracker.confirmSegment(level, start, positions.firstCurveEnd, ConnectionType.BEZIER, null, headId);
        ConnectedBoundaryTracker.confirmSegment(level, positions.firstCurveEnd, positions.diagonalEnd, ConnectionType.STRAIGHT, null, headId);
        ConnectedBoundaryTracker.confirmSegment(level, positions.diagonalEnd, positions.finalEnd, ConnectionType.BEZIER, null, headId);
    }

    /**
     * Internal exception for placement failures to consolidate error handling.
     */
    private static class PlacementFailedException extends Exception {
    }

    private static int distributeElevation(int total, int radius, int diagLen, int segment) {
        return SCurve45Geometry.distributeElevationForSegment(total, radius, diagLen, segment);
    }

    /**
     * Places the first 45-degree curve (cardinal to diagonal).
     * Exposed for use by diagonal travel executors.
     */
    public static Object placeFirst45Curve(
            ServerLevel level,
            BlockPos start,
            Direction cardinalDir,
            BlockPos end,
            DiagonalDirection diagonalDir) throws ReflectiveOperationException {
        com.vodmordia.railwaysuntold.worldgen.integration.railway.RailwayModCompat.applyMaterialForBiome(level, start);

        BlockState existingStart = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, start);
        if (existingStart == null || !CreateTrackUtil.isTrackBlock(existingStart)) {
            BlockState startTrack = CreateTrackUtil.getStraightTrack(cardinalDir);
            ChunkSafeBlockAccess.setBlockStateNonBlocking(level, start, startTrack, true);
        }

        BlockState endTrack = CreateTrackUtil.getDiagonalTrack(diagonalDir);
        ChunkSafeBlockAccess.setBlockStateNonBlocking(level, end, endTrack, true);

        // Cardinal axes have length 1, diagonal axes have length sqrt(2)
        // Both are passed unnormalized to match Create's getCurveStart() which uses axis.scale(.5)
        Vec3 startAxis = Vec3.atLowerCornerOf(cardinalDir.getNormal());
        Vec3 endAxis = diagonalDir.getAxis();

        return createBezierConnection(level, start, end, startAxis, endAxis);
    }

    /**
     * Places a diagonal straight segment with a bezier connection.
     * Exposed for use by DiagonalStraightTrackPlacer.
     */
    public static Object placeDiagonalStraightSegment(
            ServerLevel level,
            BlockPos start,
            BlockPos end,
            DiagonalDirection diagonal) throws ReflectiveOperationException {
        return placeDiagonalStraight(level, start, end, diagonal);
    }

    /**
     * Places the diagonal straight segment with a bezier connection.
     */
    private static Object placeDiagonalStraight(
            ServerLevel level,
            BlockPos start,
            BlockPos end,
            DiagonalDirection diagonal) throws ReflectiveOperationException {

        BlockState diagTrack = CreateTrackUtil.getDiagonalTrack(diagonal);

        BlockState existingStart = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, start);
        if (existingStart == null || !CreateTrackUtil.isTrackBlock(existingStart)) {
            ChunkSafeBlockAccess.setBlockStateNonBlocking(level, start, diagTrack, true);
        }

        ChunkSafeBlockAccess.setBlockStateNonBlocking(level, end, diagTrack, true);

        Vec3 diagAxis = diagonal.getAxis();
        return createBezierConnection(level, start, end, diagAxis, diagAxis);
    }

    /**
     * Places the second 45-degree curve (diagonal to cardinal).
     * Exposed for use by diagonal travel executors.
     */
    public static Object placeSecond45Curve(
            ServerLevel level,
            BlockPos start,
            DiagonalDirection diagonalDir,
            BlockPos end,
            Direction cardinalDir) throws ReflectiveOperationException {

        BlockState startTrack = CreateTrackUtil.getDiagonalTrack(diagonalDir);
        BlockState existingStart = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, start);
        if (existingStart == null || !CreateTrackUtil.isTrackBlock(existingStart)) {
            ChunkSafeBlockAccess.setBlockStateNonBlocking(level, start, startTrack, true);
        }

        BlockState endTrack = CreateTrackUtil.getStraightTrack(cardinalDir);
        ChunkSafeBlockAccess.setBlockStateNonBlocking(level, end, endTrack, true);

        // Diagonal axes have length sqrt(2), cardinal axes have length 1
        // Both are passed unnormalized to match Create's getCurveStart() which uses axis.scale(.5)
        Vec3 startAxis = diagonalDir.getAxis();
        Vec3 endAxis = Vec3.atLowerCornerOf(cardinalDir.getNormal());

        return createBezierConnection(level, start, end, startAxis, endAxis);
    }

    /**
     * Creates a bezier connection between two track positions.
     * Uses TrackGeometryCalculator to get proper curve start positions from Create.
     * Applies axis flipping to ensure proper curve geometry (matching Create's TrackPlacement.tryConnect).
     */
    private static Object createBezierConnection(
            ServerLevel level,
            BlockPos pos1,
            BlockPos pos2,
            Vec3 axis1,
            Vec3 axis2) throws ReflectiveOperationException {

        BlockState state1 = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, pos1);
        BlockState state2 = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, pos2);

        if (state1 == null || state2 == null) {
            throw new BlockEntitiesNotReadyException("Track states not ready");
        }

        Class<?> iTrackBlockClass = CreateTrackUtil.getITrackBlockClass();
        Object track1 = state1.getBlock();
        Object track2 = state2.getBlock();

        // Validate blocks implement ITrackBlock before reflection calls.
        // setBlockStateNonBlocking can silently fail at chunk boundaries, leaving
        // non-track blocks that would cause "object is not an instance of declaring class".
        if (!iTrackBlockClass.isInstance(track1) || !iTrackBlockClass.isInstance(track2)) {
            throw new BlockEntitiesNotReadyException(
                    "Track blocks not ready - pos1 " + pos1 + " isTrack=" + iTrackBlockClass.isInstance(track1)
                            + ", pos2 " + pos2 + " isTrack=" + iTrackBlockClass.isInstance(track2));
        }

        Vec3 end1 = TrackGeometryCalculator.getTrackCurveStart(level, pos1, state1, track1, iTrackBlockClass, axis1);
        Vec3 end2 = TrackGeometryCalculator.getTrackCurveStart(level, pos2, state2, track2, iTrackBlockClass, axis2);

        if (end1 == null || end2 == null) {
            throw new BlockEntitiesNotReadyException("Curve start positions not ready");
        }

        // Apply Create's axis flipping logic to ensure proper curve geometry.
        TrackGeometryCalculator.TrackEndpointData firstEndpoint = new TrackGeometryCalculator.TrackEndpointData(
                axis1, end1, pos1, state1, track1);
        TrackGeometryCalculator.TrackEndpointData secondEndpoint = new TrackGeometryCalculator.TrackEndpointData(
                axis2, end2, pos2, state2, track2);
        TrackGeometryCalculator.AxisFlipResult flipResult = TrackGeometryCalculator.applyAxisFlipping(
                firstEndpoint, secondEndpoint, level, iTrackBlockClass);

        Vec3 normal1 = TrackGeometryCalculator.getTrackNormal(level, pos1, state1, track1, iTrackBlockClass);
        Vec3 normal2 = TrackGeometryCalculator.getTrackNormal(level, pos2, state2, track2, iTrackBlockClass);

        if (normal1 == null) normal1 = new Vec3(0, 1, 0);
        if (normal2 == null) normal2 = new Vec3(0, 1, 0);

        Object connection = CreateTrackUtil.constructBezierConnection(
                pos1, pos2,
                flipResult.firstEnd, flipResult.secondEnd,
                flipResult.firstAxis, flipResult.secondAxis,
                normal1, normal2);

        BlockEntityLinker.linkTrackBlockEntitiesWithConnection(level, pos1, pos2, connection);

        return connection;
    }
}
