package com.vodmordia.railwaysuntold.worldgen.placement.placer;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.datapack.LootConfig;
import com.vodmordia.railwaysuntold.datapack.TrainDefinitionLoader;
import com.vodmordia.railwaysuntold.util.block.ChunkSafeBlockAccess;
import com.vodmordia.railwaysuntold.util.block.LightingUpdateUtil;
import com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil;
import com.vodmordia.railwaysuntold.util.chunk.ChunkVerificationUtil;
import com.vodmordia.railwaysuntold.util.spatial.DirectionUtil;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.CreateTrackUtil;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.DirectGraphConnector;
import com.vodmordia.railwaysuntold.worldgen.integration.deferred.DeferredBlockEntityCreator;
import com.vodmordia.railwaysuntold.worldgen.integration.deferred.DeferredConnectionManager;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementConstants;
import com.vodmordia.railwaysuntold.worldgen.terrain.TerrainConstants;
import com.vodmordia.railwaysuntold.worldgen.terrain.TerrainHeightUtil;
import com.vodmordia.railwaysuntold.worldgen.terrain.clearing.util.RemovalUtil;
import com.vodmordia.railwaysuntold.worldgen.tracking.BoundarySegmentData.ConnectionType;
import com.vodmordia.railwaysuntold.worldgen.tracking.ConnectedBoundaryTracker;
import com.vodmordia.railwaysuntold.worldgen.train.TrackGraphReadyListener;
import com.vodmordia.railwaysuntold.worldgen.train.TrainSchematicValidator;
import com.vodmordia.railwaysuntold.worldgen.train.TrainValidationData.BogeyLayout;
import com.vodmordia.railwaysuntold.worldgen.train.TrainValidationData.ValidationResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.Random;
import java.util.Set;

/**
 * Places a starting train on Head 1 after the initial track piece.
 * Uses the train.nbt file (Create schematic format) which contains the train contraption data.
 */
public class StartingTrainPlacer {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static TrainDefinitionLoader.ValidatedTrainEntry cachedTrainEntry = null;
    private static boolean loadAttempted = false;
    private static final int DEFAULT_TRACK_LENGTH = 8;
    private static final int PRIORITY_CONNECTION_TIMEOUT_TICKS = 20;

    /**
     * Result of train placement operation.
     */
    public static class TrainPlacementResult {
        public final boolean success;
        public final BlockPos trackExitPoint;    // Where track exits at front (for Head 1)
        public final BlockPos rearExitPoint;     // Where track exits at rear (for Head 2), null if not applicable
        public final String failureReason;

        private TrainPlacementResult(boolean success, BlockPos trackExitPoint, BlockPos rearExitPoint, String failureReason) {
            this.success = success;
            this.trackExitPoint = trackExitPoint;
            this.rearExitPoint = rearExitPoint;
            this.failureReason = failureReason;
        }

        public static TrainPlacementResult success(BlockPos frontExit, BlockPos rearExit) {
            return new TrainPlacementResult(true, frontExit, rearExit, null);
        }

        public static TrainPlacementResult failure(String reason) {
            return new TrainPlacementResult(false, null, null, reason);
        }
    }

    /**
     * Loads and validates the train data from datapack or default train.nbt.
     * Selects a train using weighted random selection filtered by biome at the placement position.
     *
     * @param level        The server level (needed for resource manager access)
     * @param placementPos The position where the train will be placed (used for biome lookup)
     * @return true if train data was loaded successfully
     */
    public static boolean ensureTrainDataLoaded(ServerLevel level, BlockPos placementPos) {
        if (loadAttempted) {
            return cachedTrainEntry != null && cachedTrainEntry.validation().valid;
        }

        loadAttempted = true;

        // Select a train from datapack definitions using weighted random by biome
        if (TrainDefinitionLoader.INSTANCE.hasValidEntries()) {
            Holder<Biome> biome = level.getBiome(placementPos);
            Random random = new Random(level.getSeed());
            TrainDefinitionLoader.ValidatedTrainEntry trainEntry = TrainDefinitionLoader.INSTANCE.getWeightedRandomTrain(random, biome);
            if (trainEntry != null) {
                cachedTrainEntry = trainEntry;
                return true;
            }
            LOGGER.warn("[STARTING-TRAIN] No datapack train matched biome at {}, falling back to default", placementPos);
        }

        // Fallback to built-in train.nbt
        ResourceLocation trainNbtLocation = new ResourceLocation("railwaysuntold", "structure/train.nbt");
        try {
            var resource = level.getServer().getResourceManager().getResource(trainNbtLocation).orElse(null);
            if (resource == null) {
                LOGGER.error("[STARTING-TRAIN] Starter train resource not found: {}", trainNbtLocation);
                return false;
            }
            net.minecraft.nbt.CompoundTag nbt;
            try (java.io.InputStream stream = resource.open()) {
                nbt = net.minecraft.nbt.NbtIo.readCompressed(stream);
            }
            ValidationResult validation = TrainSchematicValidator.loadAndValidateFlexible(nbt);
            if (!validation.valid) {
                LOGGER.error("[STARTING-TRAIN] Train data validation failed: {}", validation.errorMessage);
                return false;
            }
            cachedTrainEntry = new TrainDefinitionLoader.ValidatedTrainEntry(null, validation, nbt);
        } catch (java.io.IOException e) {
            LOGGER.error("[STARTING-TRAIN] Failed to load starter train: {}", e.getMessage(), e);
            return false;
        }

        return true;
    }

    /**
     * Places the train starting at the initial track position.
     * Creates track and then builds the train on it.
     *
     * @param level The server level
     * @param initialTrackPos Position of the initial track piece
     * @param trackDirection Direction the track runs (e.g., SOUTH means track goes toward south)
     * @return TrainPlacementResult with track entry/exit positions
     */
    public static TrainPlacementResult placeTrain(ServerLevel level,
                                                   BlockPos initialTrackPos,
                                                   Direction trackDirection) {
        if (!ensureTrainDataLoaded(level, initialTrackPos)) {
            LOGGER.error("[STARTING-TRAIN] Train data not loaded or invalid - aborting placement");
            return TrainPlacementResult.failure("Train data not loaded or invalid");
        }

        // Calculate track length based on bogey spacing
        int trackLength = calculateTrackLength();

        // Calculate track start and end positions
        // Track starts at initialTrackPos and extends in the track direction
        BlockPos trackStart = initialTrackPos;
        BlockPos trackEnd = initialTrackPos.relative(trackDirection, trackLength);

        int trainHeight = cachedTrainEntry.validation().trainHeight;
        int clearHeight = Math.max(com.vodmordia.railwaysuntold.RailwaysUntoldConfig.getVerticalExpansion(), trainHeight + 1);
        int horizontalRadius = com.vodmordia.railwaysuntold.RailwaysUntoldConfig.getHorizontalExpansion();
        int frontBuffer = 1;

        Direction perpendicular = trackDirection.getClockWise();
        BlockPos clearMin = trackStart.relative(perpendicular, -horizontalRadius);
        BlockPos extendedEnd = trackEnd.relative(trackDirection, frontBuffer);
        BlockPos clearMax = extendedEnd.relative(perpendicular, horizontalRadius).above(clearHeight);

        int minX = Math.min(clearMin.getX(), clearMax.getX());
        int minY = Math.min(clearMin.getY(), clearMax.getY());
        int minZ = Math.min(clearMin.getZ(), clearMax.getZ());
        int maxX = Math.max(clearMin.getX(), clearMax.getX());
        int maxY = Math.max(clearMin.getY(), clearMax.getY());
        int maxZ = Math.max(clearMin.getZ(), clearMax.getZ());

        clearMin = new BlockPos(minX, minY, minZ);
        clearMax = new BlockPos(maxX, maxY, maxZ);

        // Force-load all chunks in the clearing area - large trains can span many chunks
        Set<ChunkPos> requiredChunks = ChunkCoordinateUtil.getChunksInBoundingBox(clearMin, clearMax);
        for (ChunkPos cp : requiredChunks) {
            level.getChunk(cp.x, cp.z);
        }

        RemovalUtil.clearBox(level, clearMin, clearMax);

        int belowY = trackStart.getY() - 1;
        BlockPos belowMin = trackStart.relative(perpendicular, -1).atY(belowY);
        BlockPos belowMax = extendedEnd.relative(perpendicular, 1).atY(belowY);
        RemovalUtil.clearBox(level, belowMin, belowMax);

        LightingUpdateUtil.updateLightingForArea(level, clearMin, clearMax);

        // Place track blocks and connect them
        if (!placeTrainTrack(level, trackStart, trackEnd, trackDirection)) {
            LOGGER.error("[STARTING-TRAIN] Failed to place train track");
            return TrainPlacementResult.failure("Failed to place train track");
        }

        java.util.Set<BlockPos> priorityPositions = java.util.Set.of(trackStart, trackEnd);
        BlockPos trackNodePos = trackStart;

        LootConfig trainLoot = cachedTrainEntry.definition() != null
                ? cachedTrainEntry.definition().loot() : LootConfig.EMPTY;

        DeferredConnectionManager.processPriorityConnectionsAsync(level, priorityPositions, PRIORITY_CONNECTION_TIMEOUT_TICKS, success -> {
            TrackGraphReadyListener.waitForGraphAndBuildTrain(level, cachedTrainEntry.validation(), trackNodePos,
                trackDirection, null, buildResult -> {
                    if (!buildResult.success) {
                        LOGGER.error("[STARTING-TRAIN] Failed to create train: {}", buildResult.failureReason);
                    }
                }, trainLoot);
        });

        // Return both exit points - front (trackEnd) and rear (trackStart)
        // Heads should start ONE BLOCK BEYOND these positions (handled by orchestrator)
        return TrainPlacementResult.success(trackEnd, trackStart);
    }

    /**
     * Calculates the track length needed based on the full train span.
     * Mirrors the per-carriage stepping logic in TrainBuilder
     */
    private static int calculateTrackLength() {
        if (cachedTrainEntry == null || !cachedTrainEntry.validation().valid) {
            return DEFAULT_TRACK_LENGTH;
        }

        // Compute train span from entity spread (total distance between first and last entity)
        // plus within-carriage bogey spacing and wheel overhang.
        BogeyLayout layout = cachedTrainEntry.validation().bogeyLayout;
        var bogeysPerCarriage = layout.bogeysPerCarriage();
        var interCarriageSpacings = layout.interCarriageSpacings();

        // Total entity-to-entity span: sum of raw entity distances
        // (interCarriageSpacings[i] already had withinSpan subtracted, add it back)
        int totalSpan = 0;
        Direction assemblyDir = cachedTrainEntry.validation().assemblyDirection;
        var bogeys = layout.bogeys();
        int bIdx = 0;
        for (int c = 0; c < interCarriageSpacings.size(); c++) {
            int withinSpan = 0;
            int count = bogeysPerCarriage.get(c);
            if (count > 1) {
                int minP = Integer.MAX_VALUE, maxP = Integer.MIN_VALUE;
                for (int b = 0; b < count; b++) {
                    int p = DirectionUtil.getSignedPositionAlongAxis(
                            bogeys.get(bIdx + b).relativePos, assemblyDir);
                    minP = Math.min(minP, p);
                    maxP = Math.max(maxP, p);
                }
                withinSpan = maxP - minP;
            }
            totalSpan += interCarriageSpacings.get(c) + withinSpan;
            bIdx += count;
        }

        // Add within-carriage span for the last carriage
        int lastCount = bogeysPerCarriage.get(bogeysPerCarriage.size() - 1);
        if (lastCount > 1) {
            int minP = Integer.MAX_VALUE, maxP = Integer.MIN_VALUE;
            for (int b = 0; b < lastCount; b++) {
                int p = DirectionUtil.getSignedPositionAlongAxis(
                        bogeys.get(bogeys.size() - lastCount + b).relativePos, assemblyDir);
                minP = Math.min(minP, p);
                maxP = Math.max(maxP, p);
            }
            totalSpan += maxP - minP;
        }

        // Add buffer for wheel overhang and clearance
        int buffer = 6;
        return Math.max(totalSpan + buffer, DEFAULT_TRACK_LENGTH);
    }

    private static boolean placeTrainTrack(ServerLevel level, BlockPos trackStart, BlockPos trackEnd, Direction trackDir) {
        com.vodmordia.railwaysuntold.worldgen.integration.railway.RailwayModCompat.applyMaterialForBiome(level, trackStart);
        BlockState trackState = CreateTrackUtil.getStraightTrack(trackDir);

        // Place straight track blocks at every position from start to end.
        // Skip positions that already have the correct track state to preserve
        // existing graph nodes (e.g. the initial track block placed earlier).
        int dx = Integer.compare(trackEnd.getX() - trackStart.getX(), 0);
        int dz = Integer.compare(trackEnd.getZ() - trackStart.getZ(), 0);
        int distance = Math.max(
                Math.abs(trackEnd.getX() - trackStart.getX()),
                Math.abs(trackEnd.getZ() - trackStart.getZ()));

        BlockPos existingGraphPos = null;
        int placedCount = 0;
        for (int i = 0; i <= distance; i++) {
            BlockPos pos = trackStart.offset(dx * i, 0, dz * i);
            if (level.getBlockState(pos).equals(trackState)) {
                // Preserve existing track block (keeps its graph node intact)
                existingGraphPos = pos;
            } else {
                ChunkSafeBlockAccess.setBlockStateNonBlocking(level, pos, trackState, true);
            }
            placedCount++;
        }

        if (placedCount == 0) {
            LOGGER.error("[STARTING-TRAIN] No track blocks placed");
            return false;
        }

        // Connect the straight segment directly in the track graph.
        boolean connected = false;
        if (existingGraphPos != null) {
            // Connect from existing graph outward in both directions
            if (!existingGraphPos.equals(trackStart)) {
                connected = DirectGraphConnector.connectStraightDirect(level, trackStart, existingGraphPos, trackDir);
            }
            if (!existingGraphPos.equals(trackEnd)) {
                connected |= DirectGraphConnector.connectStraightDirect(level, existingGraphPos, trackEnd, trackDir);
            }
            if (connected && existingGraphPos.equals(trackStart)) {
                connected = DirectGraphConnector.connectStraightDirect(level, trackStart, trackEnd, trackDir);
            }
        }

        if (!connected) {
            // Fallback: try endpoint-to-endpoint, then track propagation
            if (!DirectGraphConnector.connectStraightDirect(level, trackStart, trackEnd, trackDir)) {
                if (com.simibubi.create.Create.RAILWAYS.trains.isEmpty()) {
                    LOGGER.warn("[STARTING-TRAIN] DirectGraphConnector failed, falling back to track propagation");
                    if (existingGraphPos != null) {
                        CreateTrackUtil.triggerTrackPropagator(level, existingGraphPos, level.getBlockState(existingGraphPos));
                    }
                    CreateTrackUtil.triggerTrackPropagator(level, trackStart, level.getBlockState(trackStart));
                    CreateTrackUtil.triggerTrackPropagator(level, trackEnd, level.getBlockState(trackEnd));
                } else {
                    LOGGER.warn("[STARTING-TRAIN] DirectGraphConnector failed with trains present, graph may be incomplete at {}", trackStart);
                }
            }
        }

        return true;
    }

    /**
     * Clears the cached train data (for testing or reload purposes).
     */
    public static void clearCache() {
        cachedTrainEntry = null;
        loadAttempted = false;
    }

    private static final int SPAWN_AVOIDANCE_DISTANCE = 2;

    /**
     * Gets the starting track position in the initial chunk, avoiding the player spawn position.
     *
     * @param level          The server level
     * @param initialChunk   The initial chunk position
     * @param config         Train world configuration
     * @param spawnPos       The world spawn position to avoid
     * @param trackDirection The direction the track will run
     * @return The block position for initial track placement
     */
    public static BlockPos getInitialTrackPosition(ServerLevel level, ChunkPos initialChunk, RailwaysUntoldConfig config,
                                                    BlockPos spawnPos, Direction trackDirection) {
        BlockPos chunkStart = initialChunk.getWorldPosition();

        var chunk = ChunkCoordinateUtil.getLoadedChunk(level, initialChunk);
        if (chunk == null) {
            return chunkStart.offset(PlacementConstants.CHUNK_CENTER_OFFSET, PlacementConstants.DEFAULT_HEIGHT, PlacementConstants.CHUNK_CENTER_OFFSET);
        }

        int baseX = PlacementConstants.CHUNK_CENTER_OFFSET;
        int baseZ = PlacementConstants.CHUNK_CENTER_OFFSET;

        BlockPos groundCheckPos = chunkStart.offset(baseX, 0, baseZ);
        int trackY = TerrainHeightUtil.getGroundLevel(level, groundCheckPos, config);

        // Check perpendicular neighbors to detect cave/ravine placement
        Direction perpLeft = DirectionUtil.getLeftDirection(trackDirection);
        Direction perpRight = DirectionUtil.getRightDirection(trackDirection);
        int perpDist = 6;

        int leftY = TerrainHeightUtil.getGroundLevel(level, groundCheckPos.relative(perpLeft, perpDist), config);
        int rightY = TerrainHeightUtil.getGroundLevel(level, groundCheckPos.relative(perpRight, perpDist), config);

        // Only correct if perpendicular samples agree (not a valley/slope)
        if (Math.abs(leftY - rightY) <= 3
                && leftY != TerrainConstants.DEFAULT_FALLBACK_HEIGHT
                && rightY != TerrainConstants.DEFAULT_FALLBACK_HEIGHT) {
            int perpAvg = (leftY + rightY) / 2;
            int correction = perpAvg - trackY;
            if (correction >= 3) {
                trackY += Math.min(correction, 3);
            }
        }

        // Also check a few blocks in each track direction for nearby water -
        // if the station is at the water's edge, the exit points need bridge height
        int forwardCheckY = TerrainHeightUtil.getGroundLevel(level,
                groundCheckPos.relative(trackDirection, 5), config);
        int backwardCheckY = TerrainHeightUtil.getGroundLevel(level,
                groundCheckPos.relative(trackDirection.getOpposite(), 5), config);
        trackY = Math.max(trackY, Math.max(forwardCheckY, backwardCheckY));

        BlockPos basePos = new BlockPos(groundCheckPos.getX(), trackY, groundCheckPos.getZ());

        return applySpawnAvoidanceOffset(basePos, spawnPos, trackDirection);
    }

    private static BlockPos applySpawnAvoidanceOffset(BlockPos basePos, BlockPos spawnPos, Direction trackDirection) {
        boolean trackRunsNorthSouth = trackDirection == Direction.NORTH || trackDirection == Direction.SOUTH;

        boolean xTooClose = Math.abs(basePos.getX() - spawnPos.getX()) < SPAWN_AVOIDANCE_DISTANCE;
        boolean zTooClose = Math.abs(basePos.getZ() - spawnPos.getZ()) < SPAWN_AVOIDANCE_DISTANCE;

        boolean needsOffset = (trackRunsNorthSouth && xTooClose) || (!trackRunsNorthSouth && zTooClose);

        if (!needsOffset) {
            return basePos;
        }

        BlockPos offsetPos;
        if (trackRunsNorthSouth) {
            offsetPos = basePos.offset(SPAWN_AVOIDANCE_DISTANCE, 0, 0);
        } else {
            offsetPos = basePos.offset(0, 0, SPAWN_AVOIDANCE_DISTANCE);
        }

        return offsetPos;
    }

    /**
     * Places the initial track block at the initial chunk.
     *
     * @param level     The server level
     * @param pos       Position to place the track
     * @param direction Initial track direction
     */
    public static void placeInitialTrack(ServerLevel level, BlockPos pos, Direction direction) {
        var chunk = ChunkCoordinateUtil.getLoadedChunk(level, pos);
        if (chunk == null) {
            return;
        }

        BlockState trackBlock = CreateTrackUtil.getStraightTrack(direction);

        ChunkSafeBlockAccess.setBlockStateNonBlocking(level, pos, trackBlock, true);

        DeferredBlockEntityCreator.scheduleDeferredCreation(level, pos, trackBlock, PlacementConstants.DEFERRED_BLOCK_ENTITY_TICKS);

        if (ChunkVerificationUtil.areGirderChunksLoaded(level, pos)) {
            CreateTrackUtil.placeGirdersUnderStraightTrack(level, pos, direction);
        }

        chunk.setUnsaved(true);

        ConnectedBoundaryTracker.markSegmentAsConnected(level, pos, pos, ConnectionType.BEZIER);
    }
}
