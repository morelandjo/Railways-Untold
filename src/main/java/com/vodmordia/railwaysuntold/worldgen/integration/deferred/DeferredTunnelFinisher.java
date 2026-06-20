package com.vodmordia.railwaysuntold.worldgen.integration.deferred;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil;
import com.vodmordia.railwaysuntold.worldgen.terrain.clearing.TunnelDecorationUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles deferred tunnel finishing when chunks aren't loaded during initial placement.
 * Queues finishing requests and processes them when required chunks become available.
 */
@EventBusSubscriber
public class DeferredTunnelFinisher extends DeferredProcessingManager {

    private static final int PROCESS_INTERVAL_TICKS = 5;
    private static final int MAX_DECORATIONS_PER_TICK = 50;

    private static final DeferredTunnelFinisher INSTANCE = new DeferredTunnelFinisher();

    private static final Map<ChunkPos, List<PendingDecoration>> pendingByChunk = new ConcurrentHashMap<>();

    @Override
    protected int getProcessIntervalTicks() {
        return PROCESS_INTERVAL_TICKS;
    }

    /**
     * Represents a pending tunnel decoration request.
     */
    public static class PendingDecoration {
        final ServerLevel level;
        final BlockPos centerPos;
        final Vec3 normal;
        final int segmentIndex;
        final RailwaysUntoldConfig config;

        PendingDecoration(ServerLevel level, BlockPos centerPos, Vec3 normal,
                          int segmentIndex, RailwaysUntoldConfig config) {
            this.level = level;
            this.centerPos = centerPos;
            this.normal = normal;
            this.segmentIndex = segmentIndex;
            this.config = config;
        }
    }

    /**
     * Queues a tunnel decoration for deferred processing.
     *
     * @param level        The server level
     * @param centerPos    Center position of the tunnel segment
     * @param normal       Normal vector perpendicular to track direction
     * @param segmentIndex Index of the segment (for torch interval)
     * @param config       Configuration settings
     */
    public static void queueDecoration(ServerLevel level, BlockPos centerPos, Vec3 normal,
                                        int segmentIndex, RailwaysUntoldConfig config) {
        ChunkPos chunkPos = new ChunkPos(centerPos);

        PendingDecoration pending = new PendingDecoration(
                level, centerPos, normal, segmentIndex, config
        );

        pendingByChunk.computeIfAbsent(chunkPos, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(pending);
    }

    /**
     * Server tick handler - processes pending tunnel decorations.
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (pendingByChunk.isEmpty()) {
            return;
        }

        for (ServerLevel level : event.getServer().getAllLevels()) {
            long currentTick = level.getGameTime();
            if (!INSTANCE.shouldProcess(currentTick)) {
                continue;
            }

            processPendingDecorations(level);
        }
    }

    /**
     * Processes pending decorations for chunks that are now loaded.
     */
    private static void processPendingDecorations(ServerLevel level) {
        List<ChunkPos> completedChunks = new ArrayList<>();
        int decorationsThisTick = 0;

        for (Map.Entry<ChunkPos, List<PendingDecoration>> entry : pendingByChunk.entrySet()) {
            ChunkPos chunkPos = entry.getKey();
            List<PendingDecoration> pendingList = entry.getValue();

            if (pendingList.isEmpty()) {
                completedChunks.add(chunkPos);
                continue;
            }

            if (!ChunkCoordinateUtil.areAdjacentChunksLoaded(level, chunkPos)) {
                continue;
            }

            List<PendingDecoration> toRemove = new ArrayList<>();

            synchronized (pendingList) {
                for (PendingDecoration pending : pendingList) {
                    if (decorationsThisTick >= MAX_DECORATIONS_PER_TICK) {
                        break;
                    }

                    if (INSTANCE.shouldSkipForWorld(pending.level, level)) {
                        toRemove.add(pending);
                        continue;
                    }

                    TunnelDecorationUtil.decorateSegmentDirect(
                            level, pending.centerPos, pending.normal,
                            pending.segmentIndex, pending.config
                    );

                    toRemove.add(pending);
                    decorationsThisTick++;
                }

                pendingList.removeAll(toRemove);
            }

            if (pendingList.isEmpty()) {
                completedChunks.add(chunkPos);
            }

            if (decorationsThisTick >= MAX_DECORATIONS_PER_TICK) {
                break;
            }
        }

        for (ChunkPos chunk : completedChunks) {
            pendingByChunk.remove(chunk);
        }
    }

    /**
     * Clears all pending decorations.
     * Called during world unload.
     */
    public static void clearAll() {
        pendingByChunk.clear();
        INSTANCE.resetProcessTick();
    }
}
