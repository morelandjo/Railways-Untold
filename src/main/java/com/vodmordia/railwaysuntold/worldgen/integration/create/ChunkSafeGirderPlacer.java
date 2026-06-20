package com.vodmordia.railwaysuntold.worldgen.integration.create;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.GirderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Set;

/**
 * Chunk-safe version of Create's TrackPaver for placing girder blocks under tracks.
 * Based on Create's TrackPaver.paveStraight() method.
 */
public class ChunkSafeGirderPlacer {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Places girder blocks under a straight track section.
     *
     * @param level     The server level
     * @param startPos  Starting position (should be trackPos.below())
     * @param direction Direction vector of the track
     * @param extent    How many blocks to pave (typically 1 for a single track piece)
     * @param block     The girder block to place
     * @param visited   Set to track already-placed positions
     */
    public static void paveStraightChunkSafe(ServerLevel level, BlockPos startPos, Vec3 direction,
                                             int extent, Block block, Set<BlockPos> visited) {
        BlockState state = createGirderBlockState(block, direction);
        Set<BlockPos> toPlaceOn = calculateGirderPositions(startPos, direction, extent);
        placeGirdersAtPositions(level, toPlaceOn, state, visited);
    }

    private static BlockState createGirderBlockState(Block block, Vec3 direction) {
        BlockState state = block.defaultBlockState();

        if (state.hasProperty(SlabBlock.TYPE)) {
            state = state.setValue(SlabBlock.TYPE, SlabType.DOUBLE);
        }

        try {
            Class<?> girderBlockClass = Class.forName("com.simibubi.create.content.decoration.girder.GirderBlock");
            if (girderBlockClass.isInstance(state.getBlock())) {
                Axis axis = Math.abs(direction.x) > Math.abs(direction.z) ? Axis.X : Axis.Z;
                state = GirderUtil.createGirderState(block, axis, false, false);
            }
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[GIRDER] Failed to create girder block state: {}", e.getMessage());
        }

        return state;
    }

    private static Set<BlockPos> calculateGirderPositions(BlockPos startPos, Vec3 direction, int extent) {
        Set<BlockPos> toPlaceOn = new HashSet<>();
        Vec3 start = getCenterOf(startPos);
        Vec3 mainNormal = direction.cross(new Vec3(0, 1, 0));
        Vec3 normalizedNormal = mainNormal.normalize();
        Vec3 normalizedDirection = direction.normalize();

        float diagFiller = 0.45f;
        for (int i = 0; i < extent; i++) {
            Vec3 offset = direction.scale(i);
            Vec3 mainPos = start.add(offset.x, offset.y, offset.z);

            toPlaceOn.add(BlockPos.containing(mainPos.add(mainNormal)));
            toPlaceOn.add(BlockPos.containing(mainPos.subtract(mainNormal)));

            addDiagonalFillerPositions(toPlaceOn, mainPos, normalizedNormal, normalizedDirection, diagFiller, i, extent);
        }

        return toPlaceOn;
    }

    private static void addDiagonalFillerPositions(Set<BlockPos> toPlaceOn, Vec3 mainPos, Vec3 normalizedNormal,
                                                    Vec3 normalizedDirection, float diagFiller, int index, int extent) {
        if (index < extent - 1) {
            toPlaceOn.add(BlockPos.containing(mainPos.add(normalizedNormal.scale(diagFiller))
                    .add(normalizedDirection.scale(diagFiller))));
            toPlaceOn.add(BlockPos.containing(mainPos.add(normalizedNormal.scale(-diagFiller))
                    .add(normalizedDirection.scale(diagFiller))));
        }
        if (index > 0) {
            toPlaceOn.add(BlockPos.containing(mainPos.add(normalizedNormal.scale(diagFiller))
                    .add(normalizedDirection.scale(-diagFiller))));
            toPlaceOn.add(BlockPos.containing(mainPos.add(normalizedNormal.scale(-diagFiller))
                    .add(normalizedDirection.scale(-diagFiller))));
        }
    }

    private static void placeGirdersAtPositions(ServerLevel level, Set<BlockPos> toPlaceOn,
                                                 BlockState state, Set<BlockPos> visited) {
        for (BlockPos pos : toPlaceOn) {
            if (!visited.add(pos)) {
                continue;
            }
            placeBlockIfFreeChunkSafe(level, pos, state);
        }
    }

    /**
     * Places a block at the given position if it's free and the chunk is loaded.
     *
     * @param level The server level
     * @param pos   Position to place block at
     * @param state Block state to place
     * @return true if block was placed, false if skipped
     */
    private static boolean placeBlockIfFreeChunkSafe(ServerLevel level, BlockPos pos, BlockState state) {
        // Verify chunk is loaded
        LevelChunk chunk = ChunkCoordinateUtil.getLoadedChunk(level, pos);
        if (chunk == null) {
            return false;
        }

        BlockState stateAtPos = chunk.getBlockState(pos);
        if (stateAtPos.getBlock() != state.getBlock() && stateAtPos.canBeReplaced()) {
            BlockState finalState = GirderUtil.applyWaterlogging(level, state, pos);

            chunk.setBlockState(pos, finalState, false);
            chunk.setUnsaved(true);

            // Sync to clients
            level.sendBlockUpdated(pos, stateAtPos, finalState, 3);

            return true;
        }

        return false;
    }

    /**
     * Helper method to get the center of a block position as Vec3.
     * Equivalent to VecHelper.getCenterOf() from Create.
     */
    private static Vec3 getCenterOf(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }
}
