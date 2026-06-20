package com.vodmordia.railwaysuntold.worldgen.placement;

import com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-dimension registry of chunks that have fired a load event in this session,
 * plus on-demand reload of those chunks.
 *
 * <p>A chunk in the registry has been generated (by chunky, by player view distance,
 * etc.) and exists on disk. This lives separately from {@code ChunkLoadTrackExpander} so
 * low-level callers (e.g. branch validation) can request a seen-chunk reload without
 * depending on the expansion-orchestration layer, which would otherwise form a class cycle.
 */
public final class SeenChunkRegistry {

    private SeenChunkRegistry() {
    }

    /** Lifespan of a seen-chunk on-demand load ticket. */
    private static final int SEEN_LOAD_TIMEOUT_TICKS = 200;

    /**
     * Self-expiring ticket for "seen" chunks pulled in on demand for a one-off read (branch
     * validation). Unlike the expander's tracked load ticket these aren't tracked for manual
     * release: the timeout lets the DistanceManager purge them automatically, so a routing read
     * that briefly needs a chunk can't permanently pin it (which would accumulate force-loaded
     * chunks all session and slow the world). Re-requesting refreshes the timer, so a chunk stays
     * resident only while something keeps asking for it.
     */
    private static final TicketType<ChunkPos> SEEN_LOAD_TICKET = TicketType.create(
            "railwaysuntold_seen_load",
            Comparator.comparingLong(ChunkPos::toLong),
            SEEN_LOAD_TIMEOUT_TICKS);

    private static final Map<String, Set<ChunkPos>> seenChunks = new ConcurrentHashMap<>();

    /** Record that a chunk fired a load event (it exists on disk). */
    public static void markSeen(ServerLevel level, ChunkPos chunk) {
        seenChunks.computeIfAbsent(ChunkCoordinateUtil.getDimensionKey(level), k -> ConcurrentHashMap.newKeySet())
                .add(chunk);
    }

    /** Chunks seen this session in the given dimension (empty if none). */
    public static Set<ChunkPos> seenIn(ServerLevel level) {
        Set<ChunkPos> seen = seenChunks.get(ChunkCoordinateUtil.getDimensionKey(level));
        return seen == null ? Collections.emptySet() : seen;
    }

    /**
     * Issues a self-expiring {@link #SEEN_LOAD_TICKET} for each requested chunk that is currently
     * unloaded but was seen earlier this session, so it reloads from disk for the brief read that
     * needs it.
     */
    public static void requestSeenChunkLoad(ServerLevel level, Iterable<ChunkPos> chunks) {
        Set<ChunkPos> seen = seenIn(level);
        for (ChunkPos chunk : chunks) {
            if (ChunkCoordinateUtil.getLoadedChunk(level, chunk) == null && seen.contains(chunk)) {
                level.getChunkSource().addRegionTicket(SEEN_LOAD_TICKET, chunk, 1, chunk);
            }
        }
    }

    /** Forget every seen chunk in every dimension (session reset). */
    public static void clear() {
        seenChunks.clear();
    }
}
