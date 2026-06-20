package com.vodmordia.railwaysuntold.worldgen.head;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the village-assignment capture round-trips and that {@link VillageAssignmentReplay} reproduces a
 * decision offline - the regression vehicle for village mis-assignments (e.g. a head swung off-course by a
 * detour committing to a side village), the analogue of the route planner's {@code ReplayCaseTest}.
 */
class VillageAssignReplayTest {

    @Test
    void serializeParseRoundTripPreservesEveryField() {
        VillageAssignRecord original = new VillageAssignRecord(
                11, "RETARGET",
                new BlockPos(7986, 70, 1755), Direction.EAST, Direction.SOUTH, true,
                new BlockPos(0, 70, 0), 1200, 8176,
                new BlockPos(8786, 70, 1845),
                List.of(
                        new VillageAssignRecord.Candidate(VillageAssignRecord.Source.PREDICTED,
                                new BlockPos(8786, 70, 1845), true, false, false, false),
                        new VillageAssignRecord.Candidate(VillageAssignRecord.Source.LOCATED,
                                new BlockPos(7600, 70, 1700), false, false, true, false)));

        VillageAssignRecord parsed = VillageAssignRecord.parse(original.serialize());

        assertEquals(original.headNumber, parsed.headNumber);
        assertEquals(original.mode, parsed.mode);
        assertEquals(original.pos, parsed.pos);
        assertEquals(original.dir, parsed.dir);
        assertEquals(original.initDir, parsed.initDir);
        assertEquals(original.isOriginal, parsed.isOriginal);
        assertEquals(original.spawn, parsed.spawn);
        assertEquals(original.searchRadius, parsed.searchRadius);
        assertEquals(original.headDistFromSpawn, parsed.headDistFromSpawn);
        assertEquals(original.chosen, parsed.chosen);
        assertEquals(original.candidates, parsed.candidates);
    }

    /**
     * Harness fidelity: the replay reproduces the runtime selection rules with no world - the not-behind
     * cone keyed off the INITIAL direction, predicted preferred over located, nearest within a source. A
     * head exploring SOUTH from (7986,1755) with a predicted village ahead, a located village further
     * ahead, and one nearly due east (outside the 45° cone): the predicted ahead one is chosen.
     */
    @Test
    void replayReproducesTheRuntimeSelectionRules() {
        VillageAssignRecord rec = new VillageAssignRecord(
                11, "RETARGET",
                new BlockPos(7986, 70, 1755), Direction.EAST, Direction.SOUTH, true,
                new BlockPos(0, 70, 0), 1200, 8176,
                new BlockPos(8000, 70, 2200),
                List.of(
                        // ahead (within 45° of SOUTH), predicted -> preferred
                        new VillageAssignRecord.Candidate(VillageAssignRecord.Source.PREDICTED,
                                new BlockPos(8000, 70, 2200), true, false, false, false),
                        // also ahead but farther, located -> lower priority than any eligible predicted
                        new VillageAssignRecord.Candidate(VillageAssignRecord.Source.LOCATED,
                                new BlockPos(7980, 70, 2600), true, false, false, false),
                        // nearly due EAST of the head: ~83° off SOUTH, outside the cone -> rejected
                        new VillageAssignRecord.Candidate(VillageAssignRecord.Source.PREDICTED,
                                new BlockPos(8786, 70, 1845), false, false, false, false)));

        assertEquals(new BlockPos(8000, 70, 2200), VillageAssignmentReplay.choose(rec),
                "replay should pick the in-cone predicted village, reproducing the runtime selection");

        // The east village really is outside the not-behind cone from this pose (recomputed, not trusted).
        assertFalse(VillageAssignmentReplay.isEligible(rec, new VillageAssignRecord.Candidate(
                VillageAssignRecord.Source.PREDICTED, new BlockPos(8786, 70, 1845), false, false, false, false)));
    }

    @Test
    void eligibilityRejectsAssignedAttemptedAndBacktrackingCandidates() {
        BlockPos pos = new BlockPos(7986, 70, 1755);
        BlockPos spawn = new BlockPos(0, 70, 0);
        VillageAssignRecord rec = new VillageAssignRecord(
                11, "NEW", pos, Direction.SOUTH, Direction.SOUTH, true, spawn, 1200, 8176, null, List.of());

        // Ahead and far from spawn, but already taken / already attempted: both rejected.
        assertFalse(VillageAssignmentReplay.isEligible(rec, new VillageAssignRecord.Candidate(
                VillageAssignRecord.Source.PREDICTED, new BlockPos(8786, 70, 1845), true, false, true, false)));
        assertFalse(VillageAssignmentReplay.isEligible(rec, new VillageAssignRecord.Candidate(
                VillageAssignRecord.Source.PREDICTED, new BlockPos(8786, 70, 1845), true, false, false, true)));

        // A village much closer to spawn than the head is: backtracking, rejected.
        VillageAssignRecord.Candidate backtrack = new VillageAssignRecord.Candidate(
                VillageAssignRecord.Source.LOCATED, new BlockPos(500, 70, 1755), true, true, false, false);
        assertTrue(VillageAssignmentReplay.isBacktracking(rec, backtrack.center()));
        assertFalse(VillageAssignmentReplay.isEligible(rec, backtrack));

        // No eligible candidates -> no assignment.
        assertNull(VillageAssignmentReplay.choose(rec));
    }
}
