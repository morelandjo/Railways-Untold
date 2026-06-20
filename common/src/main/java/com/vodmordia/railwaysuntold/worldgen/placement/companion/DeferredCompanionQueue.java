package com.vodmordia.railwaysuntold.worldgen.placement.companion;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;

/**
 * Queues companion track placements that failed due to unloaded chunks
 * and retries them when chunks become available.
 *
 */
public final class DeferredCompanionQueue {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Max placement failures (not chunk-wait cycles) before giving up. */
    private static final int MAX_PLACEMENT_FAILURES = 5;
    private static final int PROCESS_BUDGET_PER_TICK = 3;

    private static final ConcurrentLinkedQueue<PendingCompanionPlacement> pendingPlacements = new ConcurrentLinkedQueue<>();

    private DeferredCompanionQueue() {
    }

    /**
     * Register the tick handler. Called once during mod init.
     */
    public static void register() {
        TickEvent.SERVER_POST.register(DeferredCompanionQueue::onServerTick);
    }

    /**
     * Queues a failed companion placement for later retry.
     */
    public static void enqueue(BooleanSupplier placer, BlockPos start, BlockPos end, String description) {
        Set<ChunkPos> requiredChunks = ChunkCoordinateUtil.getChunksInBoundingBox(start, end);
        pendingPlacements.add(new PendingCompanionPlacement(placer, start, end, requiredChunks, description));
    }

    /**
     * Drops every pending placement. Registered with SystemStateManager so the queue does not
     * carry placer lambdas (which capture the old level) across a world unload/reload.
     */
    public static void clearAll() {
        pendingPlacements.clear();
    }

    private static void onServerTick(MinecraftServer server) {
        if (pendingPlacements.isEmpty()) {
            return;
        }
        processPending(server.overworld());
    }

    private static void processPending(ServerLevel level) {
        int processed = 0;
        List<PendingCompanionPlacement> requeue = new ArrayList<>();

        Iterator<PendingCompanionPlacement> it = pendingPlacements.iterator();
        while (it.hasNext() && processed < PROCESS_BUDGET_PER_TICK) {
            PendingCompanionPlacement pending = it.next();
            it.remove();

            boolean chunksReady = pending.requiredChunks.stream()
                    .allMatch(chunk -> ChunkCoordinateUtil.getLoadedChunk(level, chunk) != null);

            if (!chunksReady) {
                requeue.add(pending);
                continue;
            }

            try {
                boolean success = pending.placer.getAsBoolean();
                if (success) {
                    processed++;
                } else {
                    handlePlacementFailure(pending, "placement returned false", requeue);
                }
            } catch (Exception e) {
                LOGGER.warn("[COMPANION-DEFER] Exception during deferred placement of {} at {}: {}",
                        pending.description, pending.start, e.getMessage(), e);
                handlePlacementFailure(pending, e.getMessage(), requeue);
            }
        }

        pendingPlacements.addAll(requeue);
    }

    private static void handlePlacementFailure(PendingCompanionPlacement pending, String reason,
                                                List<PendingCompanionPlacement> requeue) {
        int failures = pending.failures + 1;
        if (failures >= MAX_PLACEMENT_FAILURES) {
            LOGGER.warn("[COMPANION-DEFER] Giving up on {} at {} after {} placement failures: {}",
                    pending.description, pending.start, failures, reason);
        } else {
            LOGGER.warn("[COMPANION-DEFER] Retry failed for {} at {} ({}/{}): {}",
                    pending.description, pending.start, failures, MAX_PLACEMENT_FAILURES, reason);
            requeue.add(pending.withIncrementedFailure());
        }
    }

    private record PendingCompanionPlacement(
            BooleanSupplier placer,
            BlockPos start,
            BlockPos end,
            Set<ChunkPos> requiredChunks,
            String description,
            int failures
    ) {
        PendingCompanionPlacement(BooleanSupplier placer, BlockPos start, BlockPos end,
                                  Set<ChunkPos> requiredChunks, String description) {
            this(placer, start, end, requiredChunks, description, 0);
        }

        PendingCompanionPlacement withIncrementedFailure() {
            return new PendingCompanionPlacement(placer, start, end, requiredChunks, description, failures + 1);
        }
    }
}
