package com.vodmordia.railwaysuntold.worldgen.tracking;

import com.vodmordia.railwaysuntold.worldgen.head.TrackExpansionHead;
import com.vodmordia.railwaysuntold.worldgen.tracking.BoundarySegmentData.ConnectedSegment;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.List;
import java.util.UUID;

/**
 * Resolves the physical tip of a head's placed track by consulting
 * {@link ConnectedBoundaryTracker}, rather than trusting {@code head.getPosition()}.
 *
 */
public final class PhysicalTrackTipLocator {

    // Search window: the head's chunk plus one ring of neighbors. The tip is almost
    // always in the head's own chunk; the ring covers chunk-boundary edge cases.
    private static final int CHUNK_RADIUS = 1;

    // Maximum XZ Manhattan distance we accept between a candidate segment end and the
    // head's recorded position.
    private static final int MAX_XZ_TOLERANCE = 2;

    private PhysicalTrackTipLocator() {
    }

    /**
     * Returns the end block of the most recently placed {@link ConnectedSegment} owned by
     * this head near {@code fallback}.
     */
    public static BlockPos findTipOrFallback(ServerLevel level, TrackExpansionHead head, BlockPos fallback) {
        if (!head.hasPlacedAnySegments()) {
            return fallback;
        }
        UUID headId = head.getHeadId();
        if (headId == null) {
            return fallback;
        }

        ConnectedSegment tip = findBestTipSegment(level, headId, fallback);
        return tip != null ? tip.end : fallback;
    }

    private static ConnectedSegment findBestTipSegment(ServerLevel level, UUID headId, BlockPos near) {
        int centerChunkX = near.getX() >> 4;
        int centerChunkZ = near.getZ() >> 4;
        ConnectedSegment best = null;
        int bestXzDist = Integer.MAX_VALUE;
        int bestYDist = Integer.MAX_VALUE;

        for (int dx = -CHUNK_RADIUS; dx <= CHUNK_RADIUS; dx++) {
            for (int dz = -CHUNK_RADIUS; dz <= CHUNK_RADIUS; dz++) {
                ChunkPos chunk = new ChunkPos(centerChunkX + dx, centerChunkZ + dz);
                List<ConnectedSegment> segments = ConnectedBoundaryTracker.getSegmentsInChunk(level, chunk);
                for (ConnectedSegment seg : segments) {
                    if (seg.headId == null || !seg.headId.equals(headId)) {
                        continue;
                    }
                    int xz = xzManhattan(seg.end, near);
                    if (xz > MAX_XZ_TOLERANCE) {
                        continue;
                    }
                    int yDist = Math.abs(seg.end.getY() - near.getY());
                    // Prefer the segment whose end matches in XZ; tiebreak on Y proximity so
                    // we don't pick an older segment whose end happens to share the XZ column
                    // with the current tip at a stale Y.
                    if (xz < bestXzDist || (xz == bestXzDist && yDist < bestYDist)) {
                        best = seg;
                        bestXzDist = xz;
                        bestYDist = yDist;
                    }
                }
            }
        }
        return best;
    }

    private static int xzManhattan(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ());
    }
}
