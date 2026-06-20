package com.vodmordia.railwaysuntold.worldgen.placement.support;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldJsonConfig;
import com.vodmordia.railwaysuntold.datapack.BiomeSettingsLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Shared constants for ground support and track placement strategies.
 * Block types are resolved from biome settings data packs.
 */
public class SupportConstants {

    /**
     * Gets the block state used for tunnel facade (global default).
     */
    public static BlockState getTunnelFacadeBlock() {
        return BiomeSettingsLoader.INSTANCE.resolveGlobal().getTunnelFacadeBlock();
    }

    /**
     * Gets the tunnel facade block for a specific position (biome-aware).
     */
    public static BlockState getTunnelFacadeBlock(ServerLevel level, BlockPos pos) {
        return BiomeSettingsLoader.INSTANCE.resolve(level.getBiome(pos)).getTunnelFacadeBlock();
    }

    /**
     * Gets the tunnel lighting block for a specific position (biome-aware).
     */
    public static BlockState getTunnelLightingBlock(ServerLevel level, BlockPos pos) {
        return BiomeSettingsLoader.INSTANCE.resolve(level.getBiome(pos)).getLightingBlock();
    }

    /**
     * Gets the terrain fill block for a specific position (biome-aware).
     */
    public static BlockState getTerrainFillBlock(ServerLevel level, BlockPos pos) {
        return BiomeSettingsLoader.INSTANCE.resolve(level.getBiome(pos)).getTerrainFillBlock();
    }

    /**
     * Gets the terrain fill base block for a specific position (biome-aware).
     */
    public static BlockState getTerrainFillBaseBlock(ServerLevel level, BlockPos pos) {
        return BiomeSettingsLoader.INSTANCE.resolve(level.getBiome(pos)).getTerrainFillBaseBlock();
    }

    /**
     * Gets the station foundation block for a specific position (biome-aware).
     */
    public static BlockState getStationFoundationBlock(ServerLevel level, BlockPos pos) {
        return BiomeSettingsLoader.INSTANCE.resolve(level.getBiome(pos)).getStationFoundationBlock();
    }

    /**
     * Gets the tunnel lighting attachment type for a specific position (biome-aware).
     */
    public static int getTunnelLightingAttachment(ServerLevel level, BlockPos pos) {
        return BiomeSettingsLoader.INSTANCE.resolve(level.getBiome(pos)).getLightingAttachment();
    }

    /**
     * Standard tunnel length in blocks.
     */
    public static final int TUNNEL_LENGTH = 16;

    /**
     * Minimum tunnel length when shortened due to ravine detection.
     */
    public static final int MIN_TUNNEL_LENGTH = 8;

    /**
     * Gets the minimum height above ground to use bridge decking instead of thin pillars.
     */
    public static int getBridgeElevationThreshold() {
        return RailwaysUntoldJsonConfig.getBridgeElevationThreshold();
    }

    /**
     * Returns true if the block is the global facade block or any biome override facade block.
     */
    public static boolean isAnyFacadeBlock(Block block) {
        return block == getTunnelFacadeBlock().getBlock()
                || BiomeSettingsLoader.INSTANCE.isOverrideBlock(block);
    }

    private SupportConstants() {
    }
}
