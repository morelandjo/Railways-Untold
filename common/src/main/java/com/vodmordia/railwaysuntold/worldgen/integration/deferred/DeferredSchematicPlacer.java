package com.vodmordia.railwaysuntold.worldgen.integration.deferred;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.datapack.LootConfig;
import com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil;
import com.vodmordia.railwaysuntold.util.spatial.RotationHelper;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.CreateTrackUtil;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.NbtSchematicLoader;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.SchematicLootApplier;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.SchematicValidator;
import com.vodmordia.railwaysuntold.worldgen.village.VillageTargetingSavedData;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Handles deferred schematic placement for large schematics spanning multiple chunks.
 */
public class DeferredSchematicPlacer extends DeferredProcessingManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Block update flags: UPDATE_CLIENTS (2) + UPDATE_KNOWN_SHAPE (16) */
    private static final int PLACEMENT_FLAGS = 18;
    private static final int MAX_BLOCKS_PER_TICK = 100;
    private static final int PROCESS_INTERVAL_TICKS = 2;

    private static final DeferredSchematicPlacer INSTANCE = new DeferredSchematicPlacer();
    private static final Map<UUID, PendingSchematic> pendingSchematics = new ConcurrentHashMap<>();

    public static void register() {
        TickEvent.SERVER_POST.register(DeferredSchematicPlacer::onServerTick);
    }

    @Override
    protected int getProcessIntervalTicks() {
        return PROCESS_INTERVAL_TICKS;
    }

    public static class PendingBlock {
        final BlockPos worldPos;
        final BlockState state;
        final CompoundTag blockEntityData;

        PendingBlock(BlockPos worldPos, BlockState state, CompoundTag blockEntityData) {
            this.worldPos = worldPos;
            this.state = state;
            this.blockEntityData = blockEntityData;
        }
    }

    public static class PendingSchematic {
        final UUID id;
        final ServerLevel level;
        final BlockPos origin;
        final Map<ChunkPos, java.util.Queue<PendingBlock>> blocksByChunk;
        final Set<ChunkPos> completedChunks;
        final Consumer<SchematicCompletionResult> onComplete;
        final BlockPos trackStart;
        final BlockPos trackEnd;
        final Direction trackDirection;

        int totalBlocks;
        int placedBlocks;

        // Cached to avoid per-block ConcurrentHashMap lookup via VillageTargetingSavedData.get()
        final VillageTargetingSavedData savedData;

        PendingSchematic(UUID id, ServerLevel level, BlockPos origin,
                         BlockPos trackStart, BlockPos trackEnd, Direction trackDirection,
                         Consumer<SchematicCompletionResult> onComplete) {
            this.id = id;
            this.level = level;
            this.origin = origin;
            this.blocksByChunk = new HashMap<>();
            this.completedChunks = new HashSet<>();
            this.trackStart = trackStart;
            this.trackEnd = trackEnd;
            this.trackDirection = trackDirection;
            this.onComplete = onComplete;
            this.savedData = VillageTargetingSavedData.get(level);
            this.totalBlocks = 0;
            this.placedBlocks = 0;
        }

        void addBlock(ChunkPos chunk, PendingBlock block) {
            blocksByChunk.computeIfAbsent(chunk, k -> new java.util.LinkedList<>()).add(block);
            totalBlocks++;
        }

        boolean isComplete() {
            return completedChunks.size() == blocksByChunk.size();
        }
    }

    public static class SchematicCompletionResult {
        public final boolean success;
        public final BlockPos origin;
        public final BlockPos trackStart;
        public final BlockPos trackEnd;
        public final Direction trackDirection;
        public final String failureReason;

        private SchematicCompletionResult(boolean success, BlockPos origin,
                                          BlockPos trackStart, BlockPos trackEnd,
                                          Direction trackDirection, String failureReason) {
            this.success = success;
            this.origin = origin;
            this.trackStart = trackStart;
            this.trackEnd = trackEnd;
            this.trackDirection = trackDirection;
            this.failureReason = failureReason;
        }

        public static SchematicCompletionResult success(BlockPos origin, BlockPos trackStart,
                                                         BlockPos trackEnd, Direction trackDirection) {
            return new SchematicCompletionResult(true, origin, trackStart, trackEnd,
                trackDirection, null);
        }

        public static SchematicCompletionResult failure(String reason) {
            return new SchematicCompletionResult(false, null, null, null, null, reason);
        }
    }

    /**
     * Queues a schematic for deferred placement.
     */
    @Nullable
    public static UUID queueSchematic(ServerLevel level,
                                       NbtSchematicLoader.LoadedSchematic schematic,
                                       SchematicValidator.SchematicValidationResult validation,
                                       BlockPos position,
                                       Rotation rotation,
                                       Consumer<SchematicCompletionResult> onComplete,
                                       LootConfig lootConfig) {

        if (schematic == null || !validation.valid) {
            LOGGER.error("[DEFERRED-SCHEMATIC] Invalid schematic or validation");
            if (onComplete != null) {
                onComplete.accept(SchematicCompletionResult.failure("Invalid schematic or validation"));
            }
            return null;
        }

        UUID schematicId = UUID.randomUUID();
        Vec3i size = schematic.getSize();

        BlockPos trackStart;
        BlockPos trackEnd;
        Direction worldTrackDir;

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

        trackStart = RotationHelper.transformPosition(localTrackStart, size, position, rotation);
        trackEnd = RotationHelper.transformPosition(localTrackEnd, size, position, rotation);
        worldTrackDir = RotationHelper.rotateDirection(origTrackDir, rotation);

        PendingSchematic pending = new PendingSchematic(
            schematicId, level, position,
            trackStart, trackEnd, worldTrackDir,
            onComplete
        );

        Map<BlockPos, CompoundTag> blockEntities = schematic.getBlockEntities();
        int airBlocks = 0;
        int solidBlocks = 0;
        int trackBlocks = 0;

        BlockPos.MutableBlockPos localPos = new BlockPos.MutableBlockPos();

        for (int y = 0; y < schematic.getHeight(); y++) {
            for (int z = 0; z < schematic.getLength(); z++) {
                for (int x = 0; x < schematic.getWidth(); x++) {
                    BlockState state = schematic.getBlock(x, y, z);
                    localPos.set(x, y, z);
                    BlockPos worldPos = RotationHelper.transformPosition(localPos, size, position, rotation);

                    // Structure voids preserve existing terrain - skip entirely
                    if (state.is(net.minecraft.world.level.block.Blocks.STRUCTURE_VOID)) {
                        continue;
                    }

                    if (state.isAir()) {
                        ChunkPos chunkPos = new ChunkPos(worldPos);
                        pending.addBlock(chunkPos, new PendingBlock(worldPos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), null));
                        airBlocks++;
                        continue;
                    }

                    if (CreateTrackUtil.isTrackBlock(state)) {
                        ChunkPos chunkPos = new ChunkPos(worldPos);
                        pending.addBlock(chunkPos, new PendingBlock(worldPos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), null));
                        trackBlocks++;
                        continue;
                    }

                    BlockState rotatedState = RotationHelper.rotateBlockState(level, worldPos, state, rotation);

                    CompoundTag beData = null;
                    if (blockEntities.containsKey(localPos)) {
                        beData = adjustBlockEntityTag(blockEntities.get(localPos), worldPos);
                    }

                    if (!lootConfig.isEmpty() && beData != null) {
                        String blockTypeId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(rotatedState.getBlock()).toString();
                        SchematicLootApplier.applyLoot(beData, lootConfig, blockTypeId, worldPos.asLong());
                    }

                    ChunkPos chunkPos = new ChunkPos(worldPos);
                    pending.addBlock(chunkPos, new PendingBlock(worldPos, rotatedState, beData));
                    solidBlocks++;
                }
            }
        }

        LOGGER.debug("[DEFERRED-SCHEMATIC] Queued schematic {} with {} blocks", schematicId, solidBlocks + airBlocks);

        pendingSchematics.put(schematicId, pending);

        return schematicId;
    }

    private static void onServerTick(MinecraftServer server) {
        if (pendingSchematics.isEmpty()) {
            return;
        }

        for (ServerLevel level : server.getAllLevels()) {
            long currentTick = level.getGameTime();
            if (!INSTANCE.shouldProcess(currentTick)) {
                continue;
            }

            processPendingSchematics(level);
        }
    }

    private static void processPendingSchematics(ServerLevel level) {
        List<UUID> completedIds = new ArrayList<>();
        BlockPlacementCounter counter = new BlockPlacementCounter(MAX_BLOCKS_PER_TICK);

        for (PendingSchematic pending : pendingSchematics.values()) {
            if (INSTANCE.shouldSkipForWorld(pending.level, level)) {
                continue;
            }

            boolean reachedLimit = processSchematicChunks(level, pending, counter);
            if (reachedLimit) {
                return;
            }

            if (pending.isComplete()) {
                completedIds.add(pending.id);
                notifySchematicComplete(pending);
            }
        }

        removeCompletedSchematics(completedIds);
    }

    private static boolean processSchematicChunks(ServerLevel level, PendingSchematic pending, BlockPlacementCounter counter) {
        for (Map.Entry<ChunkPos, java.util.Queue<PendingBlock>> entry : pending.blocksByChunk.entrySet()) {
            ChunkPos chunkPos = entry.getKey();

            if (pending.completedChunks.contains(chunkPos)) {
                continue;
            }

            LevelChunk chunk = ChunkCoordinateUtil.getLoadedChunk(level, chunkPos.x, chunkPos.z);
            if (chunk == null) {
                continue;
            }

            boolean reachedLimit = placeBlocksInChunk(level, entry.getValue(), pending, counter);
            if (reachedLimit) {
                return true;
            }

            pending.completedChunks.add(chunkPos);
            chunk.setUnsaved(true);
        }
        return false;
    }

    private static boolean placeBlocksInChunk(ServerLevel level,
                                               java.util.Queue<PendingBlock> blocks,
                                               PendingSchematic pending, BlockPlacementCounter counter) {
        PendingBlock block;
        while ((block = blocks.poll()) != null) {
            if (counter.hasReachedLimit()) {
                ((java.util.LinkedList<PendingBlock>) blocks).addFirst(block);
                return true;
            }

            placeSingleBlock(level, block, pending);
            counter.increment();
        }
        return false;
    }

    private static void placeSingleBlock(ServerLevel level,
                                          PendingBlock block, PendingSchematic pending) {
        try {
            level.setBlock(block.worldPos, block.state, PLACEMENT_FLAGS);

            // Protect non-air station blocks from terrain clearing
            if (!block.state.isAir()) {
                pending.savedData.getStationBlockProtection().protect(block.worldPos);
                pending.savedData.setDirty();
            }

            if (block.blockEntityData != null) {
                // Use level.getBlockEntity (IMMEDIATE creation mode) instead of
                // chunk.getBlockEntity (CHECK mode) to ensure the BE exists
                var blockEntity = level.getBlockEntity(block.worldPos);
                if (blockEntity != null) {
                    blockEntity.load(block.blockEntityData);
                    blockEntity.setChanged();
                    // Sync block entity data to clients so visual data (e.g. copycat materials) renders
                    level.sendBlockUpdated(block.worldPos, block.state, block.state, 3);
                }
            }

            pending.placedBlocks++;
        } catch (RuntimeException e) {
            LOGGER.warn("[DEFERRED-SCHEMATIC] Failed to place block at {}: {}", block.worldPos, e.getMessage());
        }
    }

    private static void notifySchematicComplete(PendingSchematic pending) {
        if (pending.onComplete != null) {
            pending.onComplete.accept(SchematicCompletionResult.success(
                    pending.origin, pending.trackStart, pending.trackEnd,
                    pending.trackDirection
            ));
        }
    }

    private static void removeCompletedSchematics(List<UUID> completedIds) {
        for (UUID id : completedIds) {
            pendingSchematics.remove(id);
        }
    }

    private static class BlockPlacementCounter {
        private final int maxBlocks;
        private int count = 0;

        BlockPlacementCounter(int maxBlocks) {
            this.maxBlocks = maxBlocks;
        }

        boolean hasReachedLimit() {
            return count >= maxBlocks;
        }

        void increment() {
            count++;
        }
    }

    public static void clearAll() {
        pendingSchematics.clear();
        INSTANCE.resetProcessTick();
    }

    private static CompoundTag adjustBlockEntityTag(CompoundTag original, BlockPos worldPos) {
        CompoundTag adjusted = original.copy();
        adjusted.putInt("x", worldPos.getX());
        adjusted.putInt("y", worldPos.getY());
        adjusted.putInt("z", worldPos.getZ());

        // Fix UUID format issues - Convert string UUIDs to int array format
        com.vodmordia.railwaysuntold.util.nbt.NbtHelper.convertStringUuidsToIntArrays(adjusted);

        // Fix ItemStack format: 1.20.5+ uses lowercase "count" (int), 1.20.1 expects "Count" (byte)
        com.vodmordia.railwaysuntold.util.nbt.NbtHelper.normalizeItemStackTags(adjusted);

        return adjusted;
    }

}
