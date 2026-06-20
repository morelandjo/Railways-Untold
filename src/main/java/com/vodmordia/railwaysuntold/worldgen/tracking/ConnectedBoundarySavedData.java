package com.vodmordia.railwaysuntold.worldgen.tracking;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists connected boundary segments across world saves/loads.
 */
public class ConnectedBoundarySavedData extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DATA_NAME = "railwaysuntold_connected_boundaries";

    // Stores segments grouped by chunk (keyed by ChunkPos.toLong())
    private final Map<Long, List<BoundarySegmentData.ConnectedSegment>> chunkSegments = new ConcurrentHashMap<>();

    public ConnectedBoundarySavedData() {
    }

    /**
     * Gets the ConnectedBoundarySavedData instance for a level.
     */
    public static ConnectedBoundarySavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new Factory<>(
                        ConnectedBoundarySavedData::new,
                        ConnectedBoundarySavedData::load
                ),
                DATA_NAME
        );
    }

    /**
     * Adds a segment to the saved data.
     */
    public void addSegment(long chunkKey, BoundarySegmentData.ConnectedSegment segment) {
        chunkSegments.computeIfAbsent(chunkKey, k -> Collections.synchronizedList(new ArrayList<>())).add(segment);
        setDirty();
    }

    /**
     * Gets all segments grouped by chunk.
     */
    public Map<Long, List<BoundarySegmentData.ConnectedSegment>> getAllSegments() {
        return Collections.unmodifiableMap(chunkSegments);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag chunksList = new ListTag();

        for (Map.Entry<Long, List<BoundarySegmentData.ConnectedSegment>> entry : chunkSegments.entrySet()) {
            CompoundTag chunkTag = new CompoundTag();
            chunkTag.putLong(TrackingNbtKeys.CHUNK_KEY, entry.getKey());

            ListTag segmentsList = new ListTag();
            for (BoundarySegmentData.ConnectedSegment segment : entry.getValue()) {
                CompoundTag segmentTag = new CompoundTag();
                segmentTag.putLong(TrackingNbtKeys.START, segment.start.asLong());
                segmentTag.putLong(TrackingNbtKeys.END, segment.end.asLong());
                segmentTag.putString(TrackingNbtKeys.TYPE, segment.type.name());

                // Save curve positions if available (critical for accurate collision detection)
                if (segment.curvePositions != null && !segment.curvePositions.isEmpty()) {
                    long[] positionsArray = new long[segment.curvePositions.size()];
                    for (int idx = 0; idx < segment.curvePositions.size(); idx++) {
                        positionsArray[idx] = segment.curvePositions.get(idx).asLong();
                    }
                    segmentTag.putLongArray(TrackingNbtKeys.CURVE_POSITIONS, positionsArray);
                }

                // Save headId if available (for ownership tracking)
                if (segment.headId != null) {
                    segmentTag.putUUID(TrackingNbtKeys.HEAD_ID, segment.headId);
                }

                segmentsList.add(segmentTag);
            }
            chunkTag.put(TrackingNbtKeys.SEGMENTS, segmentsList);
            chunksList.add(chunkTag);
        }

        tag.put(TrackingNbtKeys.CHUNKS, chunksList);
        return tag;
    }

    /**
     * Loads data from NBT.
     */
    public static ConnectedBoundarySavedData load(CompoundTag tag, HolderLookup.Provider provider) {
        ConnectedBoundarySavedData savedData = new ConnectedBoundarySavedData();

        if (tag.contains(TrackingNbtKeys.CHUNKS, Tag.TAG_LIST)) {
            ListTag chunksList = tag.getList(TrackingNbtKeys.CHUNKS, Tag.TAG_COMPOUND);
            for (int i = 0; i < chunksList.size(); i++) {
                CompoundTag chunkTag = chunksList.getCompound(i);
                long chunkKey = chunkTag.getLong(TrackingNbtKeys.CHUNK_KEY);

                List<BoundarySegmentData.ConnectedSegment> segments = loadSegments(chunkTag);
                savedData.chunkSegments.put(chunkKey, Collections.synchronizedList(segments));
            }
        }

        return savedData;
    }

    private static List<BoundarySegmentData.ConnectedSegment> loadSegments(CompoundTag chunkTag) {
        List<BoundarySegmentData.ConnectedSegment> segments = new ArrayList<>();
        if (!chunkTag.contains(TrackingNbtKeys.SEGMENTS, Tag.TAG_LIST)) {
            return segments;
        }

        ListTag segmentsList = chunkTag.getList(TrackingNbtKeys.SEGMENTS, Tag.TAG_COMPOUND);
        for (int j = 0; j < segmentsList.size(); j++) {
            segments.add(loadSegment(segmentsList.getCompound(j)));
        }
        return segments;
    }

    private static BoundarySegmentData.ConnectedSegment loadSegment(CompoundTag segmentTag) {
        BlockPos start = BlockPos.of(segmentTag.getLong(TrackingNbtKeys.START));
        BlockPos end = BlockPos.of(segmentTag.getLong(TrackingNbtKeys.END));
        BoundarySegmentData.ConnectionType type = loadConnectionType(segmentTag);
        List<BlockPos> curvePositions = loadCurvePositions(segmentTag);
        UUID headId = segmentTag.hasUUID(TrackingNbtKeys.HEAD_ID) ? segmentTag.getUUID(TrackingNbtKeys.HEAD_ID) : null;

        return new BoundarySegmentData.ConnectedSegment(start, end, type, curvePositions, headId);
    }

    private static BoundarySegmentData.ConnectionType loadConnectionType(CompoundTag segmentTag) {
        try {
            return BoundarySegmentData.ConnectionType.valueOf(segmentTag.getString(TrackingNbtKeys.TYPE));
        } catch (IllegalArgumentException e) {
            LOGGER.error("[BOUNDARY-SAVED-DATA] Unknown ConnectionType '{}', defaulting to BEZIER",
                    segmentTag.getString(TrackingNbtKeys.TYPE), e);
            return BoundarySegmentData.ConnectionType.BEZIER;
        }
    }

    private static List<BlockPos> loadCurvePositions(CompoundTag segmentTag) {
        if (!segmentTag.contains(TrackingNbtKeys.CURVE_POSITIONS)) {
            return null;
        }
        long[] positionsArray = segmentTag.getLongArray(TrackingNbtKeys.CURVE_POSITIONS);
        List<BlockPos> curvePositions = new ArrayList<>(positionsArray.length);
        for (long posLong : positionsArray) {
            curvePositions.add(BlockPos.of(posLong));
        }
        return curvePositions;
    }
}
