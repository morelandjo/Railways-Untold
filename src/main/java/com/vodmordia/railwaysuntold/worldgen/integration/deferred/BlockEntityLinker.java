package com.vodmordia.railwaysuntold.worldgen.integration.deferred;

import com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.CreateTrackUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Handles block entity operations for track connections.
 */
public class BlockEntityLinker {

    /**
     * Queues a bezier connection between two track block entities for deferred processing.
     *
     * @param level     The server level
     * @param firstPos  Position of first track
     * @param secondPos Position of second track
     * @param curve     The bezier connection curve
     * @throws BlockEntitiesNotReadyException If block entities are not ready (recoverable - retry later)
     */
    public static void linkTrackBlockEntitiesWithConnection(ServerLevel level, BlockPos firstPos,
                                                            BlockPos secondPos, Object curve) {
        var firstChunk = ChunkCoordinateUtil.getLoadedChunk(level, firstPos);
        if (firstChunk == null) {
            throw BlockEntitiesNotReadyException.atPosition("first chunk at " + firstPos);
        }

        var secondChunk = ChunkCoordinateUtil.getLoadedChunk(level, secondPos);
        if (secondChunk == null) {
            throw BlockEntitiesNotReadyException.atPosition("second chunk at " + secondPos);
        }

        var firstBE = firstChunk.getBlockEntity(firstPos);
        var secondBE = secondChunk.getBlockEntity(secondPos);

        if (firstBE == null || secondBE == null) {
            throw BlockEntitiesNotReadyException.atPosition(
                    String.format("firstBE=%s at %s, secondBE=%s at %s",
                            firstBE != null, firstPos, secondBE != null, secondPos));
        }

        CreateTrackUtil.validateTrackBlockEntities(firstBE, secondBE, "BLOCK-ENTITY-LINKER");


        // all chunks are loaded and operations are safe.
        DeferredConnectionManager.queueConnection(level, firstPos, secondPos, curve);
    }
}
