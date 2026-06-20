package com.vodmordia.railwaysuntold.worldgen.integration.deferred;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil;
import com.vodmordia.railwaysuntold.worldgen.terrain.clearing.TunnelDecorationUtil;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles deferred tunnel finishing when chunks aren't loaded during initial placement.
 * Queues finishing requests and processes them when required chunks become available.
 */
public class DeferredTunnelFinisher extends DeferredProcessingManager {

    private static final int PROCESS_INTERVAL_TICKS = 5;

    private static final DeferredTunnelFinisher INSTANCE = new DeferredTunnelFinisher();

    private static final Map<UUID, List<PendingDecoration>> pendingByLevel = new ConcurrentHashMap<>();

    public static void register() {
        TickEvent.SERVER_POST.register(DeferredTunnelFinisher::onServerTick);
    }

    @Override
    protected int getProcessIntervalTicks() {
        return PROCESS_INTERVAL_TICKS;
    }

    public static class PendingDecoration {
        final ServerLevel level;
        final BlockPos centerPos;
        final Vec3 normal;
        final int segmentIndex;
        final RailwaysUntoldConfig config;

        PendingDecoration(ServerLevel level, BlockPos centerPos, Vec3 normal, int segmentIndex, RailwaysUntoldConfig config) {
            this.level = level;
            this.centerPos = centerPos;
            this.normal = normal;
            this.segmentIndex = segmentIndex;
            this.config = config;
        }
    }

    public static void queueDecoration(ServerLevel level, BlockPos centerPos, Vec3 normal,
                                        int segmentIndex, RailwaysUntoldConfig config) {
        UUID levelId = UUID.nameUUIDFromBytes(level.dimension().location().toString().getBytes());
        pendingByLevel.computeIfAbsent(levelId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(new PendingDecoration(level, centerPos, normal, segmentIndex, config));
    }

    private static void onServerTick(MinecraftServer server) {
        if (pendingByLevel.isEmpty()) {
            return;
        }

        for (ServerLevel level : server.getAllLevels()) {
            long currentTick = level.getGameTime();
            if (!INSTANCE.shouldProcess(currentTick)) {
                continue;
            }

            processPendingDecorations(level);
        }
    }

    private static void processPendingDecorations(ServerLevel level) {
        UUID levelId = UUID.nameUUIDFromBytes(level.dimension().location().toString().getBytes());
        List<PendingDecoration> pending = pendingByLevel.get(levelId);
        if (pending == null || pending.isEmpty()) {
            return;
        }

        List<PendingDecoration> toRemove = new ArrayList<>();

        synchronized (pending) {
            for (PendingDecoration decoration : pending) {
                if (INSTANCE.shouldSkipForWorld(decoration.level, level)) {
                    continue;
                }

                ChunkPos centerChunk = new ChunkPos(decoration.centerPos);
                if (ChunkCoordinateUtil.areAdjacentChunksLoaded(level, centerChunk)) {
                    TunnelDecorationUtil.decorateSegmentDirect(
                            level,
                            decoration.centerPos,
                            decoration.normal,
                            decoration.segmentIndex,
                            decoration.config);
                    toRemove.add(decoration);
                }
            }

            pending.removeAll(toRemove);
        }

        if (pending.isEmpty()) {
            pendingByLevel.remove(levelId);
        }
    }

    public static void clearAll() {
        pendingByLevel.clear();
        INSTANCE.resetProcessTick();
    }
}
