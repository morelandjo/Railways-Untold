package com.vodmordia.railwaysuntold.worldgen.terrain.clearing.util;

import com.vodmordia.railwaysuntold.util.block.ChunkSafeBlockAccess;
import com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SnowyDirtBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Low-level batched world writes for the clearing pipeline
 */
public final class BlockBatchRemover {

    private BlockBatchRemover() {}

    public static void batchRemoveBlocks(ServerLevel level, List<BlockPos> positions) {
        if (positions.isEmpty()) {
            return;
        }

        Map<Long, List<BlockPos>> positionsByChunk = new HashMap<>();
        for (BlockPos pos : positions) {
            long chunkKey = ChunkCoordinateUtil.getChunkKey(pos);
            positionsByChunk.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(pos);
        }

        BlockState air = Blocks.AIR.defaultBlockState();
        for (Map.Entry<Long, List<BlockPos>> entry : positionsByChunk.entrySet()) {
            int chunkX = ChunkCoordinateUtil.getChunkXFromKey(entry.getKey());
            int chunkZ = ChunkCoordinateUtil.getChunkZFromKey(entry.getKey());

            LevelChunk chunk = ChunkCoordinateUtil.getLoadedChunk(level, chunkX, chunkZ);
            if (chunk == null) {
                continue;
            }

            for (BlockPos pos : entry.getValue()) {
                if (chunk.getBlockState(pos).getBlock() instanceof SnowLayerBlock) {
                    updateSnowyDirtBelow(level, pos);
                }
                ChunkSafeBlockAccess.clearContainerContents(level, pos);
                ChunkSafeBlockAccess.setBlockStateWithChunk(level, chunk, pos, air, true);
            }
        }

        convertExposedDirtToGrass(level, positions);
    }

    /**
     * Clears SNOWY=true on dirt-family blocks whose overlying snow layer has been removed,
     * so grass/podzol/mycelium don't keep their snowy appearance once exposed.
     */
    static void updateSnowyDirtBelow(ServerLevel level, BlockPos snowPos) {
        BlockPos belowSnow = snowPos.below();
        LevelChunk belowChunk = ChunkCoordinateUtil.getLoadedChunk(level, belowSnow);
        if (belowChunk == null) {
            return;
        }

        BlockState belowState = belowChunk.getBlockState(belowSnow);
        if (!belowState.hasProperty(SnowyDirtBlock.SNOWY) || !belowState.getValue(SnowyDirtBlock.SNOWY)) {
            return;
        }

        BlockState nonSnowyState = belowState.setValue(SnowyDirtBlock.SNOWY, false);
        belowChunk.setBlockState(belowSnow, nonSnowyState, false);
        belowChunk.setUnsaved(true);
        level.sendBlockUpdated(belowSnow, belowState, nonSnowyState, 3);
    }

    /**
     * Converts dirt directly beneath each cleared position into grass so newly exposed ground
     * blends with the surrounding biome surface.
     */
    static void convertExposedDirtToGrass(ServerLevel level, List<BlockPos> clearedPositions) {
        Map<Long, List<BlockPos>> positionsByChunk = new HashMap<>();

        for (BlockPos pos : clearedPositions) {
            BlockPos belowPos = pos.below();
            long chunkKey = ChunkCoordinateUtil.getChunkKey(belowPos);
            positionsByChunk.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(belowPos);
        }

        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        for (Map.Entry<Long, List<BlockPos>> entry : positionsByChunk.entrySet()) {
            int chunkX = ChunkCoordinateUtil.getChunkXFromKey(entry.getKey());
            int chunkZ = ChunkCoordinateUtil.getChunkZFromKey(entry.getKey());

            LevelChunk chunk = ChunkCoordinateUtil.getLoadedChunk(level, chunkX, chunkZ);
            if (chunk == null) {
                continue;
            }

            for (BlockPos belowPos : entry.getValue()) {
                BlockState belowState = chunk.getBlockState(belowPos);
                if (belowState.is(Blocks.DIRT)) {
                    ChunkSafeBlockAccess.setBlockStateWithChunk(level, chunk, belowPos, grass, true);
                }
            }
        }
    }
}
