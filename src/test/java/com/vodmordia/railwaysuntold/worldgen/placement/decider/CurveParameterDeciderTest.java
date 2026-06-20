package com.vodmordia.railwaysuntold.worldgen.placement.decider;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.worldgen.placement.decider.CurveParameterDecider.CurveParameters;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.PlannerTestConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterizes {@link CurveParameterDecider}'s curve-radius contract: {@code createWithRadius} clamps the
 * radius to the configured {@code [min,max]} band, while {@code createWithExactRadius} passes it through
 * unclamped. The locked-route executor depends on the exact variant matching the planned radius bit-for-bit,
 * so the divergence between the two for an out-of-range radius is load-bearing. The curve endpoint comes
 * from the pure {@code CreateTrackUtil.calculateCurveEndpoint}, so this needs no world (level == null with
 * validation skipped).
 */
class CurveParameterDeciderTest {

    @BeforeAll
    static void setUp() {
        PlannerTestConfig.bootstrapDefaults();
    }

    private static final BlockPos START = new BlockPos(0, 70, 0);
    private static final Direction DIR = Direction.EAST;

    @Test
    void createWithRadiusClampsToTheConfiguredBand() {
        int min = RailwaysUntoldConfig.getMinCurveRadius();
        int max = RailwaysUntoldConfig.getMaxCurveRadius();
        assertTrue(min < max, "test assumes a non-degenerate radius band");

        CurveParameters tooSmall = CurveParameterDecider.createWithRadius(
                null, START, DIR, min - 5, true, 0, true);
        assertEquals(min, tooSmall.radius, "a sub-min radius is clamped up to min");

        CurveParameters tooLarge = CurveParameterDecider.createWithRadius(
                null, START, DIR, max + 50, true, 0, true);
        assertEquals(max, tooLarge.radius, "a super-max radius is clamped down to max");

        int inBand = (min + max) / 2;
        CurveParameters within = CurveParameterDecider.createWithRadius(
                null, START, DIR, inBand, true, 0, true);
        assertEquals(inBand, within.radius, "an in-band radius is left untouched");
    }

    @Test
    void createWithExactRadiusNeverClamps() {
        int max = RailwaysUntoldConfig.getMaxCurveRadius();
        int min = RailwaysUntoldConfig.getMinCurveRadius();

        assertEquals(max + 50, CurveParameterDecider.createWithExactRadius(
                START, DIR, max + 50, true, 0).radius, "exact keeps a super-max radius");
        assertEquals(min - 5, CurveParameterDecider.createWithExactRadius(
                START, DIR, min - 5, true, 0).radius, "exact keeps a sub-min radius");
    }

    @Test
    void exactAndClampedDivergeGeometricallyForAnOutOfRangeRadius() {
        int max = RailwaysUntoldConfig.getMaxCurveRadius();
        int oversize = max + 50;

        CurveParameters exact = CurveParameterDecider.createWithExactRadius(START, DIR, oversize, true, 0);
        CurveParameters clamped = CurveParameterDecider.createWithRadius(
                null, START, DIR, oversize, true, 0, true);

        assertNotEquals(exact.radius, clamped.radius, "the radii differ once clamping kicks in");
        // A larger radius sweeps a wider arc, so the endpoints must land in different places.
        assertNotEquals(exact.end, clamped.end, "different radii produce different curve endpoints");
    }

    @Test
    void bothFactoriesSetA90DegreeCurveAndPreserveOrientation() {
        CurveParameters exact = CurveParameterDecider.createWithExactRadius(START, DIR, 20, true, 7);
        assertEquals(90, exact.angle, "curves are quarter turns");
        assertEquals(true, exact.turnLeft);
        assertEquals(DIR, exact.trackDirection);
        assertEquals(7, exact.elevationChange);
        assertEquals(START, exact.start);

        CurveParameters clamped = CurveParameterDecider.createWithRadius(
                null, START, DIR, 20, false, -3, true);
        assertEquals(90, clamped.angle);
        assertEquals(false, clamped.turnLeft);
        assertEquals(DIR, clamped.trackDirection);
        assertEquals(-3, clamped.elevationChange);
        assertEquals(START, clamped.start);
    }
}
