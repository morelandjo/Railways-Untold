package com.vodmordia.railwaysuntold.worldgen.placement;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterizes {@link ReplanEscalator} - the per-head "planner is stuck producing the same bad route"
 * detector. Three identical (pos, target) replan attempts in a row escalate; a different fingerprint resets
 * the count; escalating forgets the history so the count restarts. Pure (no world, no config).
 */
class ReplanEscalatorTest {

    private static final UUID HEAD = new UUID(1L, 1L);
    private static final UUID OTHER_HEAD = new UUID(2L, 2L);
    private static final BlockPos POS = new BlockPos(0, 64, 0);
    private static final BlockPos TARGET = new BlockPos(500, 64, 500);
    private static final BlockPos OTHER_POS = new BlockPos(10, 64, 10);
    private static final BlockPos OTHER_TARGET = new BlockPos(900, 64, 900);

    @Test
    void theThirdIdenticalAttemptEscalatesAndCountsUp() {
        ReplanEscalator escalator = new ReplanEscalator();

        ReplanEscalator.EscalationCheck c1 = escalator.recordAndCheck(HEAD, POS, TARGET);
        assertFalse(c1.shouldEscalate());
        assertEquals(1, c1.targetCount());

        ReplanEscalator.EscalationCheck c2 = escalator.recordAndCheck(HEAD, POS, TARGET);
        assertFalse(c2.shouldEscalate());
        assertEquals(2, c2.targetCount());

        ReplanEscalator.EscalationCheck c3 = escalator.recordAndCheck(HEAD, POS, TARGET);
        assertTrue(c3.shouldEscalate(), "the third identical attempt escalates");
        assertEquals(3, c3.targetCount());
    }

    @Test
    void escalatingForgetsTheHistorySoTheCountRestarts() {
        ReplanEscalator escalator = new ReplanEscalator();
        escalator.recordAndCheck(HEAD, POS, TARGET);
        escalator.recordAndCheck(HEAD, POS, TARGET);
        assertTrue(escalator.recordAndCheck(HEAD, POS, TARGET).shouldEscalate());

        ReplanEscalator.EscalationCheck after = escalator.recordAndCheck(HEAD, POS, TARGET);
        assertFalse(after.shouldEscalate(), "history was cleared on escalation");
        assertEquals(1, after.targetCount(), "the count restarts at 1");
    }

    @Test
    void aDifferentFingerprintResetsTheCount() {
        ReplanEscalator escalator = new ReplanEscalator();
        assertEquals(1, escalator.recordAndCheck(HEAD, POS, TARGET).targetCount());
        assertEquals(2, escalator.recordAndCheck(HEAD, POS, TARGET).targetCount());

        // A different (pos, target) is a fresh streak.
        assertEquals(1, escalator.recordAndCheck(HEAD, OTHER_POS, OTHER_TARGET).targetCount(),
                "changing the route resets the identical-failure streak");
        assertEquals(2, escalator.recordAndCheck(HEAD, OTHER_POS, OTHER_TARGET).targetCount());
    }

    @Test
    void changingOnlyTheTargetAlsoResetsTheStreak() {
        ReplanEscalator escalator = new ReplanEscalator();
        escalator.recordAndCheck(HEAD, POS, TARGET);
        assertEquals(1, escalator.recordAndCheck(HEAD, POS, OTHER_TARGET).targetCount(),
                "same pos but a new target is not the same fingerprint");
    }

    @Test
    void positionFailuresAccrueAcrossTargetChangesWhileTargetCountResets() {
        ReplanEscalator escalator = new ReplanEscalator();
        ReplanEscalator.EscalationCheck a = escalator.recordAndCheck(HEAD, POS, TARGET);
        assertEquals(1, a.targetCount());
        assertEquals(1, a.posCount());

        // Same position, new target: the identical-route streak restarts but the position tally - the
        // real loop signal logged on every replan - keeps climbing toward give-up.
        ReplanEscalator.EscalationCheck b = escalator.recordAndCheck(HEAD, POS, OTHER_TARGET);
        assertEquals(1, b.targetCount(), "a new target restarts the identical-route streak");
        assertEquals(2, b.posCount(), "but failures at the same position keep accruing");
    }

    @Test
    void headsAreTrackedIndependently() {
        ReplanEscalator escalator = new ReplanEscalator();
        escalator.recordAndCheck(HEAD, POS, TARGET);
        escalator.recordAndCheck(HEAD, POS, TARGET);
        // A different head starting the same route is on its own count.
        assertEquals(1, escalator.recordAndCheck(OTHER_HEAD, POS, TARGET).targetCount());
        // The first head's streak is unaffected -> its next is the escalating third.
        assertTrue(escalator.recordAndCheck(HEAD, POS, TARGET).shouldEscalate());
    }

    @Test
    void clearForgetsAHeadsStreak() {
        ReplanEscalator escalator = new ReplanEscalator();
        escalator.recordAndCheck(HEAD, POS, TARGET);
        escalator.recordAndCheck(HEAD, POS, TARGET);
        escalator.clear(HEAD);
        assertEquals(1, escalator.recordAndCheck(HEAD, POS, TARGET).targetCount(), "cleared -> streak restarts");
    }

    @Test
    void givesUpWhenStuckAtOnePositionEvenAsTheTargetKeepsChanging() {
        // The production loop: the head never moves, and each escalation re-explores to a new target
        // that then fails the same way. This MUST converge on give-up, not loop forever (the 62-failure
        // storm). A bounded simulation proves termination.
        ReplanEscalator escalator = new ReplanEscalator();
        BlockPos target = TARGET;
        boolean gaveUp = false;
        int escalations = 0;

        for (int i = 0; i < 100; i++) {
            ReplanEscalator.EscalationCheck check = escalator.recordAndCheck(HEAD, POS, target);
            if (check.shouldGiveUp()) {
                gaveUp = true;
                break;
            }
            if (check.shouldEscalate()) {
                escalations++;
                target = target.offset(8 * escalations, 0, 0); // orchestrator re-explores to a fresh target
            }
        }

        assertTrue(gaveUp, "a head stuck at one position must eventually be retired, not replan forever");
        assertTrue(escalations >= 1, "it should have re-explored (escalated) at least once before giving up");
    }

    @Test
    void aHeadMakingForwardProgressIsNeverRetired() {
        ReplanEscalator escalator = new ReplanEscalator();
        // Fail-then-move repeatedly: a head whose position keeps changing is making progress, so the
        // position tally never accrues and it is never given up on.
        for (int i = 0; i < 50; i++) {
            BlockPos movingPos = POS.offset(0, 0, i);
            assertFalse(escalator.recordAndCheck(HEAD, movingPos, TARGET).shouldGiveUp(),
                    "a head making forward progress must not be retired (iter " + i + ")");
        }
    }
}
