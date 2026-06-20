package com.vodmordia.railwaysuntold.config;

/**
 * Configuration for tunnel construction and decoration.
 * Block types (facade, pillar, lighting) are now configured via biome settings data packs.
 */
public interface ITunnelConfig {

    int getTunnelTorchHeight();
    boolean isTunnelFacadeEnabled();
    boolean areTunnelTorchesEnabled();
}
