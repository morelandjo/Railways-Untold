package com.vodmordia.railwaysuntold.config;

/**
 * Unified interface for Railways Untold configuration values.
 * Extends all concern-specific config interfaces.
 */
public interface IRailwaysUntoldConfig extends
        ITrackClearingConfig,
        IBranchingConfig,
        ITunnelConfig,
        IVillageConfig,
        ITrackPlacementConfig,
        IEventConfig {
}
