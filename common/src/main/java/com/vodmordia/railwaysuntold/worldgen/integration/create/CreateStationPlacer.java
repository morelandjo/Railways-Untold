package com.vodmordia.railwaysuntold.worldgen.integration.create;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil;
import com.vodmordia.railwaysuntold.worldgen.namegen.StationNameGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Utility for placing Create train station blocks programmatically.
 */
public final class CreateStationPlacer {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Block update flags: UPDATE_CLIENTS + UPDATE_KNOWN_SHAPE
     */
    private static final int PLACEMENT_FLAGS = 3;

    /**
     * Distance from track center to station block.
     * Tracks are 3 blocks wide, so we place the station 2 blocks away from center.
     */
    private static final int STATION_DISTANCE_FROM_TRACK = 2;

    // Cached references
    private static Block stationBlock = null;
    private static boolean initAttempted = false;

    private CreateStationPlacer() {
    }

    /**
     * Initializes Create station block references.
     *
     * @return true if initialization succeeded
     */
    private static boolean ensureInitialized() {
        if (initAttempted) {
            return stationBlock != null;
        }
        initAttempted = true;

        try {
            stationBlock = CreateBlockLoader.loadBlock("track_station");
            if (stationBlock == null || stationBlock.defaultBlockState().isAir()) {
                LOGGER.error("[CREATE-STATION] Failed to load Create track_station block");
                stationBlock = null;
                return false;
            }

            return true;

        } catch (RuntimeException e) {
            LOGGER.error("[CREATE-STATION] Failed to initialize Create station references: {}", e.getMessage());
            stationBlock = null;
            return false;
        }
    }

    /**
     * Result of station placement operation.
     */
    public static class StationPlacementResult {
        public final boolean success;
        public final String errorMessage;

        private StationPlacementResult(boolean success, String errorMessage) {
            this.success = success;
            this.errorMessage = errorMessage;
        }

        public static StationPlacementResult success() {
            return new StationPlacementResult(true, null);
        }

        public static StationPlacementResult failure(String reason) {
            return new StationPlacementResult(false, reason);
        }
    }

    /**
     * Places a Create train station block adjacent to a track at a calculated position.
     *
     * @param level                   The server level
     * @param trackPos                Position of the track block the station should target
     * @param trackDirection          Direction the track runs (NORTH/SOUTH or EAST/WEST)
     * @param schematicPerpOffset     Perpendicular offset of track in the schematic (determines building side)
     * @param schematicPerpSize       Size of the schematic in the perpendicular direction
     * @param travelDirectionPositive Direction the train travels (true = positive axis direction)
     * @return Placement result
     */
    /**
     * Places a Create train station block adjacent to a track at a calculated position,
     * with an explicit station name.
     *
     * @param level                   The server level
     * @param trackPos                Position of the track block the station should target
     * @param trackDirection          Direction the track runs (NORTH/SOUTH or EAST/WEST)
     * @param schematicPerpOffset     Perpendicular offset of track in the schematic (determines building side)
     * @param schematicPerpSize       Size of the schematic in the perpendicular direction
     * @param travelDirectionPositive Direction the train travels (true = positive axis direction)
     * @param explicitName            If non-null, use this name instead of generating one
     * @return Placement result
     */
    public static StationPlacementResult placeStationOppositeBuilding(
            ServerLevel level,
            BlockPos trackPos,
            Direction trackDirection,
            int schematicPerpOffset,
            int schematicPerpSize,
            boolean travelDirectionPositive,
            @javax.annotation.Nullable String explicitName) {

        boolean buildingOnPositiveSide = schematicPerpOffset < schematicPerpSize / 2;
        Direction perpDirection = trackDirection.getClockWise();
        Direction stationSide = buildingOnPositiveSide ? perpDirection.getOpposite() : perpDirection;
        BlockPos stationPos = trackPos.relative(stationSide, STATION_DISTANCE_FROM_TRACK);

        return placeStation(level, stationPos, trackPos, travelDirectionPositive, explicitName);
    }

    /**
     * Places a Create train station block at a specific position targeting a track,
     * with an explicit station name.
     *
     * @param level                   The server level
     * @param stationPos              Where to place the station block
     * @param trackPos                Position of the track block to target
     * @param targetDirectionPositive True for POSITIVE axis direction, false for NEGATIVE
     * @param explicitName            If non-null, use this name instead of generating one
     * @return Placement result
     */
    public static StationPlacementResult placeStation(
            ServerLevel level,
            BlockPos stationPos,
            BlockPos trackPos,
            boolean targetDirectionPositive,
            @javax.annotation.Nullable String explicitName) {

        if (!ensureInitialized()) {
            return StationPlacementResult.failure("Create station block not available");
        }

        var chunk = ChunkCoordinateUtil.getLoadedChunk(level, stationPos);
        if (chunk == null) {
            return StationPlacementResult.failure("Chunk not loaded at station position");
        }
        var trackChunk = ChunkCoordinateUtil.getLoadedChunk(level, trackPos);
        if (trackChunk == null) {
            return StationPlacementResult.failure("Chunk not loaded at track position");
        }

        BlockState trackState = level.getBlockState(trackPos);
        BlockPos targetTrackOffset = trackPos.subtract(stationPos);

        BlockState stationState = stationBlock.defaultBlockState();
        level.setBlock(stationPos, stationState, PLACEMENT_FLAGS);

        BlockEntity blockEntity = level.getBlockEntity(stationPos);
        if (blockEntity == null) {
            return StationPlacementResult.failure("Block entity not created");
        }

        // Build NBT data for TrackTargetingBehaviour
        CompoundTag nbt = blockEntity.saveWithoutMetadata();
        nbt.put("TargetTrack", NbtUtils.writeBlockPos(targetTrackOffset));
        nbt.putBoolean("TargetDirection", targetDirectionPositive);
        nbt.putUUID("Id", UUID.randomUUID());

        blockEntity.load(nbt);
        blockEntity.setChanged();

        level.sendBlockUpdated(stationPos, stationState, stationState, PLACEMENT_FLAGS);
        chunk.setUnsaved(true);

        // Use non-destructive graph update - the track should already be in the graph
        // from bezier/straight connection. Propagation is only needed as fallback.
        Direction stationTrackDir = targetDirectionPositive ? Direction.SOUTH : Direction.NORTH;
        // Determine actual axis from track state shape
        String shapeName = trackState.toString();
        if (shapeName.contains("xo") || shapeName.contains("XO")) {
            stationTrackDir = targetDirectionPositive ? Direction.EAST : Direction.WEST;
        }
        if (!com.vodmordia.railwaysuntold.worldgen.integration.create.util.DirectGraphConnector
                .ensureTrackInGraph(level, trackPos, stationTrackDir)) {
            if (com.simibubi.create.Create.RAILWAYS.trains.isEmpty()) {
                triggerTrackPropagator(level, trackPos, trackState);
            } else {
                LOGGER.warn("[STATION] ensureTrackInGraph failed with trains present at {}", trackPos);
            }
        }

        if (explicitName != null) {
            setStationNameExplicit(level, stationPos, explicitName);
        } else {
            setStationName(level, stationPos);
        }

        return StationPlacementResult.success();
    }

    // Lazy-cached updateName method
    private static volatile java.lang.reflect.Method cachedUpdateNameMethod;

    private static final int MAX_NAME_RETRIES = 20;
    private static final int RETRY_DELAY_MS = 100;

    private static final java.util.concurrent.ScheduledExecutorService NAME_UPDATE_SCHEDULER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "RailwaysUntold-StationNamer");
                thread.setDaemon(true);
                return thread;
            });

    private static void setStationName(ServerLevel level, BlockPos stationPos) {
        String stationName = generateStationName(level, stationPos);
        scheduleNameUpdate(level, stationPos, stationName, 0);
    }

    /**
     * Sets a station block's name to an explicit value (no generation).
     */
    private static void setStationNameExplicit(ServerLevel level, BlockPos stationPos, String name) {
        scheduleNameUpdate(level, stationPos, name, 0);
    }

    /**
     * Generates a station name for the given position without placing anything.
     *
     * @param level      The server level
     * @param stationPos Position to base the name generation on
     * @return The generated station name
     */
    public static String generateStationName(ServerLevel level, BlockPos stationPos) {
        StationNameGenerator nameGenerator = StationNameGenerator.get(level);
        RandomSource random = RandomSource.create(stationPos.asLong());
        return nameGenerator.generateName(level, stationPos, random);
    }

    private static void scheduleNameUpdate(ServerLevel level, BlockPos stationPos, String stationName, int attempt) {
        NAME_UPDATE_SCHEDULER.schedule(() -> {
            level.getServer().execute(() -> {
                var chunk = ChunkCoordinateUtil.getLoadedChunk(level, stationPos);
                if (chunk == null) {
                    return;
                }

                BlockEntity blockEntity = level.getBlockEntity(stationPos);
                if (blockEntity == null) {
                    return;
                }

                try {
                    if (cachedUpdateNameMethod == null) {
                        cachedUpdateNameMethod = blockEntity.getClass().getMethod("updateName", String.class);
                    }
                    boolean success = (Boolean) cachedUpdateNameMethod.invoke(blockEntity, stationName);

                    if (!success && attempt < MAX_NAME_RETRIES - 1) {
                        scheduleNameUpdate(level, stationPos, stationName, attempt + 1);
                    }
                } catch (ReflectiveOperationException e) {
                    LOGGER.warn("[CREATE-STATION] Failed to update station name: {}", e.getMessage());
                }
            });
        }, RETRY_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private static void triggerTrackPropagator(ServerLevel level, BlockPos trackPos, BlockState trackState) {
        com.vodmordia.railwaysuntold.worldgen.integration.create.util.CreateTrackUtil.triggerTrackPropagator(level, trackPos, trackState);
    }

    /**
     * Checks if a block state is a Create train station block.
     *
     * @param state The block state to check
     * @return true if the block is a Create track_station
     */
    public static boolean isStationBlock(BlockState state) {
        if (!ensureInitialized() || stationBlock == null) {
            return false;
        }
        return state.is(stationBlock);
    }

    /**
     * Configures an already-placed Create station block (e.g. from a schematic) to be functional.
     * Does NOT place the block - only updates NBT, graph registration, and name.
     *
     * @param level                   The server level
     * @param stationPos              Position of the existing station block
     * @param trackPos                Position of the track block to target
     * @param targetDirectionPositive True for POSITIVE axis direction, false for NEGATIVE
     * @param explicitName            If non-null, use this name instead of generating one
     * @return Configuration result
     */
    public static StationPlacementResult configureExistingStation(
            ServerLevel level,
            BlockPos stationPos,
            BlockPos trackPos,
            boolean targetDirectionPositive,
            @javax.annotation.Nullable String explicitName) {

        if (!ensureInitialized()) {
            return StationPlacementResult.failure("Create station block not available");
        }

        var chunk = ChunkCoordinateUtil.getLoadedChunk(level, stationPos);
        if (chunk == null) {
            return StationPlacementResult.failure("Chunk not loaded at station position");
        }
        var trackChunk = ChunkCoordinateUtil.getLoadedChunk(level, trackPos);
        if (trackChunk == null) {
            return StationPlacementResult.failure("Chunk not loaded at track position");
        }

        BlockState trackState = level.getBlockState(trackPos);
        BlockPos targetTrackOffset = trackPos.subtract(stationPos);

        BlockEntity blockEntity = level.getBlockEntity(stationPos);
        if (blockEntity == null) {
            return StationPlacementResult.failure("No block entity at station position");
        }

        CompoundTag nbt = blockEntity.saveWithoutMetadata();
        nbt.put("TargetTrack", NbtUtils.writeBlockPos(targetTrackOffset));
        nbt.putBoolean("TargetDirection", targetDirectionPositive);
        nbt.putUUID("Id", UUID.randomUUID());

        blockEntity.load(nbt);
        blockEntity.setChanged();

        BlockState stationState = level.getBlockState(stationPos);
        level.sendBlockUpdated(stationPos, stationState, stationState, PLACEMENT_FLAGS);
        chunk.setUnsaved(true);

        Direction stationTrackDir = targetDirectionPositive ? Direction.SOUTH : Direction.NORTH;
        String shapeName = trackState.toString();
        if (shapeName.contains("xo") || shapeName.contains("XO")) {
            stationTrackDir = targetDirectionPositive ? Direction.EAST : Direction.WEST;
        }
        if (!com.vodmordia.railwaysuntold.worldgen.integration.create.util.DirectGraphConnector
                .ensureTrackInGraph(level, trackPos, stationTrackDir)) {
            if (com.simibubi.create.Create.RAILWAYS.trains.isEmpty()) {
                triggerTrackPropagator(level, trackPos, trackState);
            } else {
                LOGGER.warn("[STATION] ensureTrackInGraph failed with trains present at {}", trackPos);
            }
        }

        if (explicitName != null) {
            setStationNameExplicit(level, stationPos, explicitName);
        } else {
            setStationName(level, stationPos);
        }

        return StationPlacementResult.success();
    }

    /**
     * Checks if Create's train station block is available.
     *
     * @return true if the station block can be placed
     */
    public static boolean isAvailable() {
        return ensureInitialized();
    }

    /**
     * Default station names that Create assigns automatically.
     * Stations with these names will be renamed when placed by event schematics.
     */
    private static final java.util.Set<String> DEFAULT_STATION_NAMES = java.util.Set.of("My Station");

    // Lazy-cached reflection for reading station name
    private static volatile java.lang.reflect.Method cachedGetStationMethod;

    /**
     * Checks if a station block has a default name and renames it using the procedural naming system.
     * Used for station blocks placed by event schematics that may have placeholder names.
     *
     * @param level      The server level
     * @param stationPos Position of the station block
     */
    public static void renameIfDefault(ServerLevel level, BlockPos stationPos) {
        if (!ensureInitialized()) return;
        String newName = generateStationName(level, stationPos);
        scheduleDefaultNameCheck(level, stationPos, newName, 0);
    }

    private static void scheduleDefaultNameCheck(ServerLevel level, BlockPos stationPos, String newName, int attempt) {
        NAME_UPDATE_SCHEDULER.schedule(() -> {
            level.getServer().execute(() -> {
                var chunk = ChunkCoordinateUtil.getLoadedChunk(level, stationPos);
                if (chunk == null) return;

                BlockEntity blockEntity = level.getBlockEntity(stationPos);
                if (blockEntity == null) return;

                try {
                    String currentName = getStationName(blockEntity);
                    if (currentName == null && attempt < MAX_NAME_RETRIES - 1) {
                        scheduleDefaultNameCheck(level, stationPos, newName, attempt + 1);
                        return;
                    }

                    if (currentName != null && DEFAULT_STATION_NAMES.contains(currentName)) {
                        if (cachedUpdateNameMethod == null) {
                            cachedUpdateNameMethod = blockEntity.getClass().getMethod("updateName", String.class);
                        }
                        boolean success = (Boolean) cachedUpdateNameMethod.invoke(blockEntity, newName);
                        if (!success && attempt < MAX_NAME_RETRIES - 1) {
                            scheduleDefaultNameCheck(level, stationPos, newName, attempt + 1);
                        }
                    }
                } catch (ReflectiveOperationException e) {
                    LOGGER.warn("[CREATE-STATION] Failed to check/update station name: {}", e.getMessage());
                }
            });
        }, RETRY_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private static String getStationName(BlockEntity blockEntity) {
        try {
            if (cachedGetStationMethod == null) {
                cachedGetStationMethod = blockEntity.getClass().getMethod("getStation");
            }
            Object station = cachedGetStationMethod.invoke(blockEntity);
            if (station == null) return null;
            java.lang.reflect.Field nameField = station.getClass().getField("name");
            return (String) nameField.get(station);
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[CREATE-STATION] Failed to read station name via reflection: {}", e.getMessage(), e);
            return null;
        }
    }
}
