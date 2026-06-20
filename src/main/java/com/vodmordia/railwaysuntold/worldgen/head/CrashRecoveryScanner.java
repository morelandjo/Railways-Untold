package com.vodmordia.railwaysuntold.worldgen.head;

import com.vodmordia.railwaysuntold.util.block.ChunkSafeBlockAccess;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.CreateTrackUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Scans forward from a restored head position to find where track actually ends
 * in the world. This recovers head state after a crash where track was placed
 * but the head position was not saved.
 */
public class CrashRecoveryScanner {

    /**
     * Maximum number of blocks to scan forward. Limits the cost of scanning
     * and prevents runaway scans into unrelated track.
     */
    private static final int MAX_SCAN_DISTANCE = 500;

    /**
     * Scans forward from the head's current position along its direction,
     * looking for existing track blocks that were placed after the last save.
     * If found, advances the head position to the end of the existing track.
     *
     * @param head  The expansion head to scan for
     * @param level The server level to check blocks in
     * @return The number of blocks the head was advanced
     */
    public static int scanAndAdvance(TrackExpansionHead head, ServerLevel level) {
        if (head.isComplete()) {
            return 0;
        }

        BlockPos startPos = head.getPosition();
        Direction direction = head.getDirection();
        int advanced = 0;

        for (int i = 1; i <= MAX_SCAN_DISTANCE; i++) {
            BlockPos checkPos = startPos.relative(direction, i);

            BlockState state = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, checkPos);
            if (state == null) {
                // Chunk not loaded - stop scanning, don't advance past unloaded chunks
                break;
            }

            if (!CreateTrackUtil.isTrackBlock(state)) {
                break;
            }

            advanced = i;
        }

        if (advanced > 0) {
            BlockPos newPos = startPos.relative(direction, advanced);
            head.setPosition(newPos);
        }

        return advanced;
    }
}
