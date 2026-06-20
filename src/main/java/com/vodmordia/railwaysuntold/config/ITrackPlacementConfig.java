package com.vodmordia.railwaysuntold.config;

/**
 * Configuration for core track placement timing and behavior.
 */
public interface ITrackPlacementConfig {

    boolean isTracksEnabled();

    int getTrackPlacementDelayTicks();
    int getChunkWaitDistanceBuffer();
    long getChunkStaleThresholdMs();
    int getChunkCleanupIntervalTicks();
    int getTrackHeightOffset();

    int getBridgeWaterDistance();
}
