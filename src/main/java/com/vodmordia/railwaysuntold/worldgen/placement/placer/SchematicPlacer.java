package com.vodmordia.railwaysuntold.worldgen.placement.placer;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.datapack.LootConfig;
import com.vodmordia.railwaysuntold.util.block.BlockTypeUtil;
import com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil;
import com.vodmordia.railwaysuntold.util.spatial.RotationHelper;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.CreateTrackUtil;
import com.vodmordia.railwaysuntold.worldgen.placement.support.SupportConstants;
import com.vodmordia.railwaysuntold.worldgen.village.StationBlockProtection;
import com.vodmordia.railwaysuntold.worldgen.village.VillageTargetingSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.Map;

/**
 * Places a loaded schematic into the world.
 */
public class SchematicPlacer {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int PLACEMENT_FLAGS = 18; // UPDATE_CLIENTS (2) + UPDATE_KNOWN_SHAPE (16)

    /**
     * Result of schematic placement operation.
     *
     */
    public static class SchematicPlacementResult {
        public final boolean success;
        public final boolean isDeadEnd;
        public final boolean hasStart;
        @javax.annotation.Nullable
        public final Direction startDirection; // world-space direction for the new head (after rotation)
        public final BlockPos stationPosition;
        /** One end of the station track. When used as placed station, this is the ENTRY point. */
        public final BlockPos trackStart;
        /** Other end of the station track. When used as placed station, this is the EXIT point. */
        public final BlockPos trackEnd;
        public final Direction trackDirection; // Direction along which track runs
        public final Rotation rotation; // Rotation applied to the schematic
        public final String failureReason;

        public SchematicPlacementResult(boolean success, boolean isDeadEnd, BlockPos stationPosition,
                                        BlockPos trackStart, BlockPos trackEnd,
                                        Direction trackDirection, Rotation rotation,
                                        String failureReason) {
            this(success, isDeadEnd, false, null, stationPosition, trackStart, trackEnd, trackDirection, rotation, failureReason);
        }

        public SchematicPlacementResult(boolean success, boolean isDeadEnd, boolean hasStart,
                                        @javax.annotation.Nullable Direction startDirection,
                                        BlockPos stationPosition,
                                        BlockPos trackStart, BlockPos trackEnd,
                                        Direction trackDirection, Rotation rotation,
                                        String failureReason) {
            this.success = success;
            this.isDeadEnd = isDeadEnd;
            this.hasStart = hasStart;
            this.startDirection = startDirection;
            this.stationPosition = stationPosition;
            this.trackStart = trackStart;
            this.trackEnd = trackEnd;
            this.trackDirection = trackDirection;
            this.rotation = rotation;
            this.failureReason = failureReason;
        }

        public static SchematicPlacementResult success(BlockPos stationPosition, BlockPos trackStart,
                                                       BlockPos trackEnd, Direction trackDirection,
                                                       Rotation rotation) {
            return new SchematicPlacementResult(true, false, false, null, stationPosition, trackStart, trackEnd, trackDirection, rotation, null);
        }

        public static SchematicPlacementResult successDeadEnd(BlockPos stationPosition, BlockPos trackStart,
                                                               BlockPos trackEnd, Direction trackDirection,
                                                               Rotation rotation) {
            return new SchematicPlacementResult(true, true, false, null, stationPosition, trackStart, trackEnd, trackDirection, rotation, null);
        }

        public static SchematicPlacementResult successStart(BlockPos stationPosition, BlockPos trackStart,
                                                             BlockPos trackEnd, Direction trackDirection,
                                                             Rotation rotation, Direction startDirection) {
            return new SchematicPlacementResult(true, false, true, startDirection, stationPosition, trackStart, trackEnd, trackDirection, rotation, null);
        }

        public static SchematicPlacementResult failure(String reason) {
            return new SchematicPlacementResult(false, false, false, null, null, null, null, null, null, reason);
        }
    }

    /**
     * Places a schematic at the given position with the specified rotation.
     *
     * @param level                 The server level
     * @param schematic             The loaded schematic to place
     * @param validation            The validation result containing track info
     * @param position              The world position for the schematic origin (corner)
     * @param rotation              The rotation to apply
     * @param config                Configuration for clearing settings
     * @param preserveExistingTrack If true, existing track blocks in the world are not cleared
     *                              when the schematic has track/air at that position
     * @param lootConfig            Loot table configuration for containers in this schematic
     * @return Placement result with track positions
     */
    public static SchematicPlacementResult place(ServerLevel level,
                                                 NbtSchematicLoader.LoadedSchematic schematic,
                                                 SchematicValidator.SchematicValidationResult validation,
                                                 BlockPos position,
                                                 Rotation rotation,
                                                 RailwaysUntoldConfig config,
                                                 boolean preserveExistingTrack,
                                                 LootConfig lootConfig) {

        if (schematic == null || !validation.valid) {
            LOGGER.warn("[SCHEMATIC-PLACER] Invalid schematic or validation - schematic:{}, validation:{}",
                    schematic != null, validation != null ? validation.valid : "null");
            return SchematicPlacementResult.failure("Invalid schematic or validation");
        }

        Vec3i rotatedSize = RotationHelper.getRotatedSize(schematic.getSize(), rotation);
        int stationVerticalClear = config.STATION_CLEARING_VERTICAL_HEIGHT;
        int stationAdjacentClear = config.STATION_CLEARING_ADJACENT_RADIUS;

        BlockPos endPos = position.offset(
                Math.abs(rotatedSize.getX()) - 1,
                Math.abs(rotatedSize.getY()) - 1,
                Math.abs(rotatedSize.getZ()) - 1
        );

        BlockPos clearMinPos = position.offset(-stationAdjacentClear, 0, -stationAdjacentClear);
        BlockPos clearMaxPos = endPos.offset(stationAdjacentClear, stationVerticalClear, stationAdjacentClear);

        if (!areChunksLoaded(level, clearMinPos, clearMaxPos)) {
            return SchematicPlacementResult.failure("Not all chunks loaded for schematic placement");
        }

        placeSchematicBlocks(level, schematic, position, rotation, preserveExistingTrack);
        placeFoundationFill(level, position, rotatedSize);
        clearAreaAboveStation(level, clearMinPos, rotatedSize, stationVerticalClear, stationAdjacentClear);
        processBlockEntities(level, schematic, position, rotation, lootConfig);
        placeEntities(level, schematic, position, rotation);
        markChunksUnsaved(level, position, endPos);

        return calculateTrackEndpoints(schematic, validation, position, rotation);
    }

    private static void placeSchematicBlocks(ServerLevel level,
                                             NbtSchematicLoader.LoadedSchematic schematic,
                                             BlockPos position,
                                             Rotation rotation,
                                             boolean preserveExistingTrack) {
        StationBlockProtection protection = VillageTargetingSavedData.get(level).getStationBlockProtection();
        BlockPos.MutableBlockPos localPos = new BlockPos.MutableBlockPos();
        for (int y = 0; y < schematic.getHeight(); y++) {
            for (int z = 0; z < schematic.getLength(); z++) {
                for (int x = 0; x < schematic.getWidth(); x++) {
                    BlockState state = schematic.getBlock(x, y, z);
                    localPos.set(x, y, z);
                    BlockPos worldPos = RotationHelper.transformPosition(localPos, schematic.getSize(), position, rotation);

                    if (state.is(Blocks.STRUCTURE_VOID)) {
                        continue;
                    }

                    if (state.getBlock() instanceof com.vodmordia.railwaysuntold.blocks.core.DeadEndBlock
                            || state.getBlock() instanceof com.vodmordia.railwaysuntold.blocks.core.StartBlock) {
                        continue;
                    }

                    if (state.isAir()) {
                        if (preserveExistingTrack && CreateTrackUtil.isTrackBlock(level.getBlockState(worldPos))) {
                            continue;
                        }
                        clearBlockIfNotAir(level, worldPos);
                        continue;
                    }

                    if (CreateTrackUtil.isTrackBlock(state)) {
                        // When preserving existing track, don't overwrite track already in the world
                        if (preserveExistingTrack && CreateTrackUtil.isTrackBlock(level.getBlockState(worldPos))) {
                            continue;
                        }
                        // Place track blocks from the schematic so events with continuous
                        // track keep their track. Edge-only schematics (broken track) have
                        // air in the middle, which is handled by the air branch above.
                        clearBlockIfNotAir(level, worldPos);
                        level.setBlock(worldPos, RotationHelper.rotateBlockState(level, worldPos, state, rotation), PLACEMENT_FLAGS);
                        continue;
                    }

                    BlockState rotatedState = RotationHelper.rotateBlockState(level, worldPos, state, rotation);
                    level.setBlock(worldPos, rotatedState, PLACEMENT_FLAGS);
                    protection.protect(worldPos);
                }
            }
        }
        VillageTargetingSavedData.get(level).setDirty();
    }

    private static void clearBlockIfNotAir(ServerLevel level, BlockPos worldPos) {
        BlockState existingState = level.getBlockState(worldPos);
        if (!existingState.isAir()) {
            level.setBlock(worldPos, Blocks.AIR.defaultBlockState(), PLACEMENT_FLAGS);
        }
    }

    private static void clearAreaAboveStation(ServerLevel level,
                                              BlockPos clearMinPos,
                                              Vec3i rotatedSize,
                                              int stationVerticalClear,
                                              int stationAdjacentClear) {
        int clearSizeX = Math.abs(rotatedSize.getX()) + (stationAdjacentClear * 2);
        int clearSizeZ = Math.abs(rotatedSize.getZ()) + (stationAdjacentClear * 2);
        int baseY = Math.abs(rotatedSize.getY());

        for (int y = 0; y < stationVerticalClear; y++) {
            for (int z = 0; z < clearSizeZ; z++) {
                for (int x = 0; x < clearSizeX; x++) {
                    BlockPos worldPos = clearMinPos.offset(x, baseY + y, z);
                    clearBlockIfNotAir(level, worldPos);
                }
            }
        }
    }

    /**
     * Fills air below the station footprint with foundation blocks.
     * For each column in the station footprint, scans downward from the bottom
     * of the station and places foundation blocks until solid ground is reached.
     */
    private static void placeFoundationFill(ServerLevel level, BlockPos position, Vec3i rotatedSize) {
        BlockState foundationBlock = SupportConstants.getStationFoundationBlock(level, position);
        if (foundationBlock == null) return;

        StationBlockProtection protection = VillageTargetingSavedData.get(level).getStationBlockProtection();
        int baseY = position.getY();
        int sizeX = Math.abs(rotatedSize.getX());
        int sizeZ = Math.abs(rotatedSize.getZ());
        int maxDepth = 64;

        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                int worldX = position.getX() + x;
                int worldZ = position.getZ() + z;

                for (int depth = 1; depth <= maxDepth; depth++) {
                    int y = baseY - depth;
                    if (y < level.getMinBuildHeight()) break;

                    mutable.set(worldX, y, worldZ);
                    BlockState existing = level.getBlockState(mutable);

                    if (!existing.isAir() && !existing.canBeReplaced()
                            && !BlockTypeUtil.isTreeOrWood(existing)) {
                        break;
                    }

                    level.setBlock(mutable.immutable(), foundationBlock, PLACEMENT_FLAGS);
                    protection.protect(mutable.immutable());
                }
            }
        }
    }

    private static void processBlockEntities(ServerLevel level,
                                             NbtSchematicLoader.LoadedSchematic schematic,
                                             BlockPos position,
                                             Rotation rotation,
                                             LootConfig lootConfig) {
        for (Map.Entry<BlockPos, CompoundTag> entry : schematic.getBlockEntities().entrySet()) {
            BlockPos localPos = entry.getKey();
            BlockPos worldPos = RotationHelper.transformPosition(localPos, schematic.getSize(), position, rotation);

            BlockState worldState = level.getBlockState(worldPos);
            if (CreateTrackUtil.isTrackBlock(worldState)) {
                continue;
            }

            CompoundTag beNbt = entry.getValue();

            // Apply loot table to container NBT before loading into block entity
            if (!lootConfig.isEmpty()) {
                String blockTypeId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(worldState.getBlock()).toString();
                SchematicLootApplier.applyLoot(beNbt, lootConfig, blockTypeId, worldPos.asLong());
            }

            // Use level.getBlockEntity (IMMEDIATE creation mode) instead of chunk.getBlockEntity
            // (CHECK mode) to ensure the block entity is created if it doesn't exist yet
            var blockEntity = level.getBlockEntity(worldPos);
            if (blockEntity != null) {
                loadBlockEntityData(level, blockEntity, beNbt, worldPos);
            }
        }
    }

    /**
     * Spawns the schematic's saved entities (minecarts, item frames, armor stands, etc.) into the
     * world, transforming each entity's local position and yaw by the same rotation the blocks get.
     */
    public static void placeEntities(ServerLevel level,
                                     NbtSchematicLoader.LoadedSchematic schematic,
                                     BlockPos position,
                                     Rotation rotation) {
        for (CompoundTag entry : schematic.getEntities()) {
            spawnSchematicEntity(level, entry, schematic.getSize(), position, rotation);
        }
    }

    private static void spawnSchematicEntity(ServerLevel level,
                                             CompoundTag entry,
                                             Vec3i schematicSize,
                                             BlockPos position,
                                             Rotation rotation) {
        if (!entry.contains("nbt", Tag.TAG_COMPOUND)) {
            return;
        }

        Vec3 localPos = readLocalEntityPos(entry);
        Vec3 worldPos = transformEntityPos(localPos, schematicSize, position, rotation);

        // Drop the export-world Pos and UUID: position comes from the transform below, and a fresh
        // UUID avoids collisions when the same event is placed more than once.
        CompoundTag entityNbt = entry.getCompound("nbt").copy();
        entityNbt.remove("Pos");
        entityNbt.remove("UUID");

        try {
            // loadEntityRecursive builds the entity and any passengers but does not add them to the
            // world; we reposition the root and add it (passengers ride along via addFreshEntityWithPassengers).
            Entity entity = EntityType.loadEntityRecursive(entityNbt, level, e -> e);
            if (entity != null) {
                float yaw = entity.rotate(rotation);
                entity.moveTo(worldPos.x, worldPos.y, worldPos.z, yaw, entity.getXRot());
                level.addFreshEntityWithPassengers(entity);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("[SCHEMATIC-PLACER] Failed to spawn entity at {}: {}", worldPos, e.getMessage());
        }
    }

    /**
     * Reads an entity entry's fractional local position. Falls back to the block-center of
     * {@code blockPos} when the precise {@code pos} list is absent.
     */
    private static Vec3 readLocalEntityPos(CompoundTag entry) {
        if (entry.contains("pos", Tag.TAG_LIST)) {
            ListTag pos = entry.getList("pos", Tag.TAG_DOUBLE);
            if (pos.size() >= 3) {
                return new Vec3(pos.getDouble(0), pos.getDouble(1), pos.getDouble(2));
            }
        }
        if (entry.contains("blockPos", Tag.TAG_LIST)) {
            ListTag bp = entry.getList("blockPos", Tag.TAG_INT);
            if (bp.size() >= 3) {
                return new Vec3(bp.getInt(0) + 0.5, bp.getInt(1), bp.getInt(2) + 0.5);
            }
        }
        return Vec3.ZERO;
    }

    /**
     * Continuous analogue of {@link RotationHelper#transformPosition}: rotates a fractional local
     * position about the schematic so entities land exactly where the rotated blocks do.
     */
    static Vec3 transformEntityPos(Vec3 local, Vec3i schematicSize, BlockPos worldOrigin, Rotation rotation) {
        int width = schematicSize.getX();
        int length = schematicSize.getZ();
        double tx;
        double tz;
        switch (rotation) {
            case CLOCKWISE_90 -> {
                tx = length - local.z;
                tz = local.x;
            }
            case CLOCKWISE_180 -> {
                tx = width - local.x;
                tz = length - local.z;
            }
            case COUNTERCLOCKWISE_90 -> {
                tx = local.z;
                tz = width - local.x;
            }
            default -> {
                tx = local.x;
                tz = local.z;
            }
        }
        return new Vec3(worldOrigin.getX() + tx, worldOrigin.getY() + local.y, worldOrigin.getZ() + tz);
    }

    private static void loadBlockEntityData(ServerLevel level,
                                            net.minecraft.world.level.block.entity.BlockEntity blockEntity,
                                            CompoundTag originalTag,
                                            BlockPos worldPos) {
        try {
            CompoundTag adjustedTag = adjustBlockEntityTag(originalTag, worldPos);
            blockEntity.loadWithComponents(adjustedTag, level.registryAccess());
            blockEntity.setChanged();
            // Sync block entity data to clients (spawners, signs, jigsaw blocks, etc.)
            BlockState state = level.getBlockState(worldPos);
            level.sendBlockUpdated(worldPos, state, state, 3);
        } catch (RuntimeException e) {
            LOGGER.warn("[SCHEMATIC-PLACER] Failed to load block entity at {}, using defaults: {}", worldPos, e.getMessage());
        }
    }

    private static SchematicPlacementResult calculateTrackEndpoints(
            NbtSchematicLoader.LoadedSchematic schematic,
            SchematicValidator.SchematicValidationResult validation,
            BlockPos position,
            Rotation rotation) {

        Direction origTrackDir = validation.trackDirection;
        int trackY = validation.trackY;
        int perpOffset = validation.trackPerpOffset;

        BlockPos localTrackStart;
        BlockPos localTrackEnd;

        if (origTrackDir == Direction.SOUTH || origTrackDir == Direction.NORTH) {
            localTrackStart = new BlockPos(perpOffset, trackY, 0);
            localTrackEnd = new BlockPos(perpOffset, trackY, schematic.getLength() - 1);
        } else {
            localTrackStart = new BlockPos(0, trackY, perpOffset);
            localTrackEnd = new BlockPos(schematic.getWidth() - 1, trackY, perpOffset);
        }

        BlockPos trackStart = RotationHelper.transformPosition(localTrackStart, schematic.getSize(), position, rotation);
        BlockPos trackEnd = RotationHelper.transformPosition(localTrackEnd, schematic.getSize(), position, rotation);
        Direction worldTrackDir = RotationHelper.rotateDirection(origTrackDir, rotation);

        if (validation.isDeadEnd) {
            return SchematicPlacementResult.successDeadEnd(position, trackStart, trackEnd, worldTrackDir, rotation);
        }
        if (validation.hasStart) {
            Direction worldStartDir = RotationHelper.rotateDirection(validation.startDirection, rotation);
            return SchematicPlacementResult.successStart(position, trackStart, trackEnd, worldTrackDir, rotation, worldStartDir);
        }
        return SchematicPlacementResult.success(position, trackStart, trackEnd, worldTrackDir, rotation);
    }

    private static CompoundTag adjustBlockEntityTag(CompoundTag original, BlockPos worldPos) {
        CompoundTag adjusted = original.copy();
        adjusted.putInt("x", worldPos.getX());
        adjusted.putInt("y", worldPos.getY());
        adjusted.putInt("z", worldPos.getZ());
        com.vodmordia.railwaysuntold.util.nbt.NbtHelper.convertStringUuidsToIntArrays(adjusted);

        return adjusted;
    }

    private static boolean areChunksLoaded(ServerLevel level, BlockPos min, BlockPos max) {
        int minChunkX = min.getX() >> 4;
        int minChunkZ = min.getZ() >> 4;
        int maxChunkX = max.getX() >> 4;
        int maxChunkZ = max.getZ() >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (ChunkCoordinateUtil.getLoadedChunk(level, cx, cz) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void markChunksUnsaved(ServerLevel level, BlockPos min, BlockPos max) {
        int minChunkX = min.getX() >> 4;
        int minChunkZ = min.getZ() >> 4;
        int maxChunkX = max.getX() >> 4;
        int maxChunkZ = max.getZ() >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                LevelChunk chunk = ChunkCoordinateUtil.getLoadedChunk(level, cx, cz);
                if (chunk != null) {
                    chunk.setUnsaved(true);
                }
            }
        }
    }

    /**
     * Inverse of the forward place+calculateTrackEndpoints flow: given a desired world position
     * for the station's track-entry block, returns the schematic placement origin such that
     * placing the schematic at that origin lands the "entry-side" local track endpoint exactly
     * at desiredWorldTrackEntry.
     *
     */
    public static BlockPos inverseEntryTransform(
            BlockPos desiredWorldTrackEntry,
            Direction headDir,
            NbtSchematicLoader.LoadedSchematic schematic,
            SchematicValidator.SchematicValidationResult validation,
            Rotation rotation) {

        Direction origTrackDir = validation.trackDirection;
        int trackY = validation.trackY;
        int perpOffset = validation.trackPerpOffset;

        BlockPos localTrackStart;
        BlockPos localTrackEnd;
        if (origTrackDir == Direction.SOUTH || origTrackDir == Direction.NORTH) {
            localTrackStart = new BlockPos(perpOffset, trackY, 0);
            localTrackEnd = new BlockPos(perpOffset, trackY, schematic.getLength() - 1);
        } else {
            localTrackStart = new BlockPos(0, trackY, perpOffset);
            localTrackEnd = new BlockPos(schematic.getWidth() - 1, trackY, perpOffset);
        }

        BlockPos startOffset = RotationHelper.transformPosition(
                localTrackStart, schematic.getSize(), BlockPos.ZERO, rotation);
        BlockPos endOffset = RotationHelper.transformPosition(
                localTrackEnd, schematic.getSize(), BlockPos.ZERO, rotation);

        int projStart = startOffset.getX() * headDir.getStepX() + startOffset.getZ() * headDir.getStepZ();
        int projEnd = endOffset.getX() * headDir.getStepX() + endOffset.getZ() * headDir.getStepZ();
        BlockPos entryOffset = (projStart < projEnd) ? startOffset : endOffset;

        return desiredWorldTrackEntry.subtract(entryOffset);
    }

    /**
     * Calculates the rotation needed to align a schematic's track direction with a target direction.
     *
     * @param schematicTrackDir The direction the track runs in the schematic (from validation)
     * @param targetTrackDir    The desired track direction in the world
     * @return The rotation to apply
     */
    public static Rotation calculateRotationForAlignment(Direction schematicTrackDir, Direction targetTrackDir) {
        // A straight station track runs both ways, so it only needs aligning to the head's axis: same
        // axis (same or opposite direction) is already aligned; a perpendicular target needs one 90° turn.
        // CLOCKWISE_90 serves both perpendicular cases since the track is symmetric under 180°.
        if (schematicTrackDir.getAxis() == targetTrackDir.getAxis()) {
            return Rotation.NONE;
        }
        return Rotation.CLOCKWISE_90;
    }
}
