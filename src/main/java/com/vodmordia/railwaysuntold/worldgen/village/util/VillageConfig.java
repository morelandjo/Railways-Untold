package com.vodmordia.railwaysuntold.worldgen.village.util;

import com.vodmordia.railwaysuntold.worldgen.village.AttemptedVillageTracker;
import com.vodmordia.railwaysuntold.worldgen.village.VillageAssignmentTracker;

import java.util.UUID;

/**
 * Centralized configuration constants and utilities for village targeting and track placement.
 */
public final class VillageConfig {

    private VillageConfig() {
    }

    /**
     * Structure regions are 34 chunks apart (vanilla village spacing)
     */
    public static final int DEFAULT_VILLAGE_SPACING = 34;

    /**
     * Result of checking if a village should be skipped.
     */
    public enum SkipReason {
        /**
         * Village can be targeted
         */
        NOT_SKIPPED,
        /**
         * Village is already assigned to another head
         */
        ALREADY_ASSIGNED,
        /**
         * Village has already been attempted
         */
        ALREADY_ATTEMPTED
    }

    /**
     * Check if a village should be skipped during targeting.
     *
     * @param villageId         The village UUID to check
     * @param assignmentTracker Assignment tracker to check (may be null to skip check)
     * @param attemptedTracker  Attempted tracker to check (may be null to skip check)
     * @return SkipReason indicating whether to skip and why
     */
    public static SkipReason shouldSkipVillage(
            UUID villageId,
            VillageAssignmentTracker assignmentTracker,
            AttemptedVillageTracker attemptedTracker) {

        // Check if already assigned
        if (assignmentTracker != null && assignmentTracker.isVillageAssigned(villageId)) {
            return SkipReason.ALREADY_ASSIGNED;
        }

        // Check if already attempted
        if (attemptedTracker != null && attemptedTracker.isVillageAttempted(villageId)) {
            return SkipReason.ALREADY_ATTEMPTED;
        }

        return SkipReason.NOT_SKIPPED;
    }
}
