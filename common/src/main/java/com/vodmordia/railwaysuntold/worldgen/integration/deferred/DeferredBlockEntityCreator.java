package com.vodmordia.railwaysuntold.worldgen.integration.deferred;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles deferred creation of block entities after worldgen completes.
 */
public class DeferredBlockEntityCreator extends DeferredProcessingManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int PROCESS_INTERVAL_TICKS = 1;

    private static final DeferredBlockEntityCreator INSTANCE = new DeferredBlockEntityCreator();
    private static final int MAX_BLOCK_ENTITIES_PER_TICK = 3;
    private static final int QUEUE_SIZE_WARNING_THRESHOLD = 100;
    private static final AtomicLong lastQueueWarningTime = new AtomicLong(0);
    private static final long QUEUE_WARNING_COOLDOWN_MS = 5000; // 5 seconds

    @Override
    protected int getProcessIntervalTicks() {
        return PROCESS_INTERVAL_TICKS;
    }

    private static class DeferredBlockEntity {
        final ServerLevel level;
        final BlockPos pos;
        final BlockState state;
        final int ticksToWait;
        int ticksWaited = 0;

        DeferredBlockEntity(ServerLevel level, BlockPos pos, BlockState state, int ticksToWait) {
            this.level = level;
            this.pos = pos;
            this.state = state;
            this.ticksToWait = ticksToWait;
        }
    }

    private static final List<DeferredBlockEntity> pending = Collections.synchronizedList(new ArrayList<>());

    /**
     * Register event handlers using Architectury events.
     * Called from mod initialization.
     */
    public static void register() {
        TickEvent.SERVER_POST.register(DeferredBlockEntityCreator::onServerTick);
    }

    /**
     * Schedules a block entity to be created after a certain number of ticks.
     *
     * @param level The server level
     * @param pos The block position
     * @param state The block state
     * @param ticksToWait Number of ticks to wait before creating the block entity
     */
    public static void scheduleDeferredCreation(ServerLevel level, BlockPos pos, BlockState state, int ticksToWait) {
        synchronized (pending) {
            pending.add(new DeferredBlockEntity(level, pos, state, ticksToWait));

            if (pending.size() >= QUEUE_SIZE_WARNING_THRESHOLD) {
                long now = System.currentTimeMillis();
                long lastWarning = lastQueueWarningTime.get();
                if (now - lastWarning > QUEUE_WARNING_COOLDOWN_MS) {
                    if (lastQueueWarningTime.compareAndSet(lastWarning, now)) {
                        LOGGER.warn("[RailwaysUntold/DeferredBlockEntity] Queue size is large: {} entries (threshold: {})",
                            pending.size(), QUEUE_SIZE_WARNING_THRESHOLD);
                        LOGGER.warn("[RailwaysUntold/DeferredBlockEntity] This may cause tick lag - consider reducing track placement rate");
                    }
                }
            }
        }
    }

    public static void clearPending() {
        synchronized (pending) {
            pending.clear();
            lastQueueWarningTime.set(0);
            INSTANCE.resetProcessTick();
        }
    }

    private static void onServerTick(MinecraftServer server) {
        if (com.vodmordia.railwaysuntold.util.core.SystemStateManager.isShuttingDown()) {
            return;
        }

        if (!shouldProcessThisTick(server)) {
            return;
        }

        List<DeferredBlockEntity> toProcess = collectReadyForProcessing();
        if (toProcess.isEmpty()) {
            return;
        }

        List<DeferredBlockEntity> toRemove = new ArrayList<>();
        for (DeferredBlockEntity deferred : toProcess) {
            processDeferredBlockEntity(deferred, toRemove);
        }

        synchronized (pending) {
            pending.removeAll(toRemove);
        }
    }

    /**
     * Checks if processing should occur this tick.
     */
    private static boolean shouldProcessThisTick(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            long currentTick = level.getGameTime();
            if (INSTANCE.shouldProcess(currentTick)) {
                return true;
            }
            break;
        }
        return false;
    }

    /**
     * Collects deferred block entities that are ready for processing.
     * Increments wait counters and returns items that have waited long enough.
     */
    private static List<DeferredBlockEntity> collectReadyForProcessing() {
        List<DeferredBlockEntity> toProcess = new ArrayList<>();

        synchronized (pending) {
            if (pending.isEmpty()) {
                return toProcess;
            }

            for (DeferredBlockEntity deferred : pending) {
                deferred.ticksWaited++;

                if (deferred.ticksWaited >= deferred.ticksToWait) {
                    toProcess.add(deferred);
                    if (toProcess.size() >= MAX_BLOCK_ENTITIES_PER_TICK) {
                        break;
                    }
                }
            }
        }

        return toProcess;
    }

    /**
     * Processes a single deferred block entity creation.
     * Adds the deferred item to toRemove regardless of success/failure.
     */
    private static void processDeferredBlockEntity(DeferredBlockEntity deferred, List<DeferredBlockEntity> toRemove) {
        try {
            var chunk = ChunkCoordinateUtil.getLoadedChunk(deferred.level, deferred.pos);

            if (chunk == null) {
                LOGGER.warn("[RailwaysUntold/DeferredBlockEntity] Chunk not loaded at {}, cannot create block entity", deferred.pos);
                toRemove.add(deferred);
                return;
            }

            BlockState currentState = chunk.getBlockState(deferred.pos);
            if (!currentState.is(deferred.state.getBlock())) {
                toRemove.add(deferred);
                return;
            }

            if (currentState.hasBlockEntity()) {
                createOrUpdateBlockEntity(deferred, chunk, currentState);
            }

            toRemove.add(deferred);

        } catch (RuntimeException e) {
            LOGGER.error("[RailwaysUntold/DeferredBlockEntity] Error creating block entity at {}: {}", deferred.pos, e.getMessage());
            toRemove.add(deferred);
        }
    }

    /**
     * Creates a new block entity or updates an existing one.
     */
    private static void createOrUpdateBlockEntity(
            DeferredBlockEntity deferred,
            net.minecraft.world.level.chunk.LevelChunk chunk,
            BlockState currentState) {

        var existingBE = chunk.getBlockEntity(deferred.pos);
        if (existingBE != null) {
            sendBlockUpdate(deferred, currentState);
            return;
        }

        if (!(currentState.getBlock() instanceof EntityBlock entityBlock)) {
            return;
        }

        var blockEntity = entityBlock.newBlockEntity(deferred.pos, currentState);
        if (blockEntity == null) {
            LOGGER.warn("[RailwaysUntold/DeferredBlockEntity] Block entity creation returned null at {}", deferred.pos);
            return;
        }

        chunk.setBlockEntity(blockEntity);
        chunk.setUnsaved(true);
        sendBlockUpdate(deferred, currentState);
    }

    /**
     * Sends a block update notification to clients.
     */
    private static void sendBlockUpdate(DeferredBlockEntity deferred, BlockState currentState) {
        deferred.level.sendBlockUpdated(
                deferred.pos,
                net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                currentState,
                3);
    }
}
