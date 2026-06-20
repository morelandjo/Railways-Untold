package com.vodmordia.railwaysuntold.worldgen.village;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterizes {@link VillageAssignmentTracker} - the bidirectional village↔head assignment map with the
 * re-bind invariants the branching/decision paths rely on. Pure (no world, no config).
 */
class VillageAssignmentTrackerTest {

    private static final UUID VILLAGE_A = new UUID(0L, 0xA);
    private static final UUID VILLAGE_B = new UUID(0L, 0xB);
    private static final UUID HEAD_1 = new UUID(1L, 0x1);
    private static final UUID HEAD_2 = new UUID(1L, 0x2);

    @Test
    void assignBindsTheVillageAndHeadBothWays() {
        VillageAssignmentTracker tracker = new VillageAssignmentTracker();
        assertTrue(tracker.assignVillage(VILLAGE_A, HEAD_1));
        assertTrue(tracker.isVillageAssigned(VILLAGE_A));
    }

    @Test
    void assignRefusesNullsAnAlreadyTakenVillageOrAnAlreadyBoundHead() {
        VillageAssignmentTracker tracker = new VillageAssignmentTracker();
        assertFalse(tracker.assignVillage(null, HEAD_1));
        assertFalse(tracker.assignVillage(VILLAGE_A, null));

        assertTrue(tracker.assignVillage(VILLAGE_A, HEAD_1));
        assertFalse(tracker.assignVillage(VILLAGE_A, HEAD_2), "the village is already taken by head 1");
        assertFalse(tracker.assignVillage(VILLAGE_B, HEAD_1), "head 1 is already bound to village A");
    }

    @Test
    void unassignHeadFreesItsVillageForReassignment() {
        VillageAssignmentTracker tracker = new VillageAssignmentTracker();
        tracker.assignVillage(VILLAGE_A, HEAD_1);

        tracker.unassignHead(HEAD_1);
        assertFalse(tracker.isVillageAssigned(VILLAGE_A), "the village is free again");
        assertTrue(tracker.assignVillage(VILLAGE_A, HEAD_2), "and can be taken by another head");
    }

    @Test
    void reassignReleasesTheHeadsPreviousVillageAtomically() {
        VillageAssignmentTracker tracker = new VillageAssignmentTracker();
        tracker.assignVillage(VILLAGE_A, HEAD_1);

        assertTrue(tracker.reassignVillage(VILLAGE_B, HEAD_1), "rebind head 1 from A to B");
        assertFalse(tracker.isVillageAssigned(VILLAGE_A), "the old village A is released");
        assertTrue(tracker.isVillageAssigned(VILLAGE_B));
    }

    @Test
    void reassignFailsWhenTheTargetVillageBelongsToAnotherHead() {
        VillageAssignmentTracker tracker = new VillageAssignmentTracker();
        tracker.assignVillage(VILLAGE_B, HEAD_2);

        assertFalse(tracker.reassignVillage(VILLAGE_B, HEAD_1), "village B is head 2's");
        assertTrue(tracker.isVillageAssigned(VILLAGE_B), "and head 2 keeps it");
    }

    @Test
    void reassignToAHeadsOwnVillageIsANoOpSuccess() {
        VillageAssignmentTracker tracker = new VillageAssignmentTracker();
        tracker.assignVillage(VILLAGE_A, HEAD_1);
        assertTrue(tracker.reassignVillage(VILLAGE_A, HEAD_1));
        assertTrue(tracker.isVillageAssigned(VILLAGE_A));
    }

    @Test
    void angleTooCloseExcludesTheCandidateHeadAndComparesAngularSeparation() {
        VillageAssignmentTracker tracker = new VillageAssignmentTracker();
        tracker.registerHeadAngle(HEAD_1, 100.0, true);

        assertTrue(tracker.isAngleTooClose(105.0, HEAD_2, 10.0), "5° from head 1 is within 10°");
        assertFalse(tracker.isAngleTooClose(120.0, HEAD_2, 10.0), "20° from head 1 is outside 10°");
        assertFalse(tracker.isAngleTooClose(100.0, HEAD_1, 10.0), "excluding head 1 leaves nobody to clash with");
    }

    @Test
    void theBranchOverloadSelectsTheSmallerSeparationForBranchHeads() {
        VillageAssignmentTracker tracker = new VillageAssignmentTracker();
        tracker.registerHeadAngle(HEAD_1, 100.0, true);

        // 15° apart: too close for an original head (sep 30) but fine for a branch (sep 10).
        assertTrue(tracker.isAngleTooClose(115.0, HEAD_2, 30.0, 10.0, false), "original head uses 30°");
        assertFalse(tracker.isAngleTooClose(115.0, HEAD_2, 30.0, 10.0, true), "branch head uses 10°");
    }

    @Test
    void clearAllEmptiesEveryAssignment() {
        VillageAssignmentTracker tracker = new VillageAssignmentTracker();
        tracker.assignVillage(VILLAGE_A, HEAD_1);
        tracker.registerHeadAngle(HEAD_1, 100.0, true);
        tracker.clearAll();
        assertFalse(tracker.isVillageAssigned(VILLAGE_A));
        assertFalse(tracker.isAngleTooClose(100.0, HEAD_2, 10.0), "no angles remain");
    }
}
