package com.vodmordia.railwaysuntold.util.track;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.PlannerTestConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterizes {@link SlopeValidator} - the shared slope math (the single clamp every slope-enforcement site
 * uses). Assertions are written relative to the configured max ratio (read at runtime) so they hold for any
 * default. The two straight endpoint blocks are excluded from the angled run.
 */
class SlopeValidatorTest {

    @BeforeAll
    static void setUp() {
        PlannerTestConfig.bootstrapDefaults();
    }

    @Test
    void zeroRunIsValidOnlyWithNoElevationChange() {
        assertTrue(SlopeValidator.isValidSlope(0, 0));
        assertFalse(SlopeValidator.isValidSlope(0, 5), "no run but a height change -> invalid");
        assertTrue(SlopeValidator.isValidSlope(-3, 0), "no run, no change -> valid");
    }

    @Test
    void flatTrackIsAlwaysValid() {
        assertTrue(SlopeValidator.isValidSlope(100, 0));
    }

    @Test
    void aRunTooShortForAnyAngledBlockRejectsAnyElevation() {
        // effectiveDistance = horizontalDistance − 2 straight endpoints.
        assertFalse(SlopeValidator.isValidSlope(2, 1), "all run consumed by the straight endpoints");
        assertFalse(SlopeValidator.isValidSlope(1, 1));
    }

    @Test
    void gentleSlopesPassAndSteepSlopesFail() {
        assertTrue(SlopeValidator.isValidSlope(1000, 1), "1 over ~998 is gentle");
        assertFalse(SlopeValidator.isValidSlope(10, 1000), "1000 over 8 is far too steep");
    }

    @Test
    void minimumDistanceAlwaysValidatesForItsElevation() {
        // The distance it reports must itself pass isValidSlope for that elevation (round-trip invariant).
        for (int elev : new int[]{1, 5, 20, -12}) {
            int min = SlopeValidator.getMinimumDistance(elev);
            assertTrue(SlopeValidator.isValidSlope(min, elev),
                    "getMinimumDistance(" + elev + ")=" + min + " should validate");
        }
        assertEquals(0, SlopeValidator.getMinimumDistance(0), "flat needs no distance");
    }

    @Test
    void clampReturnsTheRequestedEndYWhenAlreadyLegal() {
        assertEquals(65, SlopeValidator.clampEndYToSlopeLimit(64, 65, 1000), "gentle -> unchanged");
        assertEquals(64, SlopeValidator.clampEndYToSlopeLimit(64, 64, 0), "no run -> start Y");
    }

    @Test
    void clampPullsATooSteepEndYBackToASlopeLegalValueInTheSameDirection() {
        double ratio = RailwaysUntoldConfig.getMaxSlopeRatio();

        // Too steep up: clamp lands below the request, above the start, and is itself slope-legal.
        int up = SlopeValidator.clampEndYToSlopeLimit(64, 64 + 1000, 20);
        assertTrue(up < 64 + 1000, "clamped below the impossible request");
        assertTrue(up >= 64, "still in the upward direction");
        assertTrue(SlopeValidator.isValidSlope(20, up - 64), "the clamped slope is legal");

        // Too steep down: mirrored.
        int down = SlopeValidator.clampEndYToSlopeLimit(64, 64 - 1000, 20);
        assertTrue(down > 64 - 1000);
        assertTrue(down <= 64, "still downward");
        assertTrue(SlopeValidator.isValidSlope(20, down - 64), "the clamped slope is legal");

        assertTrue(ratio > 0, "sanity: a positive max slope ratio is configured");
    }
}
