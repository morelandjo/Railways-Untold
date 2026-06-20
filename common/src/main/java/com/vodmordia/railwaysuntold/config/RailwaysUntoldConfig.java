package com.vodmordia.railwaysuntold.config;

import java.util.List;

/**
 * Configuration for Railways Untold generation.
 * Delegates to {@link RailwaysUntoldConfigImpl} for actual values.
 */
public class RailwaysUntoldConfig {

    public static RailwaysUntoldConfig getDefault() {
        return new RailwaysUntoldConfig();
    }

    private final IRailwaysUntoldConfig delegate = RailwaysUntoldConfigImpl.getInstance();

    public final boolean TRACKS_ENABLED = delegate.isTracksEnabled();

    public final int TRACK_PLACEMENT_DELAY_TICKS = delegate.getTrackPlacementDelayTicks();
    public final long CHUNK_STALE_THRESHOLD_MS = delegate.getChunkStaleThresholdMs();
    public final int CHUNK_CLEANUP_INTERVAL_TICKS = delegate.getChunkCleanupIntervalTicks();
    public final int TRACK_HEIGHT_OFFSET = delegate.getTrackHeightOffset();

    public final int BRANCH_MIN_DISTANCE = delegate.getBranchMinDistance();
    public final int BRANCH_CHANCE = delegate.getBranchChance();
    public final int MAX_BLOCKS_WITHOUT_BRANCH = delegate.getMaxBlocksWithoutBranch();

    public final boolean ENABLE_VILLAGE_TARGETING = delegate.isVillageTargetingEnabled();
    public final int VILLAGE_TARGETING_CHANCE = delegate.getVillageTargetingChance();
    public final int VILLAGE_SEARCH_RADIUS = delegate.getVillageSearchRadius();
    public final int VILLAGE_MIN_DISTANCE_FROM_SPAWN = delegate.getVillageMinDistanceFromSpawn();

    public final int TUNNEL_TORCH_HEIGHT = delegate.getTunnelTorchHeight();
    public final boolean TUNNEL_FACADE_ENABLED = delegate.isTunnelFacadeEnabled();
    public final boolean TUNNEL_TORCHES_ENABLED = delegate.areTunnelTorchesEnabled();


    public final int STATION_CLEARING_VERTICAL_HEIGHT = delegate.getStationClearingVerticalHeight();
    public final int STATION_CLEARING_ADJACENT_RADIUS = delegate.getStationClearingAdjacentRadius();

    public final int EVENT_SEPARATION_MIN_DISTANCE = delegate.getEventSeparationMinDistance();
    public final int EVENT_CHANCE = delegate.getEventChance();

    public final int BRIDGE_WATER_DISTANCE = delegate.getBridgeWaterDistance();

    public RailwaysUntoldConfig() {}

    public static int getHorizontalExpansion() {
        return RailwaysUntoldConfigImpl.getInstance().getHorizontalExpansion();
    }

    public static int getVerticalExpansion() {
        return RailwaysUntoldConfigImpl.getInstance().getVerticalExpansion();
    }

    public static boolean isStartingTrainEnabled() {
        return RailwaysUntoldConfigImpl.getInstance().isStartingTrainEnabled();
    }

    public static boolean isStationAtStartEnabled() {
        return RailwaysUntoldConfigImpl.getInstance().isStationAtStartEnabled();
    }

    public static boolean areBranchesEnabled() {
        return RailwaysUntoldConfigImpl.getInstance().areBranchesEnabled();
    }

    public static boolean isRecursiveBranchingEnabled() {
        return RailwaysUntoldConfigImpl.getInstance().isRecursiveBranchingEnabled();
    }

    public static int getMaxBranchDepth() {
        return RailwaysUntoldConfigImpl.getInstance().getMaxBranchDepth();
    }

    public static int getMaxActiveHeads() {
        return RailwaysUntoldConfigImpl.getInstance().getMaxActiveHeads();
    }

    public static boolean isAllAvoidanceEnabled() {
        return RailwaysUntoldConfigImpl.getInstance().isAllAvoidanceEnabled();
    }

    public static boolean areBankingCurvesEnabled() {
        return RailwaysUntoldConfigImpl.getInstance().areBankingCurvesEnabled();
    }

    public static int getMinBranchSpacing() {
        return RailwaysUntoldConfigImpl.getInstance().getMinBranchSpacing();
    }

    public static int getTunnelLightSpacing() {
        return RailwaysUntoldConfigImpl.getInstance().getTunnelLightSpacing();
    }

    public static int getLateralOffset() {
        return RailwaysUntoldConfigImpl.getInstance().getLateralOffset();
    }

    public static double getMaxSlopeRatio() {
        return RailwaysUntoldConfigImpl.getInstance().getMaxSlopeRatio();
    }

    public static int getMinCurveRadius() {
        return RailwaysUntoldConfigImpl.getInstance().getMinCurveRadius();
    }

    public static int getMaxCurveRadius() {
        return RailwaysUntoldConfigImpl.getInstance().getMaxCurveRadius();
    }

    public static String getTrackGauge() {
        return RailwaysUntoldConfigImpl.getInstance().getTrackGauge();
    }

    public static boolean areCustomEventsEnabled() {
        return RailwaysUntoldConfigImpl.getInstance().areCustomEventsEnabled();
    }

    public static boolean isVerboseLoggingEnabled() {
        return RailwaysUntoldConfigImpl.getInstance().isVerboseLoggingEnabled();
    }

    public static List<? extends String> getGenerateInWorldTypes() {
        return RailwaysUntoldConfigImpl.getInstance().getGenerateInWorldTypes();
    }

    public static boolean isSideBySideEnabled() {
        return RailwaysUntoldConfigImpl.getInstance().isSideBySideEnabled();
    }

    public static boolean isParallelMergeEnabled() {
        return RailwaysUntoldConfigImpl.getInstance().isParallelMergeEnabled();
    }

    public static boolean areSidingsEnabled() {
        return RailwaysUntoldConfigImpl.getInstance().areSidingsEnabled();
    }

    public static int getSidingMinStraightSegments() {
        return RailwaysUntoldConfigImpl.getInstance().getSidingMinStraightSegments();
    }

    public static int getBridgeWaterDistance() {
        return RailwaysUntoldConfigImpl.getInstance().getBridgeWaterDistance();
    }

}
