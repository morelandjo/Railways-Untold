package com.vodmordia.railwaysuntold.worldgen.survey;

import com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil;
import com.vodmordia.railwaysuntold.worldgen.integration.deferred.DeferredProcessingManager;
import com.vodmordia.railwaysuntold.worldgen.survey.persist.StoredSurvey;
import com.vodmordia.railwaysuntold.worldgen.survey.persist.SurveySavedData;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The region-survey engine. On the server tick it walks in-flight requests through
 * PENDING -> LOADING -> EXTRACTING -> COMPLETE/FAILED: checks the persisted cache, force-loads the region
 * (its own {@link #SURVEY_TICKET} so the head-distance release sweep can't touch it), runs the
 * requested extractors once all chunks reach the needed status, persists the result, releases the
 * tickets, and fires the callback. Requests dedupe by region id. Off-thread callers never touch this -
 * they read {@link SurveySavedData}; only server-thread code calls {@link #request}.
 */
public final class SurveyManager extends DeferredProcessingManager {

    private static final SurveyManager INSTANCE = new SurveyManager();
    private static final int PROCESS_INTERVAL_TICKS = 2;
    /** Give a region this many ticks to finish loading before giving up. */
    private static final int LOAD_TIMEOUT_TICKS = 600;

    /** Manual-cleanup ticket, separate from ChunkLoadTrackExpander's so survey chunks aren't released
     *  by the head-distance sweep. */
    private static final TicketType<ChunkPos> SURVEY_TICKET = TicketType.create(
            "railwaysuntold_survey", Comparator.comparingLong(ChunkPos::toLong), 0);

    private static final Map<String, PendingSurvey> PENDING = new ConcurrentHashMap<>();
    private final SurveyGate gate = new SurveyGate();

    private SurveyManager() {
    }

    public static void register() {
        TickEvent.SERVER_POST.register(SurveyManager::onServerTick);
    }

    @Override
    protected int getProcessIntervalTicks() {
        return PROCESS_INTERVAL_TICKS;
    }

    /** Submit a survey (server thread). Deduped by region: a second request for an in-flight region is
     *  a no-op (its callback is dropped - consumers re-query the cache on their next pass). */
    public static void request(ServerLevel level, SurveyRequest req) {
        PENDING.computeIfAbsent(req.region().id(), k -> new PendingSurvey(level, req));
    }

    public static boolean isInFlight(RegionKey key) {
        return PENDING.containsKey(key.id());
    }

    /** Drop all in-flight requests and release their tickets (level unload / shutdown). */
    public static void clearAll() {
        for (PendingSurvey p : PENDING.values()) {
            releaseTickets(p);
        }
        PENDING.clear();
    }

    private static void onServerTick(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            long tick = level.getGameTime();
            if (!INSTANCE.shouldProcess(tick)) {
                continue;
            }
            INSTANCE.gate.resetTick();
            INSTANCE.processLevel(level);
        }
    }

    private void processLevel(ServerLevel level) {
        List<String> done = new ArrayList<>();
        for (PendingSurvey p : PENDING.values()) {
            if (p.level != level) {
                if (isStaleLevel(p.level, level)) {
                    // a previous world's request that will never run here
                    done.add(p.req.region().id());
                }
                continue;
            }
            if (advance(level, p)) {
                done.add(p.req.region().id());
            }
        }
        for (String id : done) {
            PENDING.remove(id);
        }
    }

    /** Advances one request; returns true when it reaches a terminal state and should be removed. */
    private boolean advance(ServerLevel level, PendingSurvey p) {
        switch (p.status) {
            case PENDING -> {
                Optional<StoredSurvey> cached = SurveySavedData.get(level).find(p.req.region());
                if (cached.isPresent()) {
                    fire(p, cached.get().result());
                    return true;
                }
                if (!gate.tryStartLoad()) {
                    return false; // budget exhausted this tick; retry next tick
                }
                p.startTick = level.getGameTime();
                p.status = SurveyStatus.LOADING;
                return false;
            }
            case LOADING -> {
                if (allChunksReady(level, p)) {
                    extractAndFinish(level, p);
                    return true;
                }
                if (level.getGameTime() - p.startTick > LOAD_TIMEOUT_TICKS) {
                    releaseTickets(p);
                    fire(p, SurveyResult.empty());
                    return true;
                }
                return false;
            }
            default -> {
                return true;
            }
        }
    }

    private boolean allChunksReady(ServerLevel level, PendingSurvey p) {
        if (p.req.maxRequiredStatus() == ChunkStatus.FULL) {
            // Async ticket load to FULL; wait for residency.
            if (!p.ticketsIssued) {
                for (ChunkPos c : p.chunks) {
                    level.getChunkSource().addRegionTicket(SURVEY_TICKET, c, 1, c);
                }
                p.ticketsIssued = true;
            }
            for (ChunkPos c : p.chunks) {
                if (ChunkCoordinateUtil.getLoadedChunk(level, c) == null) {
                    return false;
                }
            }
            return true;
        }
        // Cheap path: generate each chunk to the required (sub-FULL) status this tick. No ticket needed
        // - extraction reads them in the same tick and we don't keep them resident.
        for (ChunkPos c : p.chunks) {
            if (level.getChunk(c.x, c.z, p.req.maxRequiredStatus(), true) == null) {
                return false;
            }
        }
        return true;
    }

    private void extractAndFinish(ServerLevel level, PendingSurvey p) {
        SurveyContext ctx = new SurveyContext(level, p.req.region(), p.chunks);
        Map<String, SurveyData> byExtractor = new HashMap<>();
        for (String id : p.req.extractorIds()) {
            SurveyExtractor<?> extractor = SurveyExtractorRegistry.byId(id);
            if (extractor == null) {
                continue;
            }
            SurveyData data = extractor.extract(ctx);
            if (data != null) {
                byExtractor.put(id, data);
            }
        }
        SurveyResult result = new SurveyResult(byExtractor);
        SurveySavedData.get(level).store(new StoredSurvey(p.req.region(), result));
        releaseTickets(p);
        fire(p, result);
    }

    private static void releaseTickets(PendingSurvey p) {
        if (!p.ticketsIssued) {
            return;
        }
        for (ChunkPos c : p.chunks) {
            p.level.getChunkSource().removeRegionTicket(SURVEY_TICKET, c, 1, c);
        }
        p.ticketsIssued = false;
    }

    private static void fire(PendingSurvey p, SurveyResult result) {
        if (p.req.onComplete() != null) {
            p.req.onComplete().accept(result);
        }
    }

    /** In-flight survey state. All mutation happens on the server thread in {@link #processLevel}. */
    private static final class PendingSurvey {
        final ServerLevel level;
        final SurveyRequest req;
        final Set<ChunkPos> chunks;
        SurveyStatus status = SurveyStatus.PENDING;
        long startTick;
        boolean ticketsIssued;

        PendingSurvey(ServerLevel level, SurveyRequest req) {
            this.level = level;
            this.req = req;
            this.chunks = req.region().chunks();
        }
    }
}
