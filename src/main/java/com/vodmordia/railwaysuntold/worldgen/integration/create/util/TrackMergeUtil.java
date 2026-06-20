package com.vodmordia.railwaysuntold.worldgen.integration.create.util;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.util.block.ChunkSafeBlockAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

/**
 * Reusable utilities for handling track intersections where multiple track heads
 * meet at the same position. Ensures fake_track blocks are replaced with real
 * ITrackBlock instances and that graph connections bridge overlapping segments.
 */
public final class TrackMergeUtil {

    private static final Logger LOGGER = LogUtils.getLogger();

    private TrackMergeUtil() {}

    /**
     * Result of ensuring a position has a real track block.
     */
    public static final class EnsureResult {
        /** True if the position had any track (real or fake) before this call. */
        public final boolean hadExistingTrack;

        private EnsureResult(boolean hadExistingTrack) {
            this.hadExistingTrack = hadExistingTrack;
        }

        public static EnsureResult noTrack() {
            return new EnsureResult(false);
        }

        public static EnsureResult existingReal() {
            return new EnsureResult(true);
        }

        public static EnsureResult replacedFake() {
            return new EnsureResult(true);
        }
    }

    /**
     * Ensures a position has a real ITrackBlock track block. If fake_track is present,
     * replaces it with a real straight track block in the given direction.
     *
     * @param level     The server level
     * @param pos       The position to check/fix
     * @param direction The track direction for replacement
     * @return EnsureResult describing what was found/done
     */
    public static EnsureResult ensureRealTrack(ServerLevel level, BlockPos pos, Direction direction) {
        com.vodmordia.railwaysuntold.worldgen.integration.railway.RailwayModCompat.applyMaterialForBiome(level, pos);
        BlockState existing = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, pos);
        if (existing == null) {
            return EnsureResult.noTrack();
        }

        if (CreateTrackUtil.isFakeTrack(existing)) {
            BlockState realTrack = CreateTrackUtil.getStraightTrack(direction);
            if (realTrack != null) {
                ChunkSafeBlockAccess.setBlockStateNonBlocking(level, pos, realTrack, true);
                LOGGER.debug("[TrackMerge] Replaced fake_track with real track at {} (dir={})",
                        pos.toShortString(), direction);
            }
            return EnsureResult.replacedFake();
        }

        if (CreateTrackUtil.isTrackBlock(existing)) {
            return EnsureResult.existingReal();
        }

        return EnsureResult.noTrack();
    }

    /**
     * Creates a graph connection bridging a segment that overlaps with existing track.
     *
     * @param level     The server level
     * @param start     Start position of the segment
     * @param end       End position of the segment
     * @param direction Direction from start to end
     */
    public static void bridgeTrackGraph(ServerLevel level, BlockPos start, BlockPos end, Direction direction) {
        if (!DirectGraphConnector.connectStraightDirect(level, start, end, direction)) {
            LOGGER.debug("[TrackMerge] No graph available to bridge {} -> {}, will connect via propagation",
                    start.toShortString(), end.toShortString());
        }
    }
}
