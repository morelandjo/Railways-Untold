package com.vodmordia.railwaysuntold.worldgen.integration.create;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterizes the pure vector geometry in {@link TrackGeometryCalculator}: 2D (XZ) line intersection and
 * the slope-tilted track normal. These feed Create's track-graph stitching; the rest of the class is lazy-
 * reflection Create accessors (no static initializer), so these methods test on both branches.
 */
class TrackGeometryCalculatorTest {

    private static final double EPS = 1e-9;

    private static void assertVec(double x, double y, double z, Vec3 actual) {
        assertEquals(x, actual.x, EPS, "x");
        assertEquals(y, actual.y, EPS, "y");
        assertEquals(z, actual.z, EPS, "z");
    }

    @Test
    void intersectLines2DReturnsTheParametersOfTheCrossingPoint() {
        // Line 1 along +X from origin; line 2 along +Z from (5,*,-5). They meet at (5,*,0): t=u=5.
        double[] tu = TrackGeometryCalculator.intersectLines2D(
                new Vec3(0, 0, 0), new Vec3(5, 0, -5), new Vec3(1, 0, 0), new Vec3(0, 0, 1));
        assertEquals(5.0, tu[0], EPS);
        assertEquals(5.0, tu[1], EPS);
    }

    @Test
    void intersectLines2DIgnoresYAndReturnsNullForParallelLines() {
        // Same and opposite directions both have a zero cross-determinant -> parallel -> null.
        assertNull(TrackGeometryCalculator.intersectLines2D(
                new Vec3(0, 0, 0), new Vec3(0, 0, 5), new Vec3(1, 0, 0), new Vec3(2, 0, 0)), "parallel");
        assertNull(TrackGeometryCalculator.intersectLines2D(
                new Vec3(0, 0, 0), new Vec3(0, 0, 5), new Vec3(1, 0, 0), new Vec3(-1, 0, 0)), "anti-parallel");
        // Y differs wildly but the XZ projection still crosses -> not null (Y is ignored).
        assertTrue(TrackGeometryCalculator.intersectLines2D(
                new Vec3(0, 100, 0), new Vec3(5, -100, -5), new Vec3(1, 0, 0), new Vec3(0, 0, 1)) != null,
                "Y is ignored in the intersection test");
    }

    @Test
    void elevatedNormalIsTheBaseNormalWhenThereIsNoSlope() {
        Vec3 base = new Vec3(0, 1, 0);
        Vec3 axis = new Vec3(1, 0, 0);
        assertSame(base, TrackGeometryCalculator.calculateElevatedNormal(base, axis, 0, 10), "flat -> unchanged");
        assertSame(base, TrackGeometryCalculator.calculateElevatedNormal(base, axis, 5, 0), "no run -> unchanged");
    }

    @Test
    void elevatedNormalTiltsBackOnTheWayUpAndForwardOnTheWayDown() {
        Vec3 base = new Vec3(0, 1, 0);
        Vec3 east = new Vec3(1, 0, 0);

        // Up a 0.5 slope: normal gains a −X component (tilts back, opposite travel), then is normalized.
        Vec3 up = TrackGeometryCalculator.calculateElevatedNormal(base, east, 5, 10);
        assertTrue(up.x < 0, "up-slope normal tilts back (−X): " + up);
        assertEquals(1.0, up.length(), EPS, "normalized");
        assertVec(-0.5 / Math.sqrt(1.25), 1.0 / Math.sqrt(1.25), 0.0, up);

        // Down the same slope flips the sign: normal tilts forward (+X).
        Vec3 down = TrackGeometryCalculator.calculateElevatedNormal(base, east, -5, 10);
        assertTrue(down.x > 0, "down-slope normal tilts forward (+X): " + down);
    }

    @Test
    void elevatedNormalClampsTheSlopeToFortyFiveDegrees() {
        Vec3 base = new Vec3(0, 1, 0);
        Vec3 east = new Vec3(1, 0, 0);
        // slope 100 clamps to 1.0, the same as an exact 1:1 slope.
        Vec3 superSteep = TrackGeometryCalculator.calculateElevatedNormal(base, east, 1000, 10);
        Vec3 oneToOne = TrackGeometryCalculator.calculateElevatedNormal(base, east, 10, 10);
        assertVec(oneToOne.x, oneToOne.y, oneToOne.z, superSteep);
        assertVec(-1.0 / Math.sqrt(2), 1.0 / Math.sqrt(2), 0.0, superSteep);
    }

    @Test
    void slopeNormalMatchesTheElevatedNormalAtTheEquivalentSlope() {
        Vec3 base = new Vec3(0, 1, 0);
        Vec3 east = new Vec3(1, 0, 0);
        assertSame(base, TrackGeometryCalculator.calculateSlopeNormal(base, east, 0.0), "zero slope -> unchanged");

        // calculateSlopeNormal(slope=0.5) == calculateElevatedNormal(elev=5, run=10).
        Vec3 bySlope = TrackGeometryCalculator.calculateSlopeNormal(base, east, 0.5);
        Vec3 byElevation = TrackGeometryCalculator.calculateElevatedNormal(base, east, 5, 10);
        assertVec(byElevation.x, byElevation.y, byElevation.z, bySlope);
    }
}
