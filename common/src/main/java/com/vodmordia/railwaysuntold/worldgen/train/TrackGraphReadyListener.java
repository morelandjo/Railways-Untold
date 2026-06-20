package com.vodmordia.railwaysuntold.worldgen.train;

import com.mojang.logging.LogUtils;
import com.simibubi.create.api.event.TrackGraphMergeEvent;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.vodmordia.railwaysuntold.datapack.LootConfig;
import com.vodmordia.railwaysuntold.worldgen.integration.deferred.DeferredConnectionManager;
import com.vodmordia.railwaysuntold.worldgen.train.TrainValidationData.ValidationResult;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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
public class TrackGraphReadyListener {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<BlockPos, PendingTrainPlacement> pendingPlacements = new ConcurrentHashMap<>();
    private static final Map<BlockPos, PendingGraphCallback> pendingGraphCallbacks = new ConcurrentHashMap<>();

    private static final int INITIAL_POLL_DELAY_TICKS = 5;
    private static final int MAX_POLL_DELAY_TICKS = 100;
    private static final int MAX_TOTAL_WAIT_TICKS = 600;
    private static final double BACKOFF_MULTIPLIER = 1.5;
    private static final int TICKS_PER_SECOND = 20;

    private static volatile boolean createEventsRegistered = false;

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

    public static void register() {
        TickEvent.SERVER_POST.register(TrackGraphReadyListener::onServerTick);
    }

    public static synchronized void registerCreateEvents(Runnable eventRegistrar) {
        if (!createEventsRegistered) {
            eventRegistrar.run();
            createEventsRegistered = true;
        }
    }

    /**
     * Waits for the track graph to be ready at the specified position, then executes the callback.
     */
    public static void waitForGraphReady(
            ServerLevel level,
            BlockPos trackPos,
            Direction trackDirection,
            Runnable callback) {

        if (DeferredConnectionManager.hasPendingConnections()) {
            pendingGraphCallbacks.put(trackPos, new PendingGraphCallback(level, trackPos, trackDirection, callback));
            return;
        }

        TrackGraph graph = CreateTrainUtils.getGraphForTrack(level, trackPos, trackDirection);

        if (graph != null) {
            callback.run();
            return;
        }

        pendingGraphCallbacks.put(trackPos, new PendingGraphCallback(level, trackPos, trackDirection, callback));
    }

    /**
     * Waits for the track graph to be ready, then builds the train.
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

            PendingTrainPlacement pending = new PendingTrainPlacement(
                level, validation, trackPos, trackDirection, ownerUUID, callback, lootConfig);
            pendingPlacements.put(trackPos, pending);
            return;
        }

        TrackGraph graph = CreateTrainUtils.getGraphForTrack(level, trackPos, trackDirection);

        if (graph != null) {
            double requiredTrackLength = calculateRequiredTrackLength(validation);
            double availableTrackLength = getAvailableTrackLength(graph, level, trackPos, trackDirection);

            if (availableTrackLength >= requiredTrackLength) {
                TrainBuilder.BuildResult result = TrainBuilder.buildTrain(
                    level, validation, trackPos, trackDirection, ownerUUID, lootConfig);
                if (callback != null) {
                    callback.accept(result);
                }
                return;
            } else {
            }
        }

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

        if (DeferredConnectionManager.hasPendingConnections()) {
            return;
        }

        for (Iterator<Map.Entry<BlockPos, PendingTrainPlacement>> it =
                pendingPlacements.entrySet().iterator(); it.hasNext();) {

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

    private static void onServerTick(MinecraftServer server) {
        if (pendingPlacements.isEmpty() && pendingGraphCallbacks.isEmpty()) {
            return;
        }

        if (DeferredConnectionManager.hasPendingConnections()) {
            return;
        }

        for (ServerLevel level : server.getAllLevels()) {
            long currentTick = level.getGameTime();

            processGraphCallbacks(level, currentTick);
            processTrainPlacements(level, currentTick);
        }
    }

    private static void processGraphCallbacks(ServerLevel level, long currentTick) {
        for (Iterator<Map.Entry<BlockPos, PendingGraphCallback>> it =
             pendingGraphCallbacks.entrySet().iterator(); it.hasNext(); ) {

            Map.Entry<BlockPos, PendingGraphCallback> entry = it.next();
            PendingGraphCallback pending = entry.getValue();

            if (pending.level != level) {
                continue;
            }

            if (handleGraphCallbackTimeout(pending, currentTick, it)) {
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
                updatePollDelay(pending, currentTick);
            }
        }
    }

    private static boolean handleGraphCallbackTimeout(PendingGraphCallback pending, long currentTick,
                                                       Iterator<Map.Entry<BlockPos, PendingGraphCallback>> it) {
        long elapsedTicks = currentTick - pending.startTick;
        if (elapsedTicks > MAX_TOTAL_WAIT_TICKS) {
            LOGGER.error("[GRAPH-WAIT] TIMEOUT waiting for graph at {} after {} ticks ({} seconds)",
                    pending.trackPos, elapsedTicks, elapsedTicks / TICKS_PER_SECOND);
            pending.callback.run();
            it.remove();
            return true;
        }
        return false;
    }

    private static void updatePollDelay(PendingGraphCallback pending, long currentTick) {
        pending.pollDelayTicks = (int) Math.min(
                pending.pollDelayTicks * BACKOFF_MULTIPLIER,
                MAX_POLL_DELAY_TICKS);
        pending.nextPollTick = currentTick + pending.pollDelayTicks;
    }

    private static void processTrainPlacements(ServerLevel level, long currentTick) {
        for (Iterator<Map.Entry<BlockPos, PendingTrainPlacement>> it =
                pendingPlacements.entrySet().iterator(); it.hasNext();) {

            Map.Entry<BlockPos, PendingTrainPlacement> entry = it.next();
            PendingTrainPlacement pending = entry.getValue();

            if (pending.level != level) {
                continue;
            }

            if (handleTrainPlacementTimeout(pending, currentTick, it)) {
                continue;
            }

            if (currentTick < pending.nextPollTick) {
                continue;
            }

            pending.attempts++;
            TrackGraph graph = CreateTrainUtils.getGraphForTrack(pending.level, pending.trackPos, pending.trackDirection);

            if (graph != null) {
                processGraphReadyForTrain(pending, graph, it);
            } else {
                updatePollDelayForTrainPlacement(pending, currentTick);
            }
        }
    }

    private static boolean handleTrainPlacementTimeout(PendingTrainPlacement pending, long currentTick,
                                                        Iterator<Map.Entry<BlockPos, PendingTrainPlacement>> it) {
        long elapsedTicks = currentTick - pending.startTick;
        if (elapsedTicks > MAX_TOTAL_WAIT_TICKS) {
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
        return false;
    }

    private static void processGraphReadyForTrain(PendingTrainPlacement pending, TrackGraph graph,
                                                   Iterator<Map.Entry<BlockPos, PendingTrainPlacement>> it) {
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
        } else {
            updatePollDelayForTrainPlacement(pending, pending.level.getGameTime());
        }
    }

    private static void updatePollDelayForTrainPlacement(PendingTrainPlacement pending, long currentTick) {
        pending.pollDelayTicks = (int) Math.min(
            pending.pollDelayTicks * BACKOFF_MULTIPLIER,
            MAX_POLL_DELAY_TICKS);
        pending.nextPollTick = currentTick + pending.pollDelayTicks;
    }

    public static void clearPending() {
        pendingPlacements.clear();
        pendingGraphCallbacks.clear();
    }

    private static double calculateRequiredTrackLength(ValidationResult validation) {
        if (validation.bogeyLayout.bogeys().isEmpty()) {
            return 8.0;
        }

        int bogeySpacing = validation.bogeyLayout.spacing() > 0 ? validation.bogeyLayout.spacing() : 4;
        return bogeySpacing + 3.0;
    }

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

        double totalLength = 0.0;
        java.util.Set<com.simibubi.create.content.trains.graph.TrackNode> visited = new java.util.HashSet<>();
        java.util.Queue<com.simibubi.create.content.trains.graph.TrackNode> queue = new java.util.LinkedList<>();

        visited.add(startNode);
        queue.add(startNode);

        int maxNodes = 50;
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
