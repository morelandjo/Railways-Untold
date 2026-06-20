package com.vodmordia.railwaysuntold.config;

import com.vodmordia.railwaysuntold.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Unified implementation of Railways Untold configuration.
 * Combines TOML-based user-configurable values with hardcoded default values.
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
        return Config.HORIZONTAL_EXPANSION.get();
    }

    public int getVerticalExpansion() {
        return Config.VERTICAL_EXPANSION.get();
    }

    public boolean isStartingTrainEnabled() {
        return Config.STARTING_TRAIN.get();
    }

    public boolean isStationAtStartEnabled() {
        return Config.STARTING_STATION.get();
    }

    public boolean areBranchesEnabled() {
        return Config.BRANCHES_ENABLED.get();
    }

    public boolean isRecursiveBranchingEnabled() {
        return Config.RECURSIVE_BRANCHING_ENABLED.get();
    }

    public int getMaxBranchDepth() {
        return Config.MAX_BRANCH_DEPTH.get();
    }

    public int getMaxActiveHeads() {
        return Config.MAX_ACTIVE_HEADS.get();
    }

    public int getMinBranchSpacing() {
        return Config.MIN_BRANCH_SPACING.get();
    }

    public int getBridgeElevationThreshold() {
        return Config.BRIDGE_ELEVATION_THRESHOLD.get();
    }

    public boolean areBankingCurvesEnabled() {
        return Config.BANKING_CURVES.get();
    }

    public boolean areLateralsEnabled() {
        return Config.LATERALS.get();
    }

    public int getLateralOffset() {
        return Config.LATERAL_OFFSET.get();
    }

    public int getTunnelLightSpacing() {
        return Config.TUNNEL_LIGHT_SPACING.get();
    }

    public List<? extends String> getGenerateInWorldTypes() {
        return parseCommaSeparated(Config.GENERATE_IN_WORLD_TYPES.get());
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
        return Config.ALL_AVOIDANCE.get();
    }

    @Override
    public boolean isVillageTargetingEnabled() {
        if (!Config.STRUCTURE_TARGETING.get()) return false;
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
        return Config.SLOPE_RISE.get();
    }

    public int getSlopeRun() {
        return Config.SLOPE_RUN.get();
    }

    public double getMaxSlopeRatio() {
        return (double) getSlopeRise() / getSlopeRun();
    }

    public int getMinCurveRadius() {
        return Config.MIN_CURVE_RADIUS.get();
    }

    public int getMaxCurveRadius() {
        return Config.MAX_CURVE_RADIUS.get();
    }

    public boolean isSideBySideEnabled() {
        return Config.SIDE_BY_SIDE_TRACKS.get();
    }

    public boolean isParallelMergeEnabled() {
        return Config.PARALLEL_MERGE_ENABLED.get();
    }

    public boolean areSidingsEnabled() {
        return Config.SIDINGS_ENABLED.get();
    }

    public int getSidingMinStraightSegments() {
        return Config.SIDING_MIN_STRAIGHT_SEGMENTS.get();
    }

    @Override
    public int getBridgeWaterDistance() {
        return Config.BRIDGE_WATER_DISTANCE.get();
    }

    public String getTrackGauge() {
        return Config.TRACK_GAUGE.get();
    }

    public boolean areCustomEventsEnabled() {
        return Config.CUSTOM_EVENTS.get();
    }

    @Override
    public int getEventSeparationMinDistance() {
        return Config.EVENT_SEPARATION_MIN_DISTANCE.get();
    }

    @Override
    public int getEventChance() {
        return Config.EVENT_CHANCE.get();
    }

    public boolean isVerboseLoggingEnabled() {
        return Config.VERBOSE_LOGGING.get();
    }

    private static List<String> parseCommaSeparated(String value) {
        if (value == null || value.isBlank()) {
            return Collections.emptyList();
        }
        // Strip surrounding brackets and quotes in case config was saved in JSON/TOML array format
        String cleaned = value.strip();
        if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).strip();
        }
        return Arrays.stream(cleaned.split(","))
                .map(s -> s.trim().replace("\"", ""))
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
