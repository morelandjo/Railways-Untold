package com.vodmordia.railwaysuntold.worldgen.placement;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil;
import com.vodmordia.railwaysuntold.worldgen.head.TrackExpansionHead;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Listens for chunk load events and resumes track placement when chunks become available.
 */
@EventBusSubscriber
public class ChunkLoadTrackExpander {

    private static RailwaysUntoldConfig getConfig() {
        return RailwaysUntoldConfig.getDefault();
    }

    /**
     * Encapsulates chunk waiting state per dimension.
     */
    private static class ChunkWaitingState {
        // Per-head chunk tracking
        // headId -> set of chunks that head is waiting for
        final Map<UUID, Set<ChunkPos>> headWaitingChunks = new ConcurrentHashMap<>();
        // Reverse index: chunk -> set of heads waiting for that chunk
        final Map<ChunkPos, Set<UUID>> chunkToHeads = new ConcurrentHashMap<>();
        // Timestamps for stale chunk cleanup (chunk -> timestamp when first waited)
        final Map<ChunkPos, Long> chunkTimestamps = new ConcurrentHashMap<>();
    }

    private static final Map<String, ChunkWaitingState> chunkWaitingStates = new ConcurrentHashMap<>();
    private static final Object waitingChunksLock = new Object();
    private static final AtomicInteger tickCounter = new AtomicInteger(0);
    private static final int DEFERRED_HEAD_CHECK_INTERVAL_TICKS = 40;
    private static final int CHUNK_RELEASE_INTERVAL_TICKS = 40;

    /**
     * Manhattan distance (chunks) at which a head-issued ticket is considered "behind"
     * the head and eligible for release. MUST stay at or above the longest single segment's
     * chunk span, or the far end of the segment being placed unloads mid-placement - the head
     * then can't see its own just-placed tip, reads its position as the last still-loaded block,
     * and re-places the same segment forever (stuck). Segments are cardinal-straight and capped at
     * {@code SegmentEmitter.MAX_SEGMENT_LENGTH} (64 blocks ≈ 4 chunks; ~72 with commit overshoot, <=5
     * chunks), so 6 covers the span plus the ~3-chunk routing lookahead. This must scale with the
     * bridge cap: with a 128-block cap it needs to be 8, and dropping it to 5 strands long bridges.
     */
    private static final int CHUNK_RELEASE_DISTANCE = 6;

    /**
     * Per-(dimension, head) set of chunks where the mod has issued a CHUNK_LOAD_TICKET.
     */
    private static final Map<String, Map<UUID, Set<ChunkPos>>> headTicketedChunks = new ConcurrentHashMap<>();

    /**
     * Per-(dimension, head) set of chunks held until the head finishes a multi-segment
     * unit (currently a diagonal Entry->Straight->Exit trio).
     */
    private static final Map<String, Map<UUID, Set<ChunkPos>>> headTrioPinnedChunks = new ConcurrentHashMap<>();

    /** Always-on logger (bypasses the TrackLogger verbose gate). */
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /**
     * Manual-cleanup ticket used to pin chunks the head is actively working in.
     */
    private static final TicketType<ChunkPos> CHUNK_LOAD_TICKET = TicketType.create(
            "railwaysuntold_chunk_load",
            Comparator.comparingLong(ChunkPos::toLong),
            0);

    public static void clearAllPlacersAndWaitingChunks() {
        chunkWaitingStates.clear();
        SeenChunkRegistry.clear();
        headTicketedChunks.clear();
        headTrioPinnedChunks.clear();
    }

    /**
     * Removes every {@link #CHUNK_LOAD_TICKET} this expander has issued and drops the per-head
     * tracking. Called on server stop: the autoload driver pins a moving band of corridor and
     * deferred chunks via manual-cleanup tickets, and {@link #clearAllPlacersAndWaitingChunks}
     * only drops the tracking map without removing the tickets. Left in place, those force-loaded
     * chunks keep the chunk system generating/holding while the world tries to save, hanging the
     * shutdown. Must run before the tracking map is cleared.
     */
    public static void releaseAllHeldChunks(net.minecraft.server.MinecraftServer server) {
        if (server != null) {
            for (ServerLevel level : server.getAllLevels()) {
                Map<UUID, Set<ChunkPos>> ticketed =
                        headTicketedChunks.get(ChunkCoordinateUtil.getDimensionKey(level));
                if (ticketed == null) continue;
                for (Set<ChunkPos> chunks : ticketed.values()) {
                    for (ChunkPos chunk : chunks) {
                        level.getChunkSource().removeRegionTicket(CHUNK_LOAD_TICKET, chunk, 1, chunk);
                    }
                }
            }
        }
        headTicketedChunks.clear();
        headTrioPinnedChunks.clear();
    }

    /**
     * Pin the given chunks for {@code headId} so they don't unload while the head is
     * actively working in them. Returns the number of chunks newly ticketed this call
     * (i.e. not already held for the head) - used by the autoload driver to meter how
     * much fresh generation it has requested against its safety budget.
     */
    public static int holdWorkingChunks(ServerLevel level, UUID headId, Iterable<ChunkPos> chunks) {
        if (headId == null) return 0;
        String dimensionKey = ChunkCoordinateUtil.getDimensionKey(level);
        Set<ChunkPos> headRecord = headTicketedChunks
                .computeIfAbsent(dimensionKey, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(headId, k -> ConcurrentHashMap.newKeySet());
        int newlyTicketed = 0;
        for (ChunkPos chunk : chunks) {
            if (headRecord.add(chunk)) {
                level.getChunkSource().addRegionTicket(CHUNK_LOAD_TICKET, chunk, 1, chunk);
                newlyTicketed++;
            }
        }
        return newlyTicketed;
    }

    /**
     * Like {@link #holdWorkingChunks}, but also marks the chunks as exempt from the
     * distance-based release sweep until {@link #releaseTrioPin} is called. Use when
     * the head has a multi-segment commitment (currently the diagonal Entry->Straight->
     * Exit sequence) whose footprint extends beyond the sweep's 8-chunk threshold.
     *
     */
    public static void pinTrioChunks(ServerLevel level, UUID headId, Iterable<ChunkPos> chunks) {
        if (headId == null) return;
        String dimensionKey = ChunkCoordinateUtil.getDimensionKey(level);
        holdWorkingChunks(level, headId, chunks);
        Set<ChunkPos> trioSet = ConcurrentHashMap.newKeySet();
        for (ChunkPos chunk : chunks) {
            trioSet.add(chunk);
        }
        headTrioPinnedChunks
                .computeIfAbsent(dimensionKey, k -> new ConcurrentHashMap<>())
                .put(headId, trioSet);
    }

    /**
     * Removes the trio-pin exemption for a head. The chunks themselves stay ticketed
     * via {@link #headTicketedChunks} until the next release sweep, which will then
     * apply the normal distance-based check and free anything behind.
     */
    public static void releaseTrioPin(UUID headId) {
        if (headId == null) return;
        for (Map<UUID, Set<ChunkPos>> headMap : headTrioPinnedChunks.values()) {
            headMap.remove(headId);
        }
    }

    private static ChunkWaitingState getOrCreateState(String dimensionKey) {
        return chunkWaitingStates.computeIfAbsent(dimensionKey, k -> new ChunkWaitingState());
    }

    private static ChunkWaitingState getState(String dimensionKey) {
        return chunkWaitingStates.get(dimensionKey);
    }

    /** True if {@code headId} is currently registered as waiting on unloaded chunks - i.e. legitimately
     *  chunk-deferred, not silently stalled. The stall watchdog skips these so it never kicks or retires
     *  a head that is simply waiting for the player/Chunky/autoload to load the chunks it needs. */
    public static boolean isHeadWaitingForChunks(ServerLevel level, java.util.UUID headId) {
        ChunkWaitingState state = getState(ChunkCoordinateUtil.getDimensionKey(level));
        return state != null && state.headWaitingChunks.containsKey(headId);
    }

    /**
     * Attempts to restore an orchestrator from saved data.
     * Returns null if restoration is not needed or not possible.
     * Uses TrackPlacerRegistry as single source of truth.
     */
    private static TrackExpansionOrchestrator tryRestoreOrchestrator(ServerLevel level) {
        // Single check: registry is the only source of truth
        TrackExpansionOrchestrator existing = TrackPlacerRegistry.get(level);
        if (existing != null) {
            return existing;
        }

        // Check saved data
        TrackPlacementSavedData savedData = TrackPlacementSavedData.get(level);
        if (!savedData.isInitiated() || savedData.areAllHeadsComplete() || savedData.getAllHeads().isEmpty()) {
            return null;
        }

        // Re-check registry after savedData read (potential race window)
        existing = TrackPlacerRegistry.get(level);
        if (existing != null) {
            return existing;
        }

        net.minecraft.core.Direction trackDirection = savedData.getInitialTrackDirection();
        if (trackDirection == null) {
            return null;
        }

        // Create and register in single location
        TrackExpansionOrchestrator placer = new TrackExpansionOrchestrator(
                level, getConfig(), trackDirection, savedData, level.getSharedSpawnPos());
        TrackPlacerRegistry.register(level, placer);

        // Schedule ALL restored heads (including paused ones)
        // Paused heads may have been waiting for chunks/player proximity before save
        // On reload, they need to be re-evaluated
        level.getServer().execute(() -> {
            for (TrackExpansionHead head : placer.getHeadManager().getActiveHeads()) {
                if (head.isPaused()) {
                    head.resume();
                }
                placer.scheduleHeadExpansion(head, PlacementConstants.MIN_EXPANSION_DELAY_TICKS,
                        HeadScheduler.ScheduleCaller.INITIAL);
            }
        });

        return placer;
    }

    public static void addWaitingChunksForHead(String dimensionKey, UUID headId, Set<ChunkPos> chunks, ServerLevel level) {
        ChunkWaitingState state = getOrCreateState(dimensionKey);

        boolean allChunksAlreadyLoaded = registerWaitingChunks(state, headId, chunks, level);

        if (allChunksAlreadyLoaded) {
            scheduleHeadResumption(level, headId);
            return;
        }

        // Re-check for race condition: chunks may have loaded between check and registration
        boolean shouldResume = cleanupNewlyLoadedChunks(state, headId, level);

        if (shouldResume) {
            scheduleHeadResumption(level, headId);
        }
    }

    private static boolean registerWaitingChunks(
            ChunkWaitingState state, UUID headId, Set<ChunkPos> chunks, ServerLevel level) {

        long currentTime = System.currentTimeMillis();
        Set<ChunkPos> seen = SeenChunkRegistry.seenIn(level);

        synchronized (waitingChunksLock) {
            Set<ChunkPos> headChunks = state.headWaitingChunks.computeIfAbsent(headId, k -> ConcurrentHashMap.newKeySet());

            for (ChunkPos chunk : chunks) {
                if (ChunkCoordinateUtil.getLoadedChunk(level, chunk) != null) {
                    continue;
                }

                if (headChunks.add(chunk)) {
                    state.chunkToHeads.computeIfAbsent(chunk, k -> ConcurrentHashMap.newKeySet()).add(headId);
                    state.chunkTimestamps.putIfAbsent(chunk, currentTime);

                    // Only ticket if we've seen this chunk load before - i.e. it
                    // exists on disk because chunky / a player / something else
                    // generated it.
                    if (seen.contains(chunk)) {
                        holdWorkingChunks(level, headId, java.util.Collections.singleton(chunk));
                    }
                }
            }

            boolean allChunksAlreadyLoaded = headChunks.isEmpty();
            if (allChunksAlreadyLoaded) {
                state.headWaitingChunks.remove(headId);
            }
            return allChunksAlreadyLoaded;
        }
    }

    private static boolean cleanupNewlyLoadedChunks(ChunkWaitingState state, UUID headId, ServerLevel level) {
        synchronized (waitingChunksLock) {
            Set<ChunkPos> headChunks = state.headWaitingChunks.get(headId);
            if (headChunks == null) {
                return false;
            }

            Set<ChunkPos> nowLoaded = findNewlyLoadedChunks(headChunks, level);
            if (nowLoaded.isEmpty()) {
                return false;
            }

            removeLoadedChunksFromState(state, headId, headChunks, nowLoaded);

            if (headChunks.isEmpty()) {
                state.headWaitingChunks.remove(headId);
                return true;
            }
            return false;
        }
    }

    private static Set<ChunkPos> findNewlyLoadedChunks(Set<ChunkPos> headChunks, ServerLevel level) {
        Set<ChunkPos> nowLoaded = new HashSet<>();
        for (ChunkPos chunk : headChunks) {
            if (ChunkCoordinateUtil.getLoadedChunk(level, chunk) != null) {
                nowLoaded.add(chunk);
            }
        }
        return nowLoaded;
    }

    private static void removeLoadedChunksFromState(
            ChunkWaitingState state, UUID headId, Set<ChunkPos> headChunks, Set<ChunkPos> loadedChunks) {

        for (ChunkPos loaded : loadedChunks) {
            headChunks.remove(loaded);
            removeHeadFromChunkMapping(state, headId, loaded);
            state.chunkTimestamps.remove(loaded);
        }
    }

    private static void removeHeadFromChunkMapping(ChunkWaitingState state, UUID headId, ChunkPos chunk) {
        Set<UUID> headsForChunk = state.chunkToHeads.get(chunk);
        if (headsForChunk != null) {
            headsForChunk.remove(headId);
            if (headsForChunk.isEmpty()) {
                state.chunkToHeads.remove(chunk);
            }
        }
    }

    private static void scheduleHeadResumption(ServerLevel level, UUID headId) {
        com.vodmordia.railwaysuntold.util.core.ThreadingUtil.scheduleNextTick(() -> {
            TrackExpansionOrchestrator placer = TrackPlacerRegistry.get(level);
            if (placer == null || placer.isComplete()) {
                LOGGER.warn("[CHUNK-RESUME] Head {} resumption FAILED: placer={}", headId.toString().substring(0, 8),
                        placer == null ? "null" : "complete");
                return;
            }
            var head = placer.getHeadManager().getHeadById(headId);
            if (head == null || head.isComplete()) {
                LOGGER.warn("[CHUNK-RESUME] Head {} resumption FAILED: head={}", headId.toString().substring(0, 8),
                        head == null ? "null" : "complete");
                return;
            }

            if (head.isPaused()) {
                head.resume();
            }
            boolean scheduled = placer.scheduleHeadExpansion(head, PlacementConstants.MIN_EXPANSION_DELAY_TICKS,
                    HeadScheduler.ScheduleCaller.EXTERNAL);
            if (!scheduled) {
                LOGGER.warn("[CHUNK-RESUME] Head {} at {} resumption REJECTED",
                        head.getHeadNumber(), head.getPosition());
            }
        });
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        if (!shouldProcessChunkLoad(level)) {
            return;
        }

        ChunkPos loadedChunk = event.getChunk().getPos();
        String dimensionKey = ChunkCoordinateUtil.getDimensionKey(level);
        SeenChunkRegistry.markSeen(level, loadedChunk);

        TrackExpansionOrchestrator placer = getOrRestoreOrchestrator(level);
        if (placer == null || placer.isComplete()) {
            return;
        }

        // Pin the chunk if any head is waiting for it. Chunky's ticket on
        // pre-gen chunks is dropped almost immediately after the load event
        // fires; without our own ticket here, the chunk would unload before
        // the head's next-tick resume could consume it.
        ChunkWaitingState state = getState(dimensionKey);
        Set<UUID> waitersSnapshot = state != null ? state.chunkToHeads.get(loadedChunk) : null;
        if (waitersSnapshot != null && !waitersSnapshot.isEmpty()) {
            // Idempotent: holdWorkingChunks per-waiter only addRegionTickets if the
            // chunk isn't already in that waiter's record.
            for (UUID waiter : waitersSnapshot) {
                holdWorkingChunks(level, waiter, java.util.Collections.singleton(loadedChunk));
            }
        }

        Set<UUID> headsToResume = findHeadsReadyToResume(dimensionKey, loadedChunk);
        if (!headsToResume.isEmpty()) {
            scheduleHeadResumptions(level, placer, headsToResume);
        }
    }

    /**
     * Checks if chunk load events should be processed for this level.
     */
    private static boolean shouldProcessChunkLoad(ServerLevel level) {
        if (com.vodmordia.railwaysuntold.util.core.SystemStateManager.isShuttingDown()) {
            return false;
        }

        if (!getConfig().TRACKS_ENABLED) {
            return false;
        }

        String dimensionKey = ChunkCoordinateUtil.getDimensionKey(level);
        return dimensionKey.equals("minecraft:overworld");
    }

    /**
     * Gets the orchestrator from the registry, or attempts to restore it from saved data.
     * Returns null if no orchestrator exists or can be restored.
     */
    private static TrackExpansionOrchestrator getOrRestoreOrchestrator(ServerLevel level) {
        TrackExpansionOrchestrator placer = TrackPlacerRegistry.get(level);
        if (placer == null) {
            placer = tryRestoreOrchestrator(level);
        }
        return placer;
    }

    /**
     * Finds heads that are ready to resume after a chunk loads.
     * Updates the chunk waiting state and returns the set of head IDs ready for resumption.
     */
    private static Set<UUID> findHeadsReadyToResume(String dimensionKey, ChunkPos loadedChunk) {
        ChunkWaitingState state = getState(dimensionKey);
        if (state == null) {
            return Collections.emptySet();
        }

        Set<UUID> headsToResume = new HashSet<>();

        synchronized (waitingChunksLock) {
            Set<UUID> waitingHeads = state.chunkToHeads.remove(loadedChunk);
            if (waitingHeads == null || waitingHeads.isEmpty()) {
                return Collections.emptySet();
            }

            for (UUID headId : waitingHeads) {
                if (removeChunkFromHeadAndCheckReady(state, headId, loadedChunk)) {
                    headsToResume.add(headId);
                }
            }

            state.chunkTimestamps.remove(loadedChunk);
        }

        return headsToResume;
    }

    /**
     * Removes a chunk from a head's waiting set and checks if the head is ready to resume.
     * Returns true if the head has no more chunks to wait for.
     */
    private static boolean removeChunkFromHeadAndCheckReady(ChunkWaitingState state, UUID headId, ChunkPos chunk) {
        Set<ChunkPos> headChunks = state.headWaitingChunks.get(headId);
        if (headChunks == null) {
            return false;
        }

        headChunks.remove(chunk);
        if (headChunks.isEmpty()) {
            state.headWaitingChunks.remove(headId);
            return true;
        }
        return false;
    }

    private static void scheduleHeadResumptions(ServerLevel level, TrackExpansionOrchestrator placer, Set<UUID> headsToResume) {
        for (UUID headId : headsToResume) {
            var head = placer.getHeadManager().getHeadById(headId);
            if (head == null || head.isComplete()) {
                continue;
            }

            com.vodmordia.railwaysuntold.util.core.ThreadingUtil.scheduleNextTick(() -> {
                var h = placer.getHeadManager().getHeadById(headId);
                if (h != null && !h.isComplete()) {
                    if (h.isPaused()) {
                        h.resume();
                    }
                    boolean scheduled = placer.scheduleHeadExpansion(h, PlacementConstants.MIN_EXPANSION_DELAY_TICKS,
                            HeadScheduler.ScheduleCaller.EXTERNAL);
                    if (!scheduled) {
                        LOGGER.warn("[CHUNK-RESUME] Head {} resumption REJECTED", h.getHeadNumber());
                    }
                }
            });
        }
    }

    private static void cleanupStaleChunks() {
        long currentTime = System.currentTimeMillis();

        for (Map.Entry<String, ChunkWaitingState> stateEntry : chunkWaitingStates.entrySet()) {
            String dimensionKey = stateEntry.getKey();
            ChunkWaitingState state = stateEntry.getValue();

            // Get the level to check actual chunk load status
            ServerLevel level = null;
            if (dimensionKey.equals("minecraft:overworld")) {
                net.minecraft.server.MinecraftServer server =
                        net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    level = server.getLevel(net.minecraft.world.level.Level.OVERWORLD);
                }
            }

            Set<ChunkPos> staleChunks = findStaleChunks(state, currentTime);
            if (staleChunks.isEmpty()) {
                continue;
            }

            // Split stale chunks: only process those that are actually loaded now
            // (missed chunk load events). For chunks still unloaded, just refresh
            // the timestamp to avoid a resume-defer cycle.
            Set<ChunkPos> loadedStaleChunks = new HashSet<>();
            for (ChunkPos chunk : staleChunks) {
                if (level != null && ChunkCoordinateUtil.getLoadedChunk(level, chunk) != null) {
                    loadedStaleChunks.add(chunk);
                } else {
                    // Chunk is still unloaded - refresh timestamp, don't resume
                    state.chunkTimestamps.put(chunk, currentTime);
                }
            }

            if (loadedStaleChunks.isEmpty()) {
                continue;
            }

            Set<UUID> headsToResume = removeStaleChunksFromState(state, loadedStaleChunks);
            if (!headsToResume.isEmpty()) {
                resumeHeadsAfterStaleCleanup(dimensionKey, headsToResume);
            }
        }
    }

    private static Set<ChunkPos> findStaleChunks(ChunkWaitingState state, long currentTime) {
        Set<ChunkPos> staleChunks = new HashSet<>();
        long staleThreshold = getConfig().CHUNK_STALE_THRESHOLD_MS;

        for (Map.Entry<ChunkPos, Long> entry : state.chunkTimestamps.entrySet()) {
            if (currentTime - entry.getValue() > staleThreshold) {
                staleChunks.add(entry.getKey());
            }
        }
        return staleChunks;
    }

    private static Set<UUID> removeStaleChunksFromState(ChunkWaitingState state, Set<ChunkPos> staleChunks) {
        Set<UUID> headsToResume = new HashSet<>();

        synchronized (waitingChunksLock) {
            for (ChunkPos staleChunk : staleChunks) {
                state.chunkTimestamps.remove(staleChunk);
                Set<UUID> affectedHeads = state.chunkToHeads.remove(staleChunk);
                if (affectedHeads != null) {
                    collectHeadsReadyToResume(state, staleChunk, affectedHeads, headsToResume);
                }
            }
        }
        return headsToResume;
    }

    private static void collectHeadsReadyToResume(ChunkWaitingState state, ChunkPos staleChunk,
                                                   Set<UUID> affectedHeads, Set<UUID> headsToResume) {
        for (UUID headId : affectedHeads) {
            Set<ChunkPos> headChunks = state.headWaitingChunks.get(headId);
            if (headChunks != null) {
                headChunks.remove(staleChunk);
                if (headChunks.isEmpty()) {
                    state.headWaitingChunks.remove(headId);
                    headsToResume.add(headId);
                }
            }
        }
    }

    private static void resumeHeadsAfterStaleCleanup(String dimensionKey, Set<UUID> headsToResume) {
        if (!dimensionKey.equals("minecraft:overworld")) {
            return;
        }

        net.minecraft.server.MinecraftServer server =
                net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }

        ServerLevel level = server.getLevel(net.minecraft.world.level.Level.OVERWORLD);
        if (level == null) {
            return;
        }

        TrackExpansionOrchestrator placer = TrackPlacerRegistry.get(level);
        if (placer != null && !placer.isComplete()) {
            scheduleHeadResumptions(level, placer, headsToResume);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (com.vodmordia.railwaysuntold.util.core.SystemStateManager.isShuttingDown()) {
            return;
        }

        int currentTick = tickCounter.incrementAndGet();

        if (currentTick % PlacementConstants.RESUME_CHECK_INTERVAL_TICKS == 0) {
            checkPausedHeadsForResume(event.getServer());
        }

        if (currentTick % DEFERRED_HEAD_CHECK_INTERVAL_TICKS == 0) {
            checkDeferredHeadsForLoadedChunks(event.getServer());
        }

        // The stall watchdog only runs during autoload - the hands-off mass-generation mode where a
        // silently-stuck head squats a slot. In normal play the PLAYER drives generation and a head
        // waiting for the player to come near (chunk-deferred) must NOT be kicked/retired.
        if (currentTick % 200 == 0 && AutoLoadController.isActive()) {
            runStallWatchdogs(event.getServer());
        }

        if (currentTick % getConfig().CHUNK_CLEANUP_INTERVAL_TICKS == 0) {
            cleanupStaleChunks();
        }

        if (currentTick % CHUNK_RELEASE_INTERVAL_TICKS == 0) {
            releaseChunksBehindHeads(event.getServer());
        }

    }

    /**
     * Releases CHUNK_LOAD_TICKETs we issued for chunks that are now behind / far from
     * each active head.
     */
    private static void releaseChunksBehindHeads(net.minecraft.server.MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            String dimensionKey = ChunkCoordinateUtil.getDimensionKey(level);
            Map<UUID, Set<ChunkPos>> ticketed = headTicketedChunks.get(dimensionKey);
            if (ticketed == null || ticketed.isEmpty()) {
                continue;
            }
            TrackExpansionOrchestrator placer = TrackPlacerRegistry.get(level);
            if (placer == null) {
                continue;
            }

            Map<UUID, Set<ChunkPos>> trioPinnedByHead = headTrioPinnedChunks.get(dimensionKey);

            var headEntries = ticketed.entrySet().iterator();
            while (headEntries.hasNext()) {
                var headEntry = headEntries.next();
                UUID headId = headEntry.getKey();
                Set<ChunkPos> chunks = headEntry.getValue();

                TrackExpansionHead head = placer.getHeadManager().getHeadById(headId);
                if (head == null || head.isComplete()) {
                    // Head is gone - drop all its tickets immediately.
                    for (ChunkPos chunk : chunks) {
                        level.getChunkSource().removeRegionTicket(CHUNK_LOAD_TICKET, chunk, 1, chunk);
                    }
                    headEntries.remove();
                    if (trioPinnedByHead != null) {
                        trioPinnedByHead.remove(headId);
                    }
                    continue;
                }

                Set<ChunkPos> trioPinned = trioPinnedByHead != null ? trioPinnedByHead.get(headId) : null;

                ChunkPos headChunk = new ChunkPos(head.getPosition());
                var chunkIter = chunks.iterator();
                while (chunkIter.hasNext()) {
                    ChunkPos chunk = chunkIter.next();
                    // Skip trio-pinned chunks: those are held by an active diagonal
                    // Entry->Straight->Exit sequence whose footprint legitimately extends
                    // far past the 8-chunk distance threshold.
                    if (trioPinned != null && trioPinned.contains(chunk)) {
                        continue;
                    }
                    int dx = chunk.x - headChunk.x;
                    int dz = chunk.z - headChunk.z;
                    if (Math.abs(dx) + Math.abs(dz) > CHUNK_RELEASE_DISTANCE) {
                        level.getChunkSource().removeRegionTicket(CHUNK_LOAD_TICKET, chunk, 1, chunk);
                        chunkIter.remove();
                    }
                }

                if (chunks.isEmpty()) {
                    headEntries.remove();
                }
            }
        }
    }

    private static void checkPausedHeadsForResume(net.minecraft.server.MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            TrackExpansionOrchestrator placer = TrackPlacerRegistry.get(level);
            if (placer != null && !placer.isComplete()) {
                checkPausedHeadsForResume(level, placer.getHeadManager(), placer);
            }
        }
    }

    private static void checkPausedHeadsForResume(ServerLevel level,
                                                  com.vodmordia.railwaysuntold.worldgen.head.ExpansionHeadManager headManager,
                                                  TrackExpansionOrchestrator placer) {
        for (TrackExpansionHead head : headManager.getPausedHeads()) {
            ChunkPos headChunk = new ChunkPos(head.getPosition());
            if (ChunkCoordinateUtil.getLoadedChunk(level, headChunk) == null) {
                continue;
            }
            // Defense against the halt↔resume loop: don't resume a head that has nothing
            // to aim at - it would immediately re-defer (no route target -> pipeline returns
            // empty -> non-chunk defer) and accumulate defers until it gets paused again.
            if (head.getVillageState().getRouteTarget() == null) {
                LOGGER.warn("[PAUSE-RESUME] Head {} at {}: chunk loaded but no route target, skipping resume",
                        head.getHeadNumber(), head.getPosition());
                continue;
            }
            head.resume();
            boolean scheduled = placer.scheduleHeadExpansion(head, PlacementConstants.MIN_EXPANSION_DELAY_TICKS,
                    HeadScheduler.ScheduleCaller.EXTERNAL);
            if (!scheduled) {
                LOGGER.warn("[PAUSE-RESUME] Head {} schedule REJECTED after pause resume", head.getHeadNumber());
            }
        }
    }

    private static void checkDeferredHeadsForLoadedChunks(net.minecraft.server.MinecraftServer server) {
        ServerLevel level = server.overworld();
        String dimensionKey = "minecraft:overworld";

        ChunkWaitingState state = getState(dimensionKey);
        if (state == null || state.headWaitingChunks.isEmpty()) {
            return;
        }

        TrackExpansionOrchestrator placer = TrackPlacerRegistry.get(level);
        if (placer == null || placer.isComplete()) {
            return;
        }

        Set<UUID> headsToResume = findHeadsWithAllChunksLoaded(state, level);

        for (UUID headId : headsToResume) {
            scheduleHeadResumption(level, headId);
        }
    }

    private static Set<UUID> findHeadsWithAllChunksLoaded(ChunkWaitingState state, ServerLevel level) {
        Set<UUID> headsToResume = new HashSet<>();

        synchronized (waitingChunksLock) {
            var iterator = state.headWaitingChunks.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                UUID headId = entry.getKey();
                Set<ChunkPos> waitingChunks = entry.getValue();

                removeLoadedChunksForHead(state, headId, waitingChunks, level);

                if (waitingChunks.isEmpty()) {
                    iterator.remove();
                    headsToResume.add(headId);
                }
            }
        }
        return headsToResume;
    }

    private static void removeLoadedChunksForHead(
            ChunkWaitingState state, UUID headId, Set<ChunkPos> waitingChunks, ServerLevel level) {

        var chunkIterator = waitingChunks.iterator();
        while (chunkIterator.hasNext()) {
            ChunkPos chunk = chunkIterator.next();
            if (ChunkCoordinateUtil.getLoadedChunk(level, chunk) == null) {
                continue;
            }

            chunkIterator.remove();
            removeHeadFromChunkMapping(state, headId, chunk);
            state.chunkTimestamps.remove(chunk);
        }
    }

    /** Drives each level's orchestrator stall watchdog - the safety net that catches a head gone
     *  silent (no progress, not failing) and escalates kick -> abandon-village -> retire. */
    private static void runStallWatchdogs(net.minecraft.server.MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            TrackExpansionOrchestrator placer = TrackPlacerRegistry.get(level);
            if (placer != null && !placer.isComplete()) {
                placer.runStallWatchdog();
            }
        }
    }

    /**
     * Returns the list of unloaded chunks that a head is waiting for, or empty list if none.
     */
    private static List<ChunkPos> getUnloadedChunksForHead(ChunkWaitingState state, UUID headId, ServerLevel level) {
        if (state == null) return List.of();
        Set<ChunkPos> waiting = state.headWaitingChunks.get(headId);
        if (waiting == null || waiting.isEmpty()) return List.of();
        return waiting.stream()
                .filter(c -> ChunkCoordinateUtil.getLoadedChunk(level, c) == null)
                .toList();
    }

    /**
     * Force-loads the chunks every deferred head is currently waiting on. The reactive deferral only
     * REGISTERS waiting chunks (it never tickets them - it relied on a player/Chunky to load them), and
     * the autoload corridor only covers chunks straight ahead of MOVING heads - so a deferred head whose
     * needed chunk is off-corridor stalls forever. The autoload driver calls this each tick to issue a
     * {@link #CHUNK_LOAD_TICKET} (idempotently re-asserted) for each UNLOADED waiting chunk and track it for
     * the behind-head release sweep, reviving stalled heads regardless of direction. Returns the count of
     * chunks newly ticketed (for the autoload budget).
     *
     * <p>Only heads in {@code onlyHeads} are considered (null = all); the autoload round-robin passes its
     * active window so parked heads aren't revived.
     */
    public static int forceLoadDeferredWaitingChunks(ServerLevel level, Set<UUID> onlyHeads) {
        String dimensionKey = ChunkCoordinateUtil.getDimensionKey(level);
        ChunkWaitingState state = getState(dimensionKey);
        if (state == null) return 0;
        int newlyTicketed = 0;
        synchronized (waitingChunksLock) {
            for (UUID headId : new ArrayList<>(state.headWaitingChunks.keySet())) {
                if (onlyHeads != null && !onlyHeads.contains(headId)) continue;
                Set<ChunkPos> tracked = headTicketedChunks
                        .computeIfAbsent(dimensionKey, k -> new ConcurrentHashMap<>())
                        .computeIfAbsent(headId, k -> ConcurrentHashMap.newKeySet());
                for (ChunkPos chunk : getUnloadedChunksForHead(state, headId, level)) {
                    level.getChunkSource().addRegionTicket(CHUNK_LOAD_TICKET, chunk, 1, chunk);
                    if (tracked.add(chunk)) {
                        newlyTicketed++;
                    }
                }
            }
        }
        return newlyTicketed;
    }

}
