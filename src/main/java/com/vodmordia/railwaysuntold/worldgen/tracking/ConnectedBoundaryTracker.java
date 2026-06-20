package com.vodmordia.railwaysuntold.worldgen.tracking;

import com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil;
import com.vodmordia.railwaysuntold.worldgen.tracking.BoundarySegmentData.ConnectedSegment;
import com.vodmordia.railwaysuntold.worldgen.tracking.BoundarySegmentData.ConnectionType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Tracks chunk boundaries that have been successfully connected via beziers.
 */
public class ConnectedBoundaryTracker {

    private static final Map<ResourceKey<Level>, Map<Long, List<ConnectedSegment>>> dimensionSegments = new ConcurrentHashMap<>();

    private static Map<Long, List<ConnectedSegment>> getOrCreateDimensionMap(ResourceKey<Level> dimensionKey) {
        return dimensionSegments.computeIfAbsent(dimensionKey, k -> new ConcurrentHashMap<>());
    }

    public static void clearAll() {
        dimensionSegments.clear();
    }

    /**
     * Initializes the tracker from saved data on world load.
     *
     * @param level The server level to initialize tracking for
     */
    public static void initFromSavedData(ServerLevel level) {
        ResourceKey<Level> dimensionKey = level.dimension();
        Map<Long, List<ConnectedSegment>> dimensionMap = getOrCreateDimensionMap(dimensionKey);
        dimensionMap.clear();

        ConnectedBoundarySavedData savedData = ConnectedBoundarySavedData.get(level);
        Map<Long, List<ConnectedSegment>> savedSegments = savedData.getAllSegments();

        for (Map.Entry<Long, List<ConnectedSegment>> entry : savedSegments.entrySet()) {
            dimensionMap.put(entry.getKey(), Collections.synchronizedList(new ArrayList<>(entry.getValue())));
        }
    }

    /**
     * Marks a segment (range of positions) as connected via bezier.
     *
     * @param level The server level where the segment exists
     * @param start Starting position of the connection
     * @param end   Ending position of the connection
     * @param type  Type of connection (BEZIER)
     */
    public static void markSegmentAsConnected(ServerLevel level, BlockPos start, BlockPos end, ConnectionType type) {
        markSegmentAsConnected(level, start, end, type, null, null);
    }

    /**
     * Marks a segment (range of positions) as connected via bezier, with curve geometry and head ownership.
     *
     * @param level          The server level where the segment exists
     * @param start          Starting position of the connection
     * @param end            Ending position of the connection
     * @param type           Type of connection (BEZIER)
     * @param curvePositions Actual curve sample positions for accurate intersection detection (may be null)
     * @param headId         UUID of the head that placed this segment (may be null for legacy data)
     */
    public static void markSegmentAsConnected(ServerLevel level, BlockPos start, BlockPos end, ConnectionType type, List<BlockPos> curvePositions, UUID headId) {
        ConnectedSegment segment = new ConnectedSegment(start, end, type, curvePositions, headId);
        ResourceKey<Level> dimensionKey = level.dimension();
        Map<Long, List<ConnectedSegment>> dimensionMap = getOrCreateDimensionMap(dimensionKey);

        Set<ChunkPos> affectedChunks;
        if (curvePositions != null && !curvePositions.isEmpty()) {
            affectedChunks = new HashSet<>();
            for (BlockPos pos : curvePositions) {
                affectedChunks.add(new ChunkPos(pos));
            }
        } else {
            affectedChunks = getChunksSpannedBySegment(start, end);
        }

        for (ChunkPos chunk : affectedChunks) {
            long chunkKey = chunk.toLong();
            dimensionMap.computeIfAbsent(chunkKey, k -> Collections.synchronizedList(new ArrayList<>())).add(segment);

            ConnectedBoundarySavedData savedData = ConnectedBoundarySavedData.get(level);
            savedData.addSegment(chunkKey, segment);
        }
    }

    /**
     * Gets all tracked segments for a specific chunk in a specific dimension.
     *
     * @param level    The server level to query
     * @param chunkPos The chunk position to query
     * @return Unmodifiable list of connected segments in the chunk, or empty list if none
     */
    public static List<ConnectedSegment> getSegmentsInChunk(ServerLevel level, ChunkPos chunkPos) {
        ResourceKey<Level> dimensionKey = level.dimension();
        Map<Long, List<ConnectedSegment>> dimensionMap = dimensionSegments.get(dimensionKey);
        if (dimensionMap == null) {
            return Collections.emptyList();
        }
        List<ConnectedSegment> segments = dimensionMap.get(chunkPos.toLong());
        return segments != null ? Collections.unmodifiableList(segments) : Collections.emptyList();
    }

    public static Set<ChunkPos> getChunksSpannedBySegment(BlockPos start, BlockPos end) {
        return ChunkCoordinateUtil.getChunksInBoundingBox(start, end);
    }

    /**
     * Returns true if any tracked segment's AABB overlaps the box {@code [min, max]}, skipping
     * segments for which {@code exempt} tests true. Only the chunks the box spans are scanned.
     */
    public static boolean hasSegmentIntersecting(ServerLevel level, BlockPos min, BlockPos max,
                                                 Predicate<ConnectedSegment> exempt) {
        int minX = min.getX(), maxX = max.getX();
        int minY = min.getY(), maxY = max.getY();
        int minZ = min.getZ(), maxZ = max.getZ();
        for (ChunkPos chunk : getChunksSpannedBySegment(min, max)) {
            for (ConnectedSegment seg : getSegmentsInChunk(level, chunk)) {
                if (!seg.aabb.overlaps(minX, maxX, minY, maxY, minZ, maxZ)) {
                    continue;
                }
                if (exempt != null && exempt.test(seg)) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Registers a segment as connected after track placement succeeds, with head ownership.
     *
     * @param level          The server level
     * @param start          Starting position of the segment
     * @param end            Ending position of the segment
     * @param type           Type of connection
     * @param curvePositions Curve positions for accurate intersection detection (may be null)
     * @param headId         UUID of the head that placed this segment (may be null)
     */
    public static void confirmSegment(ServerLevel level, BlockPos start, BlockPos end,
                                       ConnectionType type, List<BlockPos> curvePositions, UUID headId) {
        markSegmentAsConnected(level, start, end, type, curvePositions, headId);
    }
}
