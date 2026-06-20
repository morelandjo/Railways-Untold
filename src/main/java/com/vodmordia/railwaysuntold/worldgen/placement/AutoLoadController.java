package com.vodmordia.railwaysuntold.worldgen.placement;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.worldgen.head.TrackExpansionHead;
import com.vodmordia.railwaysuntold.worldgen.village.VillageTargetingSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.*;

/**
 * Test/utility driver that self-loads chunks along the rail corridor so track generation proceeds
 * hands-off, the way Chunky's moving window does - but following the heads instead of brute-forcing a
 * square. Each server tick it force-tickets the chunks just ahead of every active head; the existing
 * release sweep in {@link ChunkLoadTrackExpander} unloads them once the head has moved >8 chunks past,
 * so loaded chunks stay a thin moving band along the track. Coexists with Chunky / player view distance
 * (tickets simply stack), and is enabled per-session via {@code /railways autoload}.
 *
 * Hard backstops so a forgotten session can't fill the disk: it auto-disables when the requested timer
 * expires, at a wall-clock ceiling, or once it has requested a chunk budget - whichever comes first.
 * Always starts OFF on server boot and never auto-resumes.
 */
public final class AutoLoadController {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Chunks ahead of each head to keep generated. The far corner sits at Manhattan
     * {@code CORRIDOR_AHEAD_CHUNKS + CORRIDOR_LATERAL_CHUNKS} from the head, which must stay at or
     * below the release-sweep distance ({@code CHUNK_RELEASE_DISTANCE} = 6) or the corridor's own
     * corners would be unloaded the moment they're ticketed.
     */
    private static final int CORRIDOR_AHEAD_CHUNKS = 4;
    private static final int CORRIDOR_LATERAL_CHUNKS = 2;

    /**
     * Round-robin: only this many heads generate at once; the rest are parked (held by the
     * HeadScheduler via {@link #mayHeadRun}) so the CONCURRENT actively-generating footprint is
     * bounded by the working set, not the total head count. A held head stops ticketing chunks
     * ahead, so its transient generation borders drain away and it settles to a small static band -
     * that's what keeps the concurrent footprint bounded. The window rotates so every head
     * progresses over the session.
     */
    private static final int ACTIVE_HEAD_BUDGET = 4;
    private static final long ROTATION_PERIOD_TICKS = 200;   // 10s per round-robin slice

    /** Backstops applied regardless of the requested duration. */
    private static final long MAX_DURATION_TICKS = 2L * 60 * 60 * 20;   // 2 hours
    private static final long MAX_CHUNK_BUDGET = 200_000;               // total chunks requested

    /**
     * Load-clear (stop-and-go) gates. Force-loading generation outruns the chunk system's
     * generate-then-save-and-evict pipeline; once the server falls behind, the unload drain
     * collapses and resident chunks climb without bound until it freezes. So generation runs in
     * bursts, paused while the backlog drains.
     *
     * The SOLE brake is server tick HEALTH, not a raw chunk count: tick health holds (≈50ms ticks)
     * well past 10k resident chunks, so a chunk-count cap needlessly throttles a
     * machine that's keeping up. Generation runs as fast as the machine sustains and only pauses when
     * ticks degrade; resident count is bounded solely by where that happens (the per-session
     * {@link #MAX_CHUNK_BUDGET} and the timer still cap the run overall). Draining ends as soon as
     * the backlog has actually cleared (resident count stops falling - {@link #PLATEAU_WINDOW_TICKS})
     * rather than waiting a fixed timeout past the floor.
     */
    private static final int LOADED_CHUNK_RESUME = 4000;      // fast-path resume once drained this low (low head counts)
    private static final double TICK_PERIOD_PAUSE_MS = 60.0;  // pause if the smoothed tick period exceeds this (server behind)
    private static final double TICK_PERIOD_RESUME_MS = 53.0; // resume once the tick period recovers below this
    /** A resident drop smaller than this (chunks) doesn't count as drain progress (noise floor). */
    private static final int PLATEAU_DELTA = 200;
    /** Resume once resident chunks haven't meaningfully fallen for this long - the backlog is drained
     *  and what remains is the active heads' irreducible floor; waiting longer achieves nothing. */
    private static final long PLATEAU_WINDOW_TICKS = 60;      // 3s
    /** After resuming, suppress tick-health re-pausing for this long so the one-off resume burst
     *  (all heads un-hold + the deferred backlog tickets at once) doesn't instantly re-trip the gate. */
    private static final long RESUME_GRACE_TICKS = 40;        // 2s
    /** Ultimate safety: resume a drain after this long no matter what (should never be the reason). */
    private static final long MAX_DRAIN_TICKS = 600;          // 30s

    private static volatile boolean active = false;
    private static long startTick = 0;
    private static long expiryTick = 0;
    private static long chunksRequested = 0;

    /** Total track placed (blocks of segment distance) since server boot - shown on the sidebar. */
    private static volatile long totalTrackBlocks = 0;

    /** On-screen sidebar list; refreshed every {@link #BAR_UPDATE_PERIOD_TICKS} while active. */
    private static final AutoLoadSidebar SIDEBAR = new AutoLoadSidebar();
    private static long lastBarUpdateTick = 0;
    private static final long BAR_UPDATE_PERIOD_TICKS = 10;   // refresh twice a second

    /** Stop-and-go state: true while generation is paused waiting for the chunk backlog to drain. */
    private static boolean draining = false;
    private static long drainStartTick = 0;
    private static int drainMinLoaded = 0;        // lowest resident count seen this drain (ratchets down by PLATEAU_DELTA)
    private static long drainLastImproveTick = 0; // last tick the drain made meaningful progress
    private static long resumeGraceUntilTick = 0; // tick-health pauses suppressed until here after a resume

    /** Smoothed server tick period (ms) measured between successive ticks; ~50ms when keeping up,
     *  higher once the server is compute-bound. Mapping-independent overload signal. */
    private static long lastTickNanos = 0;
    private static double tickPeriodEmaMs = 50.0;

    /** Round-robin state: which heads may generate this slice, and the rotating window offset. */
    private static volatile Set<UUID> generationWindow = Set.of();
    private static int rotationOffset = 0;
    private static long lastRotationTick = 0;

    private AutoLoadController() {}

    /** Enable for the given duration in ticks, clamped to the wall-clock backstop. */
    public static synchronized void enable(MinecraftServer server, long durationTicks) {
        long dur = Math.max(1, Math.min(durationTicks, MAX_DURATION_TICKS));
        startTick = server.getTickCount();
        expiryTick = startTick + dur;
        lastBarUpdateTick = 0;
        chunksRequested = 0;
        draining = false;
        resumeGraceUntilTick = 0;
        lastTickNanos = 0;
        tickPeriodEmaMs = 50.0;
        generationWindow = Set.of();
        rotationOffset = 0;
        lastRotationTick = 0;
        active = true;
        String msg = "[RailwaysUntold] auto-load ON for " + formatTicks(dur)
                + " (caps: " + formatTicks(MAX_DURATION_TICKS) + " / " + MAX_CHUNK_BUDGET + " chunks)";
        LOGGER.info(msg);
        broadcast(server, msg);
    }

    public static synchronized void disable(MinecraftServer server, String reason) {
        if (!active) return;
        active = false;
        LOGGER.info("[RailwaysUntold] auto-load OFF ({}, {} track blocks, {} chunks requested)",
                reason, totalTrackBlocks, chunksRequested);
        if (server != null) {
            SIDEBAR.hide(server);
            broadcastSummary(server, reason);
        }
    }

    /** Final stats dumped to chat when a session ends - heads built, track laid, chunks, runtime. */
    private static void broadcastSummary(MinecraftServer server, String reason) {
        long elapsed = Math.max(0, server.getTickCount() - startTick);
        broadcast(server, "[RailwaysUntold] auto-load finished - " + reason);
        broadcast(server, "  Track placed: " + String.format("%,d", totalTrackBlocks) + " blocks");
        broadcast(server, "  Chunks: " + String.format("%,d / %,d", chunksRequested, MAX_CHUNK_BUDGET));
        broadcast(server, "  Heads remaining: " + activeHeadCount(server));
        broadcast(server, "  Ran for: " + formatTicks(elapsed));
    }

    public static boolean isActive() {
        return active;
    }

    /**
     * Record newly placed track (blocks of segment distance) toward the running total shown on the
     * sidebar. Counted unconditionally (not just while auto-load is on) so the figure reflects the
     * world's whole build this session; cleared on server boot via reset().
     */
    public static void addTrackBlocks(int blocks) {
        if (blocks > 0) totalTrackBlocks += blocks;
    }

    public static String status(MinecraftServer server) {
        if (!active) return "auto-load is OFF";
        long remain = Math.max(0, expiryTick - server.getTickCount());
        return "auto-load ON" + (draining ? " (draining)" : "") + " - " + formatTicks(remain) + " left, "
                + activeHeadCount(server) + " heads, " + totalTrackBlocks + " track blocks, "
                + chunksRequested + "/" + MAX_CHUNK_BUDGET + " chunks requested";
    }

    /** Reset to OFF - called on server start so a session never auto-resumes within the same JVM. */
    public static void reset(MinecraftServer server) {
        active = false;
        SIDEBAR.clearStale(server);
        chunksRequested = 0;
        startTick = 0;
        expiryTick = 0;
        totalTrackBlocks = 0;
        lastBarUpdateTick = 0;
        draining = false;
        resumeGraceUntilTick = 0;
        lastTickNanos = 0;
        tickPeriodEmaMs = 50.0;
        generationWindow = Set.of();
        rotationOffset = 0;
        lastRotationTick = 0;
    }

    /**
     * Whether {@code headId} is currently allowed to run its expansion. Always true when autoload is
     * off (normal play is unaffected). While autoload is on, false during a drain (all heads held) or
     * when the head is outside the current round-robin window (parked this slice). The HeadScheduler
     * consults this to hold parked/draining heads without executing.
     */
    public static boolean mayHeadRun(UUID headId) {
        if (!active) return true;
        if (draining) return false;
        Set<UUID> window = generationWindow;
        return window.isEmpty() || window.contains(headId);
    }

    /** Called every server tick: enforce the backstops, then force-load the corridor ahead of each head. */
    public static void tick(MinecraftServer server) {
        if (!active) return;
        if (server.getTickCount() >= expiryTick) {
            disable(server, "timer expired");
            return;
        }
        if (chunksRequested >= MAX_CHUNK_BUDGET) {
            disable(server, "chunk budget reached");
            return;
        }
        // Only treat all-heads-complete as "done" once this session has actually driven generation,
        // so enabling over an already-finished world doesn't instantly self-stop.
        if (chunksRequested > 0 && allGenerationComplete(server)) {
            disable(server, "generation complete");
            return;
        }

        // Load-clear gate: pause generation when the chunk system is saturated, resume once drained.
        // Runs every tick (even while paused) so the tick-period EMA keeps tracking recovery.
        updateTickPeriodEma();
        boolean generate = shouldGenerateThisTick(server);
        maybeUpdateProgressBar(server);   // reflects the just-computed drain state
        if (!generate) {
            return;
        }

        // Round-robin: only the windowed heads generate this slice. Parked heads are held by the
        // HeadScheduler (via mayHeadRun); once they stop generating, their transient generation
        // borders drain on their own, so the concurrent footprint stays bounded by the window size
        // rather than the total head count - without tearing down their static band (which would
        // unload their just-placed tip and strand them re-placing the same segment).
        Set<UUID> window = computeWindow(server);
        generationWindow = window;

        for (ServerLevel level : server.getAllLevels()) {
            TrackExpansionOrchestrator orch = TrackPlacerRegistry.get(level);
            if (orch == null || orch.isComplete()) continue;
            // Revive deferred/stalled WINDOW heads: force-load the exact chunks they are waiting on.
            // The forward corridor below only feeds MOVING heads; a head whose needed chunk fell off
            // any corridor (a defer behind/lateral, a replan target, a branch turn) would otherwise
            // stall forever, since the reactive deferral never tickets its waiting chunks.
            chunksRequested += ChunkLoadTrackExpander.forceLoadDeferredWaitingChunks(level, window);
            // Pre-warm the forward corridor of each windowed head so moving heads rarely defer at all.
            for (TrackExpansionHead head : orch.getHeadManager().getActiveHeads()) {
                if (head == null || head.isComplete() || !window.contains(head.getHeadId())) continue;
                chunksRequested += ChunkLoadTrackExpander.holdWorkingChunks(
                        level, head.getHeadId(), corridorAhead(head));
            }
        }
    }

    /**
     * The set of head IDs allowed to generate this slice. All active heads when there are at most
     * {@link #ACTIVE_HEAD_BUDGET}; otherwise a window of that many, advanced every
     * {@link #ROTATION_PERIOD_TICKS} so every head gets turns. Heads are ordered by head number for a
     * stable rotation as branches spawn and heads complete.
     */
    private static Set<UUID> computeWindow(MinecraftServer server) {
        List<TrackExpansionHead> heads = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            TrackExpansionOrchestrator orch = TrackPlacerRegistry.get(level);
            if (orch == null || orch.isComplete()) continue;
            for (TrackExpansionHead head : orch.getHeadManager().getActiveHeads()) {
                if (head != null && !head.isComplete()) heads.add(head);
            }
        }
        if (heads.size() <= ACTIVE_HEAD_BUDGET) {
            Set<UUID> all = new HashSet<>();
            for (TrackExpansionHead head : heads) all.add(head.getHeadId());
            return all;
        }
        heads.sort(Comparator.comparingInt(TrackExpansionHead::getHeadNumber));
        long now = server.getTickCount();
        if (now - lastRotationTick >= ROTATION_PERIOD_TICKS) {
            rotationOffset += ACTIVE_HEAD_BUDGET;
            lastRotationTick = now;
        }
        int n = heads.size();
        int start = ((rotationOffset % n) + n) % n;
        Set<UUID> window = new HashSet<>();
        for (int i = 0; i < ACTIVE_HEAD_BUDGET; i++) {
            window.add(heads.get((start + i) % n).getHeadId());
        }
        return window;
    }

    /**
     * Load-clear decision for this tick. Returns {@code false} (skip generation) while draining a
     * backlog. The brake is server tick HEALTH: pause when the server falls behind, resume once it
     * recovers and the backlog has drained (count low or stopped falling). Generation otherwise runs
     * as fast as the machine sustains - the resident count is bounded only by where ticks degrade.
     */
    private static boolean shouldGenerateThisTick(MinecraftServer server) {
        int loaded = totalLoadedChunks(server);
        long nowTick = server.getTickCount();
        boolean tickOk = tickPeriodEmaMs <= TICK_PERIOD_RESUME_MS;

        if (draining) {
            // Track drain progress: the resident count ratchets down in steps of PLATEAU_DELTA.
            if (loaded <= drainMinLoaded - PLATEAU_DELTA) {
                drainMinLoaded = loaded;
                drainLastImproveTick = nowTick;
            }
            boolean plateaued = (nowTick - drainLastImproveTick) >= PLATEAU_WINDOW_TICKS;
            boolean chunksOk = loaded <= LOADED_CHUNK_RESUME;
            boolean drainedTooLong = (nowTick - drainStartTick) >= MAX_DRAIN_TICKS;
            // Resume once the server is healthy AND the backlog has cleared (drained low, or stopped
            // falling = floor reached). The plateau check is what avoids waiting past the floor.
            if ((tickOk && (chunksOk || plateaued)) || drainedTooLong) {
                draining = false;
                resumeGraceUntilTick = nowTick + RESUME_GRACE_TICKS;
                return true;
            }
            return false;
        }

        // Tick-health is the sole governor: pause only when the server falls behind (outside the
        // post-resume grace window so the one-tick resume burst can't instantly re-trip it).
        boolean tickBad = tickPeriodEmaMs >= TICK_PERIOD_PAUSE_MS && nowTick >= resumeGraceUntilTick;
        if (tickBad) {
            draining = true;
            drainStartTick = nowTick;
            drainMinLoaded = loaded;
            drainLastImproveTick = nowTick;
            return false;
        }
        return true;
    }

    /** Smoothed wall-clock period between server ticks (ms). ~50ms when keeping up; rises once the
     *  server is compute-bound and running catch-up ticks with no sleep. */
    private static void updateTickPeriodEma() {
        long now = System.nanoTime();
        if (lastTickNanos != 0L) {
            double periodMs = (now - lastTickNanos) / 1_000_000.0;
            // Ignore absurd gaps (first tick after a long stall/GC) so one spike can't skew the average.
            if (periodMs < 1000.0) {
                tickPeriodEmaMs = tickPeriodEmaMs * 0.9 + periodMs * 0.1;
            }
        }
        lastTickNanos = now;
    }

    /**
     * Refresh the on-screen sidebar (throttled to {@link #BAR_UPDATE_PERIOD_TICKS}) with the live
     * stats as a left-justified list, one per line: active head count, total track length, time
     * remaining, and chunks requested vs the budget cap.
     */
    private static void maybeUpdateProgressBar(MinecraftServer server) {
        long now = server.getTickCount();
        if (now - lastBarUpdateTick < BAR_UPDATE_PERIOD_TICKS) return;
        lastBarUpdateTick = now;

        int heads = activeHeadCount(server);
        int totalHeads = totalHeadCount(server);
        int stations = stationCount(server);
        long remain = Math.max(0, expiryTick - now);

        Component title = Component.literal("Railways auto-load" + (draining ? " (draining)" : ""));
        List<String> lines = List.of(
                String.format("Heads: %d", heads),
                String.format("Total heads: %d", totalHeads),
                String.format("Stations: %d", stations),
                String.format("Track: %,d blocks", totalTrackBlocks),
                String.format("Time left: %s", formatCountdown(remain)),
                String.format("Chunks: %,d / %,d", chunksRequested, MAX_CHUNK_BUDGET));
        SIDEBAR.update(server, title, lines);
    }

    /**
     * True once every orchestrator that has bootstrapped has finished all its heads - i.e. there is
     * nothing left to generate. False while none exist yet (pre-bootstrap) or any head is still
     * running, since ExpansionHeadManager.isComplete() is false with no heads.
     */
    private static boolean allGenerationComplete(MinecraftServer server) {
        boolean anyOrchestrator = false;
        for (ServerLevel level : server.getAllLevels()) {
            TrackExpansionOrchestrator orch = TrackPlacerRegistry.get(level);
            if (orch == null) continue;
            anyOrchestrator = true;
            if (!orch.isComplete()) return false;
        }
        return anyOrchestrator;
    }

    /** Active (incomplete) heads across all dimensions - the "number of heads" stat. */
    private static int activeHeadCount(MinecraftServer server) {
        int total = 0;
        for (ServerLevel level : server.getAllLevels()) {
            TrackExpansionOrchestrator orch = TrackPlacerRegistry.get(level);
            if (orch == null || orch.isComplete()) continue;
            total += orch.getHeadManager().getActiveHeadCount();
        }
        return total;
    }

    /**
     * Cumulative heads ever created across all dimensions, including completed and
     * merged ones - runs higher than the active limit over a world's lifetime.
     * Completed orchestrators still count, so they are not skipped here.
     */
    private static int totalHeadCount(MinecraftServer server) {
        int total = 0;
        for (ServerLevel level : server.getAllLevels()) {
            TrackExpansionOrchestrator orch = TrackPlacerRegistry.get(level);
            if (orch == null) continue;
            total += orch.getHeadManager().getTotalHeadCount();
        }
        return total;
    }

    /**
     * Stations successfully placed across all dimensions that have generation.
     * Gated on an orchestrator being present so dimensions without track gen are
     * not given empty saved data just to read a zero.
     */
    private static int stationCount(MinecraftServer server) {
        int total = 0;
        for (ServerLevel level : server.getAllLevels()) {
            if (TrackPlacerRegistry.get(level) == null) continue;
            total += VillageTargetingSavedData.get(level).getStationTracker().getPlacedStationCount();
        }
        return total;
    }

    /** Live countdown as {@code m:ss} (or {@code h:mm:ss}), so seconds visibly tick down on the bar. */
    private static String formatCountdown(long ticks) {
        long secs = ticks / 20;
        long h = secs / 3600;
        long m = (secs % 3600) / 60;
        long s = secs % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    /** Total resident chunk holders across all dimensions - the resource pressure the gate caps. */
    private static int totalLoadedChunks(MinecraftServer server) {
        int total = 0;
        for (ServerLevel level : server.getAllLevels()) {
            total += level.getChunkSource().getLoadedChunksCount();
        }
        return total;
    }

    /** Chunk set forming a {@code (2*lateral+1)}-wide band running {@code ahead} chunks in the head's heading. */
    private static Set<ChunkPos> corridorAhead(TrackExpansionHead head) {
        BlockPos pos = head.getPosition();
        Direction dir = head.getDirection();
        Direction lat = dir.getClockWise();
        int hcx = pos.getX() >> 4;
        int hcz = pos.getZ() >> 4;
        Set<ChunkPos> corridor = new HashSet<>();
        for (int f = 0; f <= CORRIDOR_AHEAD_CHUNKS; f++) {
            for (int s = -CORRIDOR_LATERAL_CHUNKS; s <= CORRIDOR_LATERAL_CHUNKS; s++) {
                corridor.add(new ChunkPos(
                        hcx + dir.getStepX() * f + lat.getStepX() * s,
                        hcz + dir.getStepZ() * f + lat.getStepZ() * s));
            }
        }
        return corridor;
    }

    private static void broadcast(MinecraftServer server, String msg) {
        server.getPlayerList().broadcastSystemMessage(Component.literal(msg), false);
    }

    /** Human-readable ticks -> e.g. "1h30m", "45m", "20s". */
    private static String formatTicks(long ticks) {
        long secs = ticks / 20;
        long h = secs / 3600;
        long m = (secs % 3600) / 60;
        long s = secs % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append('h');
        if (m > 0) sb.append(m).append('m');
        if (h == 0 && m == 0) sb.append(s).append('s');
        return sb.toString();
    }
}
