package com.vodmordia.railwaysuntold.datapack;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Flattened, fully-resolved biome settings with all values guaranteed non-null.
 * Produced by {@link BiomeSettingsLoader#resolve} after layering matching definitions.
 */
public class ResolvedBiomeSettings {

    private final BlockState tunnelFacadeBlock;
    private final BlockState terrainFillBlock;
    private final BlockState terrainFillBaseBlock;
    private final BlockState stationFoundationBlock;
    private final BlockState lightingBlock;
    private final int lightingAttachment;
    private final String trackMaterial;
    private final ResourceLocation bridgeDeckingNbt;
    private final ResourceLocation bridgeEndNbt;
    private final int bridgeHalfWidth;
    private final ResourceLocation bridgeAbutmentNbt;
    private final ResourceLocation bridgePierNbt;
    private final int bridgePillarDeckRow;
    private final ResourceLocation bridgeRailingNbt;

    public ResolvedBiomeSettings(BlockState tunnelFacadeBlock,
                                  BlockState terrainFillBlock,
                                  BlockState terrainFillBaseBlock,
                                  BlockState stationFoundationBlock,
                                  BlockState lightingBlock,
                                  int lightingAttachment,
                                  String trackMaterial,
                                  ResourceLocation bridgeDeckingNbt,
                                  ResourceLocation bridgeEndNbt,
                                  int bridgeHalfWidth,
                                  ResourceLocation bridgeAbutmentNbt,
                                  ResourceLocation bridgePierNbt,
                                  int bridgePillarDeckRow,
                                  ResourceLocation bridgeRailingNbt) {
        this.tunnelFacadeBlock = tunnelFacadeBlock;
        this.terrainFillBlock = terrainFillBlock;
        this.terrainFillBaseBlock = terrainFillBaseBlock;
        this.stationFoundationBlock = stationFoundationBlock;
        this.lightingBlock = lightingBlock;
        this.lightingAttachment = lightingAttachment;
        this.trackMaterial = trackMaterial;
        this.bridgeDeckingNbt = bridgeDeckingNbt;
        this.bridgeEndNbt = bridgeEndNbt;
        this.bridgeHalfWidth = bridgeHalfWidth;
        this.bridgeAbutmentNbt = bridgeAbutmentNbt;
        this.bridgePierNbt = bridgePierNbt;
        this.bridgePillarDeckRow = bridgePillarDeckRow;
        this.bridgeRailingNbt = bridgeRailingNbt;
    }

    public BlockState getTunnelFacadeBlock() { return tunnelFacadeBlock; }
    public BlockState getTerrainFillBlock() { return terrainFillBlock; }
    public BlockState getTerrainFillBaseBlock() { return terrainFillBaseBlock; }
    public BlockState getStationFoundationBlock() { return stationFoundationBlock; }
    public BlockState getLightingBlock() { return lightingBlock; }
    public int getLightingAttachment() { return lightingAttachment; }
    public String getTrackMaterial() { return trackMaterial; }
    public ResourceLocation getBridgeDeckingNbt() { return bridgeDeckingNbt; }
    public ResourceLocation getBridgeEndNbt() { return bridgeEndNbt; }
    public int getBridgeHalfWidth() { return bridgeHalfWidth; }
    public ResourceLocation getBridgeAbutmentNbt() { return bridgeAbutmentNbt; }
    public ResourceLocation getBridgePierNbt() { return bridgePierNbt; }
    public int getBridgePillarDeckRow() { return bridgePillarDeckRow; }
    public ResourceLocation getBridgeRailingNbt() { return bridgeRailingNbt; }
}
