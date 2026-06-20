package com.vodmordia.railwaysuntold.worldgen.tracking;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.worldgen.tracking.BoundarySegmentData.ConnectedSegment;
import com.vodmordia.railwaysuntold.worldgen.tracking.BoundarySegmentData.ConnectionType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.vodmordia.railwaysuntold.worldgen.tracking.TrackingNbtKeys.*;

/**
 * Persists connected boundary segments across world saves/loads.
 */
public class ConnectedBoundarySavedData extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DATA_NAME = "railwaysuntold_connected_boundaries";

    // Stores segments grouped by chunk (keyed by ChunkPos.toLong())
    private final Map<Long, List<ConnectedSegment>> chunkSegments = new ConcurrentHashMap<>();

    public ConnectedBoundarySavedData() {
    }

    /**
     * Gets the ConnectedBoundarySavedData instance for a level.
     */
    public static ConnectedBoundarySavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
            ConnectedBoundarySavedData::load,
            ConnectedBoundarySavedData::new,
            DATA_NAME
        );
    }

    /**
     * Adds a segment to the saved data.
     */
    public void addSegment(long chunkKey, ConnectedSegment segment) {
        chunkSegments.computeIfAbsent(chunkKey, k -> Collections.synchronizedList(new ArrayList<>())).add(segment);
        setDirty();
    }

    /**
     * Gets all segments grouped by chunk.
     */
    public Map<Long, List<ConnectedSegment>> getAllSegments() {
        return Collections.unmodifiableMap(chunkSegments);
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag chunksList = new ListTag();

        for (Map.Entry<Long, List<ConnectedSegment>> entry : chunkSegments.entrySet()) {
            CompoundTag chunkTag = new CompoundTag();
            chunkTag.putLong(CHUNK_KEY, entry.getKey());

            ListTag segmentsList = new ListTag();
            for (ConnectedSegment segment : entry.getValue()) {
                CompoundTag segmentTag = new CompoundTag();
                segmentTag.putLong(START, segment.start.asLong());
                segmentTag.putLong(END, segment.end.asLong());
                segmentTag.putString(TYPE, segment.type.name());

                // Save curve positions if available
                if (segment.curvePositions != null && !segment.curvePositions.isEmpty()) {
                    long[] positionsArray = new long[segment.curvePositions.size()];
                    for (int idx = 0; idx < segment.curvePositions.size(); idx++) {
                        positionsArray[idx] = segment.curvePositions.get(idx).asLong();
                    }
                    segmentTag.putLongArray(CURVE_POSITIONS, positionsArray);
                }

                // Save head ID if available
                if (segment.headId != null) {
                    segmentTag.putUUID(HEAD_ID, segment.headId);
                }

                segmentsList.add(segmentTag);
            }
            chunkTag.put(SEGMENTS, segmentsList);
            chunksList.add(chunkTag);
        }

        tag.put(CHUNKS, chunksList);
        return tag;
    }

    /**
     * Loads data from NBT.
     */
    public static ConnectedBoundarySavedData load(CompoundTag tag) {
        ConnectedBoundarySavedData savedData = new ConnectedBoundarySavedData();

        if (tag.contains(CHUNKS, Tag.TAG_LIST)) {
            ListTag chunksList = tag.getList(CHUNKS, Tag.TAG_COMPOUND);
            for (int i = 0; i < chunksList.size(); i++) {
                CompoundTag chunkTag = chunksList.getCompound(i);
                long chunkKey = chunkTag.getLong(CHUNK_KEY);

                List<ConnectedSegment> segments = loadSegments(chunkTag);
                savedData.chunkSegments.put(chunkKey, Collections.synchronizedList(segments));
            }
        }

        return savedData;
    }

    private static List<ConnectedSegment> loadSegments(CompoundTag chunkTag) {
        List<ConnectedSegment> segments = new ArrayList<>();
        if (!chunkTag.contains(SEGMENTS, Tag.TAG_LIST)) {
            return segments;
        }

        ListTag segmentsList = chunkTag.getList(SEGMENTS, Tag.TAG_COMPOUND);
        for (int j = 0; j < segmentsList.size(); j++) {
            segments.add(loadSegment(segmentsList.getCompound(j)));
        }
        return segments;
    }

    private static ConnectedSegment loadSegment(CompoundTag segmentTag) {
        BlockPos start = BlockPos.of(segmentTag.getLong(START));
        BlockPos end = BlockPos.of(segmentTag.getLong(END));
        ConnectionType type = loadConnectionType(segmentTag);
        List<BlockPos> curvePositions = loadCurvePositions(segmentTag);
        UUID headId = segmentTag.hasUUID(HEAD_ID) ? segmentTag.getUUID(HEAD_ID) : null;

        return new ConnectedSegment(start, end, type, curvePositions, headId);
    }

    private static ConnectionType loadConnectionType(CompoundTag segmentTag) {
        try {
            return ConnectionType.valueOf(segmentTag.getString(TYPE));
        } catch (IllegalArgumentException e) {
            LOGGER.error("[BOUNDARY-SAVED-DATA] Unknown ConnectionType '{}', defaulting to BEZIER",
                    segmentTag.getString(TYPE), e);
            return ConnectionType.BEZIER;
        }
    }

    private static List<BlockPos> loadCurvePositions(CompoundTag segmentTag) {
        if (!segmentTag.contains(CURVE_POSITIONS)) {
            return null;
        }
        long[] positionsArray = segmentTag.getLongArray(CURVE_POSITIONS);
        List<BlockPos> curvePositions = new ArrayList<>(positionsArray.length);
        for (long posLong : positionsArray) {
            curvePositions.add(BlockPos.of(posLong));
        }
        return curvePositions;
    }
}
