package com.vodmordia.railwaysuntold.config;

import com.vodmordia.railwaysuntold.RailwaysUntoldConfig;

import java.util.List;

/**
 * Unified implementation of Railways Untold configuration.
 * Combines Cloth Config user-configurable values with hardcoded default values.
 *
 * Use the singleton instance via {@link #getInstance()} or inject via the interface.
 */
public class RailwaysUntoldConfigImpl implements IRailwaysUntoldConfig {

    private static final RailwaysUntoldConfigImpl INSTANCE = new RailwaysUntoldConfigImpl();

    public static RailwaysUntoldConfigImpl getInstance() {
        return INSTANCE;
    }

    private final boolean tracksEnabled = true;

    private final int trackPlacementDelayTicks = 2;
    private final int chunkWaitDistanceBuffer = 5;
    private final long chunkStaleThresholdMs = 300000;
    private final int chunkCleanupIntervalTicks = 1200;
    private final int trackHeightOffset = 1;

    private final int branchMinDistance = 300;
    private final int branchChance = 10;
    private final int maxBlocksWithoutBranch = 700;

    private final int villageTargetingChance = 100;
    private final int villageSearchRadius = 1000;
    private final int villageMinDistanceFromSpawn = 100;

    private final int tunnelTorchHeight = 2;
    private final boolean tunnelFacadeEnabled = true;
    private final boolean tunnelTorchesEnabled = true;

    private final int stationClearingVerticalHeight = 3;
    private final int stationClearingAdjacentRadius = 2;

    private RailwaysUntoldConfigImpl() {
        // Singleton - use getInstance()
    }

    public int getHorizontalExpansion() {
        return RailwaysUntoldConfig.getHorizontalExpansion();
    }

    public int getVerticalExpansion() {
        return RailwaysUntoldConfig.getVerticalExpansion();
    }

    public boolean isStartingTrainEnabled() {
        return RailwaysUntoldConfig.isStartingTrainEnabled();
    }

    public boolean isStationAtStartEnabled() {
        return RailwaysUntoldConfig.isStationAtStartEnabled();
    }

    public boolean areBranchesEnabled() {
        return RailwaysUntoldConfig.areBranchesEnabled();
    }

    public boolean isRecursiveBranchingEnabled() {
        return RailwaysUntoldConfig.isRecursiveBranchingEnabled();
    }

    public int getMaxBranchDepth() {
        return RailwaysUntoldConfig.getMaxBranchDepth();
    }

    public int getMaxActiveHeads() {
        return RailwaysUntoldConfig.getMaxActiveHeads();
    }

    public int getMinBranchSpacing() {
        return RailwaysUntoldConfig.getMinBranchSpacing();
    }

    public int getBridgeElevationThreshold() {
        return RailwaysUntoldConfig.getBridgeElevationThreshold();
    }

    public boolean areBankingCurvesEnabled() {
        return RailwaysUntoldConfig.areBankingCurvesEnabled();
    }

    public boolean areLateralsEnabled() {
        return RailwaysUntoldConfig.areLateralsEnabled();
    }

    public int getLateralOffset() {
        return RailwaysUntoldConfig.getLateralOffset();
    }

    public int getTunnelLightSpacing() {
        return RailwaysUntoldConfig.getTunnelLightSpacing();
    }

    public List<? extends String> getGenerateInWorldTypes() {
        return RailwaysUntoldConfig.getGenerateInWorldTypes();
    }

    @Override
    public boolean isTracksEnabled() {
        return tracksEnabled;
    }

    @Override
    public int getTrackPlacementDelayTicks() {
        return trackPlacementDelayTicks;
    }

    @Override
    public int getChunkWaitDistanceBuffer() {
        return chunkWaitDistanceBuffer;
    }

    @Override
    public long getChunkStaleThresholdMs() {
        return chunkStaleThresholdMs;
    }

    @Override
    public int getChunkCleanupIntervalTicks() {
        return chunkCleanupIntervalTicks;
    }

    @Override
    public int getTrackHeightOffset() {
        return trackHeightOffset;
    }

    @Override
    public int getBranchMinDistance() {
        return branchMinDistance;
    }

    @Override
    public int getBranchChance() {
        return branchChance;
    }

    @Override
    public int getMaxBlocksWithoutBranch() {
        return maxBlocksWithoutBranch;
    }

    public boolean isAllAvoidanceEnabled() {
        return RailwaysUntoldConfig.isAllAvoidanceEnabled();
    }

    @Override
    public boolean isVillageTargetingEnabled() {
        if (!RailwaysUntoldConfig.isStructureTargetingEnabled()) return false;
        List<String> tags = com.vodmordia.railwaysuntold.datapack.BiomeSettingsLoader.INSTANCE
                .resolveStructureSettings().targetTags();
        return !tags.isEmpty() && !isSideBySideEnabled();
    }

    @Override
    public int getVillageTargetingChance() {
        return villageTargetingChance;
    }

    @Override
    public int getVillageSearchRadius() {
        return villageSearchRadius;
    }

    @Override
    public int getVillageMinDistanceFromSpawn() {
        return villageMinDistanceFromSpawn;
    }

    @Override
    public int getTunnelTorchHeight() {
        return tunnelTorchHeight;
    }

    @Override
    public boolean isTunnelFacadeEnabled() {
        return tunnelFacadeEnabled;
    }

    @Override
    public boolean areTunnelTorchesEnabled() {
        return tunnelTorchesEnabled;
    }

    @Override
    public int getStationClearingVerticalHeight() {
        return stationClearingVerticalHeight;
    }

    @Override
    public int getStationClearingAdjacentRadius() {
        return stationClearingAdjacentRadius;
    }

    public int getSlopeRise() {
        return RailwaysUntoldConfig.getSlopeRise();
    }

    public int getSlopeRun() {
        return RailwaysUntoldConfig.getSlopeRun();
    }

    public double getMaxSlopeRatio() {
        return RailwaysUntoldConfig.getMaxSlopeRatio();
    }

    public int getMinCurveRadius() {
        return RailwaysUntoldConfig.getMinCurveRadius();
    }

    public int getMaxCurveRadius() {
        return RailwaysUntoldConfig.getMaxCurveRadius();
    }

    public boolean isSideBySideEnabled() {
        return RailwaysUntoldConfig.isSideBySideEnabled();
    }

    public boolean isParallelMergeEnabled() {
        return RailwaysUntoldConfig.isParallelMergeEnabled();
    }

    public boolean areSidingsEnabled() {
        return RailwaysUntoldConfig.areSidingsEnabled();
    }

    public int getSidingMinStraightSegments() {
        return RailwaysUntoldConfig.getSidingMinStraightSegments();
    }

    @Override
    public int getBridgeWaterDistance() {
        return RailwaysUntoldConfig.getBridgeWaterDistance();
    }

    public String getTrackGauge() {
        return RailwaysUntoldConfig.getTrackGauge();
    }

    public boolean areCustomEventsEnabled() {
        return RailwaysUntoldConfig.areCustomEventsEnabled();
    }

    @Override
    public int getEventSeparationMinDistance() {
        return RailwaysUntoldConfig.getEventSeparationMinDistance();
    }

    @Override
    public int getEventChance() {
        return RailwaysUntoldConfig.getEventChance();
    }

    public boolean isVerboseLoggingEnabled() {
        return RailwaysUntoldConfig.isVerboseLoggingEnabled();
    }

}
