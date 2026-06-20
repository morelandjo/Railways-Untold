package com.vodmordia.railwaysuntold.worldgen.train;

import com.mojang.logging.LogUtils;
import com.simibubi.create.api.event.TrackGraphMergeEvent;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.vodmordia.railwaysuntold.RailwaysUntold;
import com.vodmordia.railwaysuntold.datapack.LootConfig;
import com.vodmordia.railwaysuntold.worldgen.integration.deferred.DeferredConnectionManager;
import com.vodmordia.railwaysuntold.worldgen.train.TrainValidationData.ValidationResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Event-driven listener for waiting on Create's track graph to be ready.
 */
@EventBusSubscriber(modid = RailwaysUntold.MODID)
public class TrackGraphReadyListener {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Pending train placements waiting for graph
    private static final Map<BlockPos, PendingTrainPlacement> pendingPlacements = new ConcurrentHashMap<>();

    // Pending callbacks waiting for graph to be ready (general purpose)
    private static final Map<BlockPos, PendingGraphCallback> pendingGraphCallbacks = new ConcurrentHashMap<>();

    // Configuration
    private static final int INITIAL_POLL_DELAY_TICKS = 5;
    private static final int MAX_POLL_DELAY_TICKS = 100; // ~5 seconds max between polls
    private static final int MAX_TOTAL_WAIT_TICKS = 600; // 30 seconds absolute max
    private static final double BACKOFF_MULTIPLIER = 1.5;
    private static final int TICKS_PER_SECOND = 20;

    // Track if we've registered for Create's events
    private static volatile boolean createEventsRegistered = false;

    /**
     * Inner class for general-purpose graph waiting callbacks.
     */
    private static class PendingGraphCallback {
        final ServerLevel level;
        final BlockPos trackPos;
        final Direction trackDirection;
        final Runnable callback;

        final long startTick;
        long nextPollTick;
        int pollDelayTicks;
        int attempts;

        PendingGraphCallback(ServerLevel level, BlockPos trackPos, Direction trackDirection, Runnable callback) {
            this.level = level;
            this.trackPos = trackPos;
            this.trackDirection = trackDirection;
            this.callback = callback;

            this.startTick = level.getGameTime();
            this.pollDelayTicks = INITIAL_POLL_DELAY_TICKS;
            this.nextPollTick = startTick + pollDelayTicks;
            this.attempts = 0;
        }
    }

    /**
     * Inner class to track pending train placements.
     */
    private static class PendingTrainPlacement {
        final ServerLevel level;
        final ValidationResult validation;
        final BlockPos trackPos;
        final Direction trackDirection;
        final UUID ownerUUID;
        final Consumer<TrainBuilder.BuildResult> callback;
        final LootConfig lootConfig;

        final long startTick;
        long nextPollTick;
        int pollDelayTicks;
        int attempts;

        PendingTrainPlacement(ServerLevel level, ValidationResult validation,
                              BlockPos trackPos, Direction trackDirection, UUID ownerUUID,
                              Consumer<TrainBuilder.BuildResult> callback, LootConfig lootConfig) {
            this.level = level;
            this.validation = validation;
            this.trackPos = trackPos;
            this.trackDirection = trackDirection;
            this.ownerUUID = ownerUUID;
            this.callback = callback;
            this.lootConfig = lootConfig;

            this.startTick = level.getGameTime();
            this.pollDelayTicks = INITIAL_POLL_DELAY_TICKS;
            this.nextPollTick = startTick + pollDelayTicks;
            this.attempts = 0;
        }
    }

    /**
     * Ensures Create's TrackGraphMergeEvent listener is registered.
     */
    private static synchronized void ensureCreateEventsRegistered() {
        if (!createEventsRegistered) {
            NeoForge.EVENT_BUS.addListener(TrackGraphReadyListener::onTrackGraphMerge);
            createEventsRegistered = true;
        }
    }

    /**
     * Waits for the track graph to be ready at the specified position, then executes the callback.
     *
     * @param level          The server level
     * @param trackPos       The position of the track to wait for
     * @param trackDirection The direction the track runs
     * @param callback       Callback to execute when graph is ready
     */
    public static void waitForGraphReady(
            ServerLevel level,
            BlockPos trackPos,
            Direction trackDirection,
            Runnable callback) {

        if (DeferredConnectionManager.hasPendingConnections()) {
            ensureCreateEventsRegistered();
            pendingGraphCallbacks.put(trackPos, new PendingGraphCallback(level, trackPos, trackDirection, callback));
            return;
        }

        // Try immediately first
        TrackGraph graph = CreateTrainUtils.getGraphForTrack(level, trackPos, trackDirection);

        if (graph != null) {
            // Graph is ready, execute callback immediately
            callback.run();
            return;
        }

        // Graph not ready - register for waiting
        ensureCreateEventsRegistered();
        pendingGraphCallbacks.put(trackPos, new PendingGraphCallback(level, trackPos, trackDirection, callback));
    }

    /**
     * Waits for the track graph to be ready, then builds the train.
     *
     * @param level          The server level
     * @param validation     The validated train schematic data
     * @param trackPos       The position of the track where the train should be placed
     * @param trackDirection The direction the track runs
     * @param ownerUUID      Optional owner UUID (can be null)
     * @param callback       Callback with the build result
     * @param lootConfig     Loot configuration for containers
     */
    public static void waitForGraphAndBuildTrain(
            ServerLevel level,
            ValidationResult validation,
            BlockPos trackPos,
            Direction trackDirection,
            @Nullable UUID ownerUUID,
            Consumer<TrainBuilder.BuildResult> callback,
            LootConfig lootConfig) {

        if (DeferredConnectionManager.hasPendingConnections()) {
            ensureCreateEventsRegistered();

            PendingTrainPlacement pending = new PendingTrainPlacement(
                    level, validation, trackPos, trackDirection, ownerUUID, callback, lootConfig);
            pendingPlacements.put(trackPos, pending);
            return;
        }

        // Try immediately first (synchronous case - most common)
        TrackGraph graph = CreateTrainUtils.getGraphForTrack(level, trackPos, trackDirection);

        if (graph != null) {
            // Graph exists, but verify edges are ready (bezier might not be processed yet)
            double requiredTrackLength = calculateRequiredTrackLength(validation);
            double availableTrackLength = getAvailableTrackLength(graph, level, trackPos, trackDirection);

            if (availableTrackLength >= requiredTrackLength) {
                // Graph is ready with sufficient track, build immediately
                TrainBuilder.BuildResult result = TrainBuilder.buildTrain(
                        level, validation, trackPos, trackDirection, ownerUUID, lootConfig);
                if (callback != null) {
                    callback.accept(result);
                }
                return;
            }
        }

        // Graph not ready - register for waiting
        ensureCreateEventsRegistered();

        PendingTrainPlacement pending = new PendingTrainPlacement(
                level, validation, trackPos, trackDirection, ownerUUID, callback, lootConfig);

        pendingPlacements.put(trackPos, pending);
    }

    /**
     * Called when Create posts a TrackGraphMergeEvent.
     */
    public static void onTrackGraphMerge(TrackGraphMergeEvent event) {
        if (pendingPlacements.isEmpty()) {
            return;
        }

        // Don't process if there are still pending connections to be established
        if (DeferredConnectionManager.hasPendingConnections()) {
            return;
        }

        for (Iterator<Map.Entry<BlockPos, PendingTrainPlacement>> it =
             pendingPlacements.entrySet().iterator(); it.hasNext(); ) {

            Map.Entry<BlockPos, PendingTrainPlacement> entry = it.next();
            PendingTrainPlacement pending = entry.getValue();

            TrackGraph graph = CreateTrainUtils.getGraphForTrack(pending.level, pending.trackPos, pending.trackDirection);

            if (graph != null) {
                TrainBuilder.BuildResult result = TrainBuilder.buildTrain(
                        pending.level, pending.validation, pending.trackPos,
                        pending.trackDirection, pending.ownerUUID, pending.lootConfig);

                if (pending.callback != null) {
                    pending.callback.accept(result);
                }

                it.remove();
            }
        }
    }

    /**
     * Tick-based polling fallback for edge cases where the graph appears
     * without triggering a merge event (e.g., first track in world).
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (pendingPlacements.isEmpty() && pendingGraphCallbacks.isEmpty()) {
            return;
        }

        if (DeferredConnectionManager.hasPendingConnections()) {
            return;
        }

        for (ServerLevel level : event.getServer().getAllLevels()) {
            long currentTick = level.getGameTime();
            processGraphCallbacks(level, currentTick);
            processPendingPlacements(level, currentTick);
        }
    }

    private static void processPendingPlacements(ServerLevel level, long currentTick) {
        for (Iterator<Map.Entry<BlockPos, PendingTrainPlacement>> it =
             pendingPlacements.entrySet().iterator(); it.hasNext(); ) {

            Map.Entry<BlockPos, PendingTrainPlacement> entry = it.next();
            PendingTrainPlacement pending = entry.getValue();

            if (pending.level != level) {
                continue;
            }

            if (handlePlacementTimeout(pending, currentTick, it)) {
                continue;
            }

            if (currentTick < pending.nextPollTick) {
                continue;
            }

            pending.attempts++;
            tryBuildTrainForPlacement(pending, currentTick, it);
        }
    }

    private static boolean handlePlacementTimeout(PendingTrainPlacement pending, long currentTick,
                                                   Iterator<Map.Entry<BlockPos, PendingTrainPlacement>> it) {
        long elapsedTicks = currentTick - pending.startTick;
        if (elapsedTicks <= MAX_TOTAL_WAIT_TICKS) {
            return false;
        }

        LOGGER.error("[TRACK-GRAPH-LISTENER] TIMEOUT waiting for graph at {} (direction={}) after {} ticks ({} seconds). " +
                        "Attempts: {}, validation: {}",
                pending.trackPos, pending.trackDirection, elapsedTicks, elapsedTicks / TICKS_PER_SECOND,
                pending.attempts, pending.validation);

        if (pending.callback != null) {
            pending.callback.accept(TrainBuilder.BuildResult.failure(
                    "Timeout waiting for track graph after " + (elapsedTicks / TICKS_PER_SECOND) + " seconds"));
        }

        it.remove();
        return true;
    }

    private static void tryBuildTrainForPlacement(PendingTrainPlacement pending, long currentTick,
                                                   Iterator<Map.Entry<BlockPos, PendingTrainPlacement>> it) {
        TrackGraph graph = CreateTrainUtils.getGraphForTrack(pending.level, pending.trackPos, pending.trackDirection);

        if (graph != null) {
            double requiredLength = calculateRequiredTrackLength(pending.validation);
            double availableLength = getAvailableTrackLength(graph, pending.level, pending.trackPos, pending.trackDirection);

            if (availableLength >= requiredLength) {
                TrainBuilder.BuildResult result = TrainBuilder.buildTrain(
                        pending.level, pending.validation, pending.trackPos,
                        pending.trackDirection, pending.ownerUUID, pending.lootConfig);

                if (pending.callback != null) {
                    pending.callback.accept(result);
                }

                it.remove();
                return;
            }
        }

        applyBackoffDelay(pending, currentTick);
    }

    private static void applyBackoffDelay(PendingTrainPlacement pending, long currentTick) {
        pending.pollDelayTicks = (int) Math.min(
                pending.pollDelayTicks * BACKOFF_MULTIPLIER,
                MAX_POLL_DELAY_TICKS);
        pending.nextPollTick = currentTick + pending.pollDelayTicks;
    }

    public static void clearPending() {
        pendingPlacements.clear();
        pendingGraphCallbacks.clear();
    }

    /**
     * Processes pending graph callbacks for a given level.
     */
    private static void processGraphCallbacks(ServerLevel level, long currentTick) {
        for (Iterator<Map.Entry<BlockPos, PendingGraphCallback>> it =
             pendingGraphCallbacks.entrySet().iterator(); it.hasNext(); ) {

            Map.Entry<BlockPos, PendingGraphCallback> entry = it.next();
            PendingGraphCallback pending = entry.getValue();

            if (pending.level != level) {
                continue;
            }

            long elapsedTicks = currentTick - pending.startTick;
            if (elapsedTicks > MAX_TOTAL_WAIT_TICKS) {
                BlockPos tp = pending.trackPos;
                LOGGER.error("[GRAPH-WAIT] TIMEOUT waiting for graph at {} after {} ticks ({} seconds) "
                                + "[block={} chunkLoaded={} entityTicking={}]",
                        tp, elapsedTicks, elapsedTicks / TICKS_PER_SECOND,
                        pending.level.getBlockState(tp),
                        pending.level.hasChunkAt(tp),
                        pending.level.isPositionEntityTicking(tp));
                pending.callback.run();
                it.remove();
                continue;
            }

            if (currentTick < pending.nextPollTick) {
                continue;
            }

            pending.attempts++;
            TrackGraph graph = CreateTrainUtils.getGraphForTrack(pending.level, pending.trackPos, pending.trackDirection);

            if (graph != null) {
                pending.callback.run();
                it.remove();
            } else {
                pending.pollDelayTicks = (int) Math.min(
                        pending.pollDelayTicks * BACKOFF_MULTIPLIER,
                        MAX_POLL_DELAY_TICKS);
                pending.nextPollTick = currentTick + pending.pollDelayTicks;
            }
        }
    }

    /**
     * Calculates the required track length for a train based on bogey positions.
     */
    private static double calculateRequiredTrackLength(ValidationResult validation) {
        if (validation.bogeyLayout.bogeys().isEmpty()) {
            return 8.0; // Default
        }

        // Need enough track for all bogey wheel points
        // bogeySpacing + buffer for wheel point spacing
        int bogeySpacing = validation.bogeyLayout.spacing() > 0 ? validation.bogeyLayout.spacing() : 4;
        // Add 2 for wheel point spacing on each bogey, plus 1 buffer
        return bogeySpacing + 3.0;
    }

    /**
     * Gets the available track length from the starting position.
     */
    private static double getAvailableTrackLength(TrackGraph graph, ServerLevel level, BlockPos trackPos, Direction trackDirection) {
        net.minecraft.world.phys.Vec3 trackCenter = CreateTrainUtils.getTrackNodePosition(trackPos, trackDirection);
        com.simibubi.create.content.trains.graph.TrackNode startNode = CreateTrainUtils.locateNode(graph, trackCenter, level);

        if (startNode == null) {
            return 0.0;
        }

        java.util.Map<com.simibubi.create.content.trains.graph.TrackNode, com.simibubi.create.content.trains.graph.TrackEdge> connections = graph.getConnectionsFrom(startNode);

        if (connections == null || connections.isEmpty()) {
            return 0.0;
        }

        // Sum total length of ALL connected edges (beziers may curve in any direction)
        double totalLength = 0.0;
        java.util.Set<com.simibubi.create.content.trains.graph.TrackNode> visited = new java.util.HashSet<>();
        java.util.Queue<com.simibubi.create.content.trains.graph.TrackNode> queue = new java.util.LinkedList<>();

        visited.add(startNode);
        queue.add(startNode);

        int maxNodes = 50; // Limit traversal
        int nodesVisited = 0;

        while (!queue.isEmpty() && nodesVisited < maxNodes) {
            com.simibubi.create.content.trains.graph.TrackNode currentNode = queue.poll();
            nodesVisited++;

            java.util.Map<com.simibubi.create.content.trains.graph.TrackNode, com.simibubi.create.content.trains.graph.TrackEdge> currentConnections = graph.getConnectionsFrom(currentNode);
            if (currentConnections == null) continue;

            for (java.util.Map.Entry<com.simibubi.create.content.trains.graph.TrackNode, com.simibubi.create.content.trains.graph.TrackEdge> entry : currentConnections.entrySet()) {
                com.simibubi.create.content.trains.graph.TrackNode nextNode = entry.getKey();
                com.simibubi.create.content.trains.graph.TrackEdge edge = entry.getValue();

                if (!visited.contains(nextNode)) {
                    visited.add(nextNode);
                    queue.add(nextNode);
                    totalLength += edge.getLength();
                }
            }
        }

        return totalLength;
    }

}
