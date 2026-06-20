package com.vodmordia.railwaysuntold;

import com.vodmordia.railwaysuntold.config.ModConfig;
import com.vodmordia.railwaysuntold.config.ModConfigHolder;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class RailwaysUntoldConfig {

    public static void initialize() {
        RailwaysUntold.LOGGER.info("[RailwaysUntold] Config system ready");
    }

    private static ModConfig config() {
        return ModConfigHolder.getConfig();
    }

    public static int getHorizontalExpansion() {
        return (int) config().horizontalExpansion;
    }

    public static int getVerticalExpansion() {
        return (int) config().verticalExpansion;
    }

    public static boolean isStartingTrainEnabled() {
        return config().startingTrain;
    }

    public static boolean isStationAtStartEnabled() {
        return config().stationAtStart;
    }

    public static boolean areBranchesEnabled() {
        return config().branchesEnabled;
    }

    public static boolean isRecursiveBranchingEnabled() {
        return config().recursiveBranchingEnabled;
    }

    /** 0 means unlimited */
    public static int getMaxBranchDepth() {
        return (int) config().maxBranchDepth;
    }

    /** 0 means unlimited */
    public static int getMaxActiveHeads() {
        return (int) config().maxActiveHeads;
    }


    public static int getBridgeElevationThreshold() {
        return (int) config().bridgeElevationThreshold;
    }

    public static boolean areBankingCurvesEnabled() {
        return config().bankingCurves;
    }

    public static int getMinBranchSpacing() {
        return (int) config().minBranchSpacing;
    }

    public static int getTunnelLightSpacing() {
        return (int) config().lightSpacing;
    }

    public static boolean isStructureTargetingEnabled() {
        return config().structureTargeting;
    }

    public static boolean isAllAvoidanceEnabled() {
        return config().allAvoidance;
    }

    public static int getSlopeRise() {
        return (int) config().slopeRise;
    }

    public static int getSlopeRun() {
        return (int) config().slopeRun;
    }

    public static double getMaxSlopeRatio() {
        return (double) getSlopeRise() / getSlopeRun();
    }

    public static int getMinCurveRadius() {
        return (int) config().minCurveRadius;
    }

    public static int getMaxCurveRadius() {
        return (int) config().maxCurveRadius;
    }

    public static boolean areLateralsEnabled() {
        return config().laterals;
    }

    public static int getLateralOffset() {
        return (int) config().lateralOffset;
    }

    public static boolean isSideBySideEnabled() {
        return config().sideBySideTracks;
    }

    public static boolean isParallelMergeEnabled() {
        return config().parallelMergeEnabled;
    }

    public static boolean areSidingsEnabled() {
        return config().sidingsEnabled;
    }

    public static int getSidingMinStraightSegments() {
        return config().sidingMinStraightSegments;
    }

    public static int getBridgeWaterDistance() {
        return (int) config().bridgeWaterDistance;
    }

    public static String getTrackGauge() {
        return config().trackGauge;
    }

    public static boolean areCustomEventsEnabled() {
        return config().customEvents;
    }

    public static int getEventSeparationMinDistance() {
        return (int) config().eventSeparationMinDistance;
    }

    public static int getEventChance() {
        return (int) config().eventChance;
    }

    public static boolean isVerboseLoggingEnabled() {
        return config().verboseLogging;
    }


    public static List<String> getGenerateInWorldTypes() {
        String raw = config().generateInWorldTypes;
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        // Strip surrounding brackets and quotes in case config was saved in JSON/TOML array format
        String cleaned = raw.strip();
        if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).strip();
        }
        return Arrays.stream(cleaned.split(","))
                .map(s -> s.trim().replace("\"", ""))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
