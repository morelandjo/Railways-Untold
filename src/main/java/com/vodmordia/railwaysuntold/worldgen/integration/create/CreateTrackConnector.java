package com.vodmordia.railwaysuntold.worldgen.integration.create;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.block.ChunkSafeBlockAccess;
import com.vodmordia.railwaysuntold.util.chunk.ChunkVerificationUtil;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.CreateTrackUtil;
import com.vodmordia.railwaysuntold.worldgen.integration.deferred.BlockEntitiesNotReadyException;
import com.vodmordia.railwaysuntold.worldgen.integration.deferred.BlockEntityLinker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import javax.annotation.Nullable;

/**
 * Helper class to create track connections using Create's native track placement system.
 * This hooks into Create's existing bezier creation logic.
 *
 */
public class CreateTrackConnector {

    private static final Logger LOGGER = LogUtils.getLogger();

    private record BezierEndpoint(BlockPos pos, Vec3 end, Vec3 axis, Vec3 normal) {}

    /**
     * Creates a track connection between two positions.
     *
     * @param level     The server level
     * @param firstPos  Position of the first track
     * @param secondPos Position of the second track
     * @return BezierConnection object if successful, null if failed
     */
    public static Object connectTracks(ServerLevel level, BlockPos firstPos, BlockPos secondPos) {
        return connectTracksInternal(level, firstPos, secondPos, 0);
    }

    /**
     * Creates a track connection with elevation change support.
     *
     * @param level           The server level
     * @param firstPos        Position of the first track
     * @param secondPos       Position of the second track
     * @param elevationChange The elevation change for the curve (positive = going up, negative = going down)
     * @return BezierConnection object if successful, null if failed
     */
    public static Object connectTracksWithElevation(ServerLevel level, BlockPos firstPos, BlockPos secondPos,
                                                    int elevationChange) {
        return connectTracksInternal(level, firstPos, secondPos, elevationChange);
    }

    /**
     * Creates a track connection with independent slope normals at each endpoint.
     *
     * @param level          The server level
     * @param firstPos       Position of the first track
     * @param secondPos      Position of the second track
     * @param elevationChange The elevation change for the curve
     * @param incomingSlope  Slope at the start endpoint (rise/run from previous segment)
     * @param outgoingSlope  Slope at the end endpoint (rise/run toward next segment)
     * @return BezierConnection object if successful, null if failed
     */
    @Nullable
    public static Object connectTracksWithSlopes(ServerLevel level, BlockPos firstPos, BlockPos secondPos,
                                                  int elevationChange, double incomingSlope, double outgoingSlope) {
        try {
            return createTrackConnectionWithSlopes(level, firstPos, secondPos, elevationChange,
                    incomingSlope, outgoingSlope);
        } catch (BlockEntitiesNotReadyException e) {
            throw e;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.error("[RailwaysUntold/TrackConnector] Exception: {} -> {}: {}", firstPos, secondPos, e.getMessage());
            return null;
        }
    }

    private static Object connectTracksInternal(ServerLevel level, BlockPos firstPos, BlockPos secondPos,
                                                int elevationChange) {
        try {
            return createTrackConnection(level, firstPos, secondPos, elevationChange);
        } catch (BlockEntitiesNotReadyException e) {
            throw e;
        } catch (IllegalStateException e) {
            LOGGER.error("[RailwaysUntold/TrackConnector] IllegalStateException: {} -> {}: {}", firstPos, secondPos, e.getMessage());
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.error("[RailwaysUntold/TrackConnector] Exception: {} -> {}: {}", firstPos, secondPos, e.getMessage());
            return null;
        }
    }

    private static Object createTrackConnection(ServerLevel level, BlockPos firstPos, BlockPos secondPos,
                                                 int elevationChange) throws ReflectiveOperationException {
        com.vodmordia.railwaysuntold.worldgen.integration.railway.RailwayModCompat.applyMaterialForBiome(level, firstPos);
        if (!ChunkVerificationUtil.areBoundingBoxChunksLoaded(level,
                firstPos.offset(-1, 0, -1), secondPos.offset(1, 0, 1))) {
            return null;
        }

        TrackBlockPair trackBlocks = getValidatedTrackBlocks(level, firstPos, secondPos);
        if (trackBlocks == null) {
            // getValidatedTrackBlocks already logged the specific reason (verbose). The connect is
            // retried/replanned by the caller - a genuine give-up still surfaces as [HEAD-FAIL].
            return null;
        }

        TrackGeometry geometry = calculateTrackGeometry(level, firstPos, secondPos, trackBlocks);
        if (geometry == null) {
            return null;
        }

        Vec3[] normals = calculateFinalNormals(geometry, firstPos, secondPos, elevationChange);

        BezierEndpoint first = new BezierEndpoint(firstPos, geometry.firstEnd, geometry.firstAxis, normals[0]);
        BezierEndpoint second = new BezierEndpoint(secondPos, geometry.secondEnd, geometry.secondAxis, normals[1]);

        LOGGER.debug("[TRACK-CONNECT] {} -> {}: geometry ok, firstAxis={}, secondAxis={}, firstEnd={}, secondEnd={}",
                firstPos, secondPos, geometry.firstAxis, geometry.secondAxis, geometry.firstEnd, geometry.secondEnd);

        Object curve = createBezierConnection(first, second);
        if (curve == null) {
            return null;
        }

        if (!ChunkVerificationUtil.areBoundingBoxChunksLoaded(level, firstPos, secondPos)) {
            return null;
        }

        BlockEntityLinker.linkTrackBlockEntitiesWithConnection(level, firstPos, secondPos, curve);
        return curve;
    }

    private static Object createTrackConnectionWithSlopes(ServerLevel level, BlockPos firstPos, BlockPos secondPos,
                                                           int elevationChange, double incomingSlope,
                                                           double outgoingSlope) throws ReflectiveOperationException {
        com.vodmordia.railwaysuntold.worldgen.integration.railway.RailwayModCompat.applyMaterialForBiome(level, firstPos);
        if (!ChunkVerificationUtil.areBoundingBoxChunksLoaded(level,
                firstPos.offset(-1, 0, -1), secondPos.offset(1, 0, 1))) {
            return null;
        }

        TrackBlockPair trackBlocks = getValidatedTrackBlocks(level, firstPos, secondPos);
        if (trackBlocks == null) return null;

        TrackGeometry geometry = calculateTrackGeometry(level, firstPos, secondPos, trackBlocks);
        if (geometry == null) return null;

        double horizontalDist = Math.sqrt(
                Math.pow(secondPos.getX() - firstPos.getX(), 2) +
                        Math.pow(secondPos.getZ() - firstPos.getZ(), 2));

        // Compute independent normals: start normal matches incoming slope,
        // end normal matches outgoing slope
        Vec3 firstNormal = (incomingSlope != 0.0)
                ? TrackGeometryCalculator.calculateSlopeNormal(geometry.firstNormal, geometry.firstAxis, incomingSlope)
                : (elevationChange != 0 ? TrackGeometryCalculator.calculateElevatedNormal(
                        geometry.firstNormal, geometry.firstAxis, elevationChange, horizontalDist) : geometry.firstNormal);

        Vec3 secondNormal = (outgoingSlope != 0.0)
                ? TrackGeometryCalculator.calculateSlopeNormal(geometry.secondNormal, geometry.secondAxis, outgoingSlope)
                : (elevationChange != 0 ? TrackGeometryCalculator.calculateElevatedNormal(
                        geometry.secondNormal, geometry.secondAxis, elevationChange, horizontalDist) : geometry.secondNormal);

        BezierEndpoint first = new BezierEndpoint(firstPos, geometry.firstEnd, geometry.firstAxis, firstNormal);
        BezierEndpoint second = new BezierEndpoint(secondPos, geometry.secondEnd, geometry.secondAxis, secondNormal);

        LOGGER.debug("[TRACK-CONNECT] {} -> {}: slopes in={}, out={}, firstAxis={}, secondAxis={}",
                firstPos, secondPos, incomingSlope, outgoingSlope, geometry.firstAxis, geometry.secondAxis);

        Object curve = createBezierConnection(first, second);
        if (curve == null) return null;

        if (!ChunkVerificationUtil.areBoundingBoxChunksLoaded(level, firstPos, secondPos)) return null;

        BlockEntityLinker.linkTrackBlockEntitiesWithConnection(level, firstPos, secondPos, curve);
        return curve;
    }

    private record TrackBlockPair(BlockState firstState, BlockState secondState,
                                   Object firstTrack, Object secondTrack, Class<?> iTrackBlockClass) {}

    private static TrackBlockPair getValidatedTrackBlocks(ServerLevel level, BlockPos firstPos, BlockPos secondPos) {
        BlockState firstState = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, firstPos);
        BlockState secondState = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, secondPos);

        // These connect-validation misses (a not-yet-materialized endpoint while autoload outruns
        // worldgen, etc.) are transient - the caller retries/replans and recovers. Verbose-only so they
        // don't fill a normal log; a head that genuinely can't proceed still surfaces as [HEAD-FAIL].
        if (firstState == null || secondState == null) {
            if (RailwaysUntoldConfig.isVerboseLoggingEnabled()) {
                LOGGER.warn("[TRACK-CONNECT] {} -> {}: block state null (first={}, second={})",
                        firstPos, secondPos, firstState, secondState);
            }
            return null;
        }

        Class<?> iTrackBlockClass = CreateTrackUtil.getITrackBlockClass();
        if (iTrackBlockClass == null ||
            !iTrackBlockClass.isInstance(firstState.getBlock()) ||
            !iTrackBlockClass.isInstance(secondState.getBlock())) {
            if (RailwaysUntoldConfig.isVerboseLoggingEnabled()) {
                LOGGER.warn("[TRACK-CONNECT] {} -> {}: not track blocks (first={}, second={}, iTrackBlockClass={})",
                        firstPos, secondPos, firstState, secondState,
                        iTrackBlockClass != null ? iTrackBlockClass.getSimpleName() : "null");
            }
            return null;
        }

        return new TrackBlockPair(firstState, secondState,
                firstState.getBlock(), secondState.getBlock(), iTrackBlockClass);
    }

    private record TrackGeometry(Vec3 firstAxis, Vec3 secondAxis, Vec3 firstEnd, Vec3 secondEnd,
                                  Vec3 firstNormal, Vec3 secondNormal) {}

    private static TrackGeometry calculateTrackGeometry(ServerLevel level, BlockPos firstPos, BlockPos secondPos,
                                                         TrackBlockPair trackBlocks) throws ReflectiveOperationException {
        Vec3 lookVec = Vec3.atLowerCornerOf(secondPos.subtract(firstPos)).normalize();

        Object firstAxisPair = TrackGeometryCalculator.getNearestTrackAxis(level, firstPos, trackBlocks.firstState,
                trackBlocks.firstTrack, trackBlocks.iTrackBlockClass, lookVec);
        Vec3 firstAxis = TrackGeometryCalculator.extractAxisVectorFromPair(firstAxisPair);
        Vec3 firstNormal = TrackGeometryCalculator.getTrackNormal(level, firstPos, trackBlocks.firstState,
                trackBlocks.firstTrack, trackBlocks.iTrackBlockClass);
        Vec3 firstEnd = TrackGeometryCalculator.getTrackCurveStart(level, firstPos, trackBlocks.firstState,
                trackBlocks.firstTrack, trackBlocks.iTrackBlockClass, firstAxis);

        Object secondAxisPair = TrackGeometryCalculator.getNearestTrackAxis(level, secondPos, trackBlocks.secondState,
                trackBlocks.secondTrack, trackBlocks.iTrackBlockClass, lookVec.scale(-1));
        Vec3 secondAxis = TrackGeometryCalculator.extractAxisVectorFromPair(secondAxisPair);
        Vec3 secondNormal = TrackGeometryCalculator.getTrackNormal(level, secondPos, trackBlocks.secondState,
                trackBlocks.secondTrack, trackBlocks.iTrackBlockClass);
        Vec3 secondEnd = TrackGeometryCalculator.getTrackCurveStart(level, secondPos, trackBlocks.secondState,
                trackBlocks.secondTrack, trackBlocks.iTrackBlockClass, secondAxis);

        if (firstAxis == null || secondAxis == null || firstNormal == null || secondNormal == null ||
                firstEnd == null || secondEnd == null) {
            throw BlockEntitiesNotReadyException.atPosition("geometry incomplete at " + firstPos + " -> " + secondPos);
        }

        TrackGeometryCalculator.TrackEndpointData firstEndpoint = new TrackGeometryCalculator.TrackEndpointData(
                firstAxis, firstEnd, firstPos, trackBlocks.firstState, trackBlocks.firstTrack);
        TrackGeometryCalculator.TrackEndpointData secondEndpoint = new TrackGeometryCalculator.TrackEndpointData(
                secondAxis, secondEnd, secondPos, trackBlocks.secondState, trackBlocks.secondTrack);
        TrackGeometryCalculator.AxisFlipResult flipResult = TrackGeometryCalculator.applyAxisFlipping(
                firstEndpoint, secondEndpoint, level, trackBlocks.iTrackBlockClass);

        return new TrackGeometry(flipResult.firstAxis, flipResult.secondAxis,
                flipResult.firstEnd, flipResult.secondEnd, firstNormal, secondNormal);
    }

    private static Vec3[] calculateFinalNormals(TrackGeometry geometry, BlockPos firstPos, BlockPos secondPos,
                                                 int elevationChange) {
        if (elevationChange == 0) {
            return new Vec3[]{geometry.firstNormal, geometry.secondNormal};
        }

        double horizontalDist = Math.sqrt(
                Math.pow(secondPos.getX() - firstPos.getX(), 2) +
                        Math.pow(secondPos.getZ() - firstPos.getZ(), 2)
        );

        Vec3 firstNormal = TrackGeometryCalculator.calculateElevatedNormal(
                geometry.firstNormal, geometry.firstAxis, elevationChange, horizontalDist);
        Vec3 secondNormal = TrackGeometryCalculator.calculateElevatedNormal(
                geometry.secondNormal, geometry.secondAxis, elevationChange, horizontalDist);

        return new Vec3[]{firstNormal, secondNormal};
    }

    private static Object createBezierConnection(BezierEndpoint first, BezierEndpoint second)
            throws ReflectiveOperationException {
        return CreateTrackUtil.constructBezierConnection(
                first.pos(), second.pos(),
                first.end(), second.end(),
                first.axis(), second.axis(),
                first.normal(), second.normal());
    }

}
