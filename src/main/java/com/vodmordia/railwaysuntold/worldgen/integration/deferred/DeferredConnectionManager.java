package com.vodmordia.railwaysuntold.worldgen.integration.deferred;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.ServerTickHandler;
import com.vodmordia.railwaysuntold.util.block.ChunkSafeBlockAccess;
import com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil;
import com.vodmordia.railwaysuntold.util.chunk.ChunkVerificationUtil;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.CreateTrackUtil;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.DirectGraphConnector;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.LinkedList;
import java.util.Queue;

/**
 * Manages deferred execution of TrackBlockEntity.addConnection() calls.
 * Create mod's addConnection() method is unsafe to call during chunk loading.
 */
@EventBusSubscriber
public class DeferredConnectionManager extends DeferredProcessingManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int MAX_QUEUE_SIZE_WARNING = 1000;
    private static final int MAX_CONNECTIONS_PER_TICK = 10;
    private static final int MAX_RETRIES = 60;

    /**
     * Retry cap for a connection whose endpoint is loaded but not a track block (reverted to air,
     * cleared, or rolled back). The track is gone and can't reappear on its own, so there's no
     * point burning the full {@link #MAX_RETRIES} budget - discard after a short grace that only
     * absorbs same-tick placement/processing ordering.
     */
    private static final int UNRECOVERABLE_RETRIES = 3;

    // Lazy-cached reflection for addConnection and secondary
    private static volatile java.lang.reflect.Method cachedAddConnectionMethod;
    private static volatile java.lang.reflect.Method cachedSecondaryMethod;

    private static final int PROCESS_INTERVAL_TICKS = 1;

    private static final DeferredConnectionManager INSTANCE = new DeferredConnectionManager();
    private static final Queue<PendingConnection> connectionQueue = new LinkedList<>();

    @Override
    protected int getProcessIntervalTicks() {
        return PROCESS_INTERVAL_TICKS;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (com.vodmordia.railwaysuntold.util.core.SystemStateManager.isShuttingDown()) {
            return;
        }

        for (ServerLevel level : event.getServer().getAllLevels()) {
            processPendingConnections(level);
        }
    }

    /**
     * Represents a pending connection between two track block entities.
     */
    private static class PendingConnection {
        final ServerLevel level;
        final BlockPos firstPos;
        final BlockPos secondPos;
        final Object bezierConnection; // BezierConnection object from Create mod
        int retryCount = 0;

        PendingConnection(ServerLevel level, BlockPos firstPos, BlockPos secondPos, Object bezierConnection) {
            this.level = level;
            this.firstPos = firstPos;
            this.secondPos = secondPos;
            this.bezierConnection = bezierConnection;
        }
    }

    /**
     * Queues a connection to be established on the main thread during tick.
     *
     * @param level            The server level
     * @param firstPos         Position of first track block entity
     * @param secondPos        Position of second track block entity
     * @param bezierConnection The BezierConnection object to add
     */
    public static void queueConnection(ServerLevel level, BlockPos firstPos, BlockPos secondPos, Object bezierConnection) {
        synchronized (connectionQueue) {
            connectionQueue.add(new PendingConnection(level, firstPos, secondPos, bezierConnection));

            int queueSize = connectionQueue.size();
            if (queueSize > MAX_QUEUE_SIZE_WARNING) {
                LOGGER.warn("[RailwaysUntold/DeferredConnection] Queue size is {} (warning threshold: {}). " +
                                "Connections may be queuing faster than they can be processed.",
                        queueSize, MAX_QUEUE_SIZE_WARNING);
            }
        }
    }

    /**
     * Processes pending connections on the main server thread.
     *
     * @param level The server level
     */
    public static void processPendingConnections(ServerLevel level) {
        long currentTick = level.getGameTime();
        if (!INSTANCE.shouldProcess(currentTick)) {
            return;
        }

        int processed = 0;
        int failed = 0;

        for (int i = 0; i < MAX_CONNECTIONS_PER_TICK; i++) {
            PendingConnection pending;

            synchronized (connectionQueue) {
                if (connectionQueue.isEmpty()) {
                    break;
                }
                pending = connectionQueue.poll();
            }

            if (pending == null) {
                break;
            }

            if (!INSTANCE.isSameDimension(pending.level, level)) {
                synchronized (connectionQueue) {
                    connectionQueue.add(pending);
                }
                continue;
            }

            if (INSTANCE.isStaleLevel(pending.level, level)) {
                continue;
            }

            try {
                if (processConnection(pending)) {
                    processed++;
                } else {
                    failed++;
                }
            } catch (RuntimeException e) {
                LOGGER.error("[RailwaysUntold/DeferredConnection] Failed to process connection {} -> {}: {}",
                        pending.firstPos, pending.secondPos, e.getMessage());
                failed++;
            }
        }
    }

    /**
     * Processes a single pending connection.
     *
     * @param pending The pending connection to process
     * @return true if successful, false if chunks not loaded or block entities missing
     */
    private static boolean processConnection(PendingConnection pending) {
        if (!ChunkVerificationUtil.areBoundingBoxChunksLoaded(
                pending.level, pending.firstPos, pending.secondPos)) {
            requeueWithRetryLimit(pending, "chunks-not-loaded", MAX_RETRIES);
            return false;
        }

        var firstChunk = ChunkCoordinateUtil.getLoadedChunk(pending.level, pending.firstPos);
        var secondChunk = ChunkCoordinateUtil.getLoadedChunk(pending.level, pending.secondPos);
        if (firstChunk == null || secondChunk == null) {
            requeueWithRetryLimit(pending, "chunk-null", MAX_RETRIES);
            return false;
        }
        BlockEntity firstBE = firstChunk.getBlockEntity(pending.firstPos);
        BlockEntity secondBE = secondChunk.getBlockEntity(pending.secondPos);

        if (firstBE == null || secondBE == null) {
            // A BE-less endpoint whose block isn't even a track block anymore (reverted to air,
            // cleared, or rolled back) can never connect - the track is gone. Fast-fail those
            // instead of burning the full retry budget. A track-state-but-no-BE endpoint is a
            // transient case (BE not yet created) and keeps the normal budget. Include the actual
            // block in the cause so a discard stays diagnosable.
            BlockState firstState = firstBE == null ? firstChunk.getBlockState(pending.firstPos) : null;
            BlockState secondState = secondBE == null ? secondChunk.getBlockState(pending.secondPos) : null;
            boolean unrecoverable =
                    (firstState != null && !CreateTrackUtil.isTrackBlock(firstState))
                            || (secondState != null && !CreateTrackUtil.isTrackBlock(secondState));

            StringBuilder cause = new StringBuilder("no-block-entity[");
            if (firstState != null) {
                cause.append("first=").append(blockName(firstState));
            }
            if (secondState != null) {
                if (firstState != null) cause.append(' ');
                cause.append("second=").append(blockName(secondState));
            }
            cause.append(']');
            requeueWithRetryLimit(pending, cause.toString(),
                    unrecoverable ? UNRECOVERABLE_RETRIES : MAX_RETRIES);
            return false;
        }

        try {
            if (!CreateTrackUtil.isTrackBlockEntity(firstBE) ||
                    !CreateTrackUtil.isTrackBlockEntity(secondBE)) {
                return false;
            }

            ensureConnectionMethodsCached();
            cachedAddConnectionMethod.invoke(firstBE, pending.bezierConnection);

            Object secondaryConnection = cachedSecondaryMethod.invoke(pending.bezierConnection);
            cachedAddConnectionMethod.invoke(secondBE, secondaryConnection);

            // Try non-destructive direct graph connection first (avoids train stuttering)
            boolean directSuccess = DirectGraphConnector.connectBezierDirect(
                    pending.level, pending.firstPos, pending.secondPos, pending.bezierConnection);
            if (!directSuccess) {
                // Requeue for retry - the block entity data is already persisted via addConnection.
                // Only fall back to destructive propagation after exhausting retries AND when no trains exist.
                LOGGER.warn("[RailwaysUntold/DeferredConnection] Direct graph connect failed for {} -> {}, requeueing (attempt {})",
                        pending.firstPos, pending.secondPos, pending.retryCount);
                requeueDirectConnectRetry(pending);
            }

            return true;

        } catch (ReflectiveOperationException e) {
            LOGGER.error("[RailwaysUntold/DeferredConnection] Reflection error invoking addConnection for {} -> {}: {}",
                    pending.firstPos, pending.secondPos, e.getMessage());
            return false;
        } catch (RuntimeException e) {
            LOGGER.error("[RailwaysUntold/DeferredConnection] Error invoking addConnection for {} -> {}: {}",
                    pending.firstPos, pending.secondPos, e.getMessage());
            return false;
        }
    }

    /**
     * Re-queues a pending connection with retry limit checking.
     * Discards the connection if max retries exceeded.
     *
     * @param pending The pending connection to re-queue
     */
    private static void requeueWithRetryLimit(PendingConnection pending, String cause, int maxRetries) {
        pending.retryCount++;
        if (pending.retryCount > maxRetries) {
            LOGGER.warn("[RailwaysUntold/DeferredConnection] Discarding connection {} -> {} after {} retries (cause={}) - endpoint track missing, connection abandoned",
                    pending.firstPos, pending.secondPos, maxRetries, cause);
            return;
        }
        synchronized (connectionQueue) {
            connectionQueue.add(pending);
        }
    }

    /**
     * Re-queues a connection whose direct graph connect failed.
     * Falls back to propagation only after MAX_RETRIES and only when no trains exist.
     */
    private static void requeueDirectConnectRetry(PendingConnection pending) {
        pending.retryCount++;
        if (pending.retryCount > MAX_RETRIES) {
            if (com.simibubi.create.Create.RAILWAYS.trains.isEmpty()) {
                LOGGER.warn("[RailwaysUntold/DeferredConnection] Direct connect exhausted retries for {} -> {}, using propagation fallback",
                        pending.firstPos, pending.secondPos);
                triggerTrackPropagation(pending.level, pending.firstPos);
                triggerTrackPropagation(pending.level, pending.secondPos);
            } else {
                LOGGER.warn("[RailwaysUntold/DeferredConnection] Direct connect exhausted retries for {} -> {} with trains present, graph may be incomplete",
                        pending.firstPos, pending.secondPos);
            }
            return;
        }
        synchronized (connectionQueue) {
            connectionQueue.add(pending);
        }
    }

    public static void clearQueue() {
        synchronized (connectionQueue) {
            connectionQueue.clear();
            INSTANCE.resetProcessTick();
        }
    }

    public static boolean hasPendingConnections() {
        synchronized (connectionQueue) {
            return !connectionQueue.isEmpty();
        }
    }

    /**
     * Callback interface for async priority connection processing.
     */
    @FunctionalInterface
    public interface PriorityConnectionCallback {
        void onComplete(boolean success);
    }

    /**
     * Asynchronously processes all pending connections that involve the specified positions.
     * Uses tick-based scheduling instead of Thread.sleep to avoid blocking the server thread.
     *
     * @param level       The server level
     * @param positions   The positions to prioritize (connections involving any of these will be processed)
     * @param maxAttempts Maximum number of processing attempts before giving up
     * @param callback    Callback to invoke when processing completes (can be null)
     */
    public static void processPriorityConnectionsAsync(ServerLevel level, java.util.Set<BlockPos> positions,
                                                        int maxAttempts, PriorityConnectionCallback callback) {
        processPriorityConnectionsAsyncInternal(level, positions, maxAttempts, 0, callback);
    }

    private static void processPriorityConnectionsAsyncInternal(ServerLevel level, java.util.Set<BlockPos> positions,
                                                                 int maxAttempts, int currentAttempt,
                                                                 PriorityConnectionCallback callback) {
        if (currentAttempt >= maxAttempts) {
            invokeCallback(callback, false);
            return;
        }

        java.util.List<PendingConnection> priorityConnections = extractPriorityConnections(positions);

        if (priorityConnections.isEmpty()) {
            invokeCallback(callback, true);
            return;
        }

        boolean allProcessed = processAllPriorityConnections(level, priorityConnections);

        if (allProcessed) {
            invokeCallback(callback, true);
            return;
        }

        final int nextAttempt = currentAttempt + 1;
        ServerTickHandler.scheduleDelayed(() ->
            processPriorityConnectionsAsyncInternal(level, positions, maxAttempts, nextAttempt, callback), 1);
    }

    private static java.util.List<PendingConnection> extractPriorityConnections(java.util.Set<BlockPos> positions) {
        java.util.List<PendingConnection> priorityConnections = new java.util.ArrayList<>();
        java.util.List<PendingConnection> otherConnections = new java.util.ArrayList<>();

        synchronized (connectionQueue) {
            while (!connectionQueue.isEmpty()) {
                PendingConnection pending = connectionQueue.poll();
                if (pending != null) {
                    if (positions.contains(pending.firstPos) || positions.contains(pending.secondPos)) {
                        priorityConnections.add(pending);
                    } else {
                        otherConnections.add(pending);
                    }
                }
            }
            connectionQueue.addAll(otherConnections);
        }

        return priorityConnections;
    }

    private static boolean processAllPriorityConnections(ServerLevel level, java.util.List<PendingConnection> priorityConnections) {
        boolean allProcessed = true;

        for (PendingConnection pending : priorityConnections) {
            if (!INSTANCE.isSameDimension(pending.level, level)) {
                requeueConnection(pending);
                continue;
            }

            if (INSTANCE.isStaleLevel(pending.level, level)) {
                continue;
            }

            if (!processSinglePriorityConnection(pending)) {
                allProcessed = false;
                requeueConnectionAtFront(pending);
            }
        }

        return allProcessed;
    }

    private static boolean processSinglePriorityConnection(PendingConnection pending) {
        return processConnection(pending);
    }

    private static void requeueConnection(PendingConnection pending) {
        synchronized (connectionQueue) {
            connectionQueue.add(pending);
        }
    }

    private static void requeueConnectionAtFront(PendingConnection pending) {
        synchronized (connectionQueue) {
            java.util.LinkedList<PendingConnection> tempList = new java.util.LinkedList<>();
            tempList.add(pending);
            tempList.addAll(connectionQueue);
            connectionQueue.clear();
            connectionQueue.addAll(tempList);
        }
    }

    private static void invokeCallback(PriorityConnectionCallback callback, boolean success) {
        if (callback != null) {
            callback.onComplete(success);
        }
    }

    private static void triggerTrackPropagation(ServerLevel level, BlockPos pos) {
        BlockState state = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, pos);
        if (state == null) {
            return;
        }
        CreateTrackUtil.triggerTrackPropagator(level, pos, state);
    }

    /** Registry name of a block state, for diagnosing a BE-less connection endpoint. */
    private static String blockName(BlockState state) {
        if (state == null) {
            return "null-state";
        }
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static void ensureConnectionMethodsCached() throws ReflectiveOperationException {
        if (cachedAddConnectionMethod == null) {
            Class<?> trackBEClass = CreateTrackUtil.getTrackBlockEntityClass();
            Class<?> bezierClass = CreateTrackUtil.getBezierConnectionClass();
            cachedAddConnectionMethod = trackBEClass.getMethod("addConnection", bezierClass);
            cachedSecondaryMethod = bezierClass.getMethod("secondary");
        }
    }
}
