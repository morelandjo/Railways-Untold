package com.vodmordia.railwaysuntold.util.spatial;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterizes {@link DirectionUtil} - the pure directional math the whole worldgen sweep leaned on without
 * pinning: the left/right turn convention behind every curve, angular math, segment direction, perpendicularity,
 * axis projection, and the directional predicates. World/config-free.
 */
class DirectionUtilTest {

    @Nested
    class TurnConvention {
        @Test
        void leftAndRightRotateTheCompassConsistently() {
            // Facing each cardinal, left is a 90° CCW turn, right is 90° CW.
            assertEquals(Direction.WEST, DirectionUtil.getLeftDirection(Direction.NORTH));
            assertEquals(Direction.NORTH, DirectionUtil.getLeftDirection(Direction.EAST));
            assertEquals(Direction.EAST, DirectionUtil.getLeftDirection(Direction.SOUTH));
            assertEquals(Direction.SOUTH, DirectionUtil.getLeftDirection(Direction.WEST));

            assertEquals(Direction.EAST, DirectionUtil.getRightDirection(Direction.NORTH));
            assertEquals(Direction.SOUTH, DirectionUtil.getRightDirection(Direction.EAST));
            assertEquals(Direction.WEST, DirectionUtil.getRightDirection(Direction.SOUTH));
            assertEquals(Direction.NORTH, DirectionUtil.getRightDirection(Direction.WEST));
        }

        @Test
        void perpendicularSelectsLeftOrRightByTheFlag() {
            assertEquals(DirectionUtil.getLeftDirection(Direction.EAST),
                    DirectionUtil.getPerpendicularDirection(Direction.EAST, true));
            assertEquals(DirectionUtil.getRightDirection(Direction.EAST),
                    DirectionUtil.getPerpendicularDirection(Direction.EAST, false));
        }

        @Test
        void getPositiveDirectionMapsTrackAxisAndRejectsOthers() {
            assertEquals(Direction.SOUTH, DirectionUtil.getPositiveDirection(Direction.NORTH), "N/S track -> +Z");
            assertEquals(Direction.EAST, DirectionUtil.getPositiveDirection(Direction.EAST), "E/W track -> +X");
            assertThrows(IllegalArgumentException.class, () -> DirectionUtil.getPositiveDirection(Direction.SOUTH));
        }
    }

    @Nested
    class Angles {
        @Test
        void angularDistanceIsTheShortestWayAroundTheCompass() {
            assertEquals(20.0, DirectionUtil.angularDistance(10, 350), 1e-9, "wraps the short way");
            assertEquals(20.0, DirectionUtil.angularDistance(350, 10), 1e-9, "and is symmetric");
            assertEquals(180.0, DirectionUtil.angularDistance(0, 180), 1e-9);
            assertEquals(179.0, DirectionUtil.angularDistance(0, 181), 1e-9, "just past opposite wraps back");
            assertEquals(0.0, DirectionUtil.angularDistance(45, 45), 1e-9);
        }

        @Test
        void computeAngleFromOriginUsesEastZeroSouthNinety() {
            BlockPos o = new BlockPos(0, 0, 0);
            assertEquals(0.0, DirectionUtil.computeAngleFromOrigin(o, new BlockPos(10, 0, 0)), 1e-9, "East = 0");
            assertEquals(90.0, DirectionUtil.computeAngleFromOrigin(o, new BlockPos(0, 0, 10)), 1e-9, "South = 90");
            assertEquals(180.0, DirectionUtil.computeAngleFromOrigin(o, new BlockPos(-10, 0, 0)), 1e-9, "West = 180");
            assertEquals(270.0, DirectionUtil.computeAngleFromOrigin(o, new BlockPos(0, 0, -10)), 1e-9, "North = 270");
        }

        @Test
        void angleBetweenIsZeroAheadNinetyPerpendicularOneEightyBehind() {
            BlockPos o = new BlockPos(0, 0, 0);
            assertEquals(0.0, DirectionUtil.angleBetween(o, new BlockPos(10, 0, 0), Direction.EAST), 1e-9);
            assertEquals(90.0, DirectionUtil.angleBetween(o, new BlockPos(0, 0, 10), Direction.EAST), 1e-9);
            assertEquals(180.0, DirectionUtil.angleBetween(o, new BlockPos(-10, 0, 0), Direction.EAST), 1e-9);
            assertEquals(0.0, DirectionUtil.angleBetween(o, o, Direction.EAST), 1e-9, "zero distance -> 0");
        }

        @Test
        void angularSeparationComposesTheTwoTargetAngles() {
            BlockPos o = new BlockPos(0, 0, 0);
            // East (0°) vs South (90°) as seen from origin -> 90° apart.
            assertEquals(90.0, DirectionUtil.angularSeparation(o, new BlockPos(10, 0, 0), new BlockPos(0, 0, 10)), 1e-9);
        }
    }

    @Nested
    class SegmentAndPerpendicular {
        @Test
        void segmentDirectionPicksTheDominantAxisAndIsNullWhenDiagonal() {
            assertEquals(Direction.EAST, DirectionUtil.getSegmentDirection(new BlockPos(0, 0, 0), new BlockPos(10, 0, 2)));
            assertEquals(Direction.WEST, DirectionUtil.getSegmentDirection(new BlockPos(0, 0, 0), new BlockPos(-10, 0, 2)));
            assertEquals(Direction.SOUTH, DirectionUtil.getSegmentDirection(new BlockPos(0, 0, 0), new BlockPos(2, 0, 10)));
            assertEquals(Direction.NORTH, DirectionUtil.getSegmentDirection(new BlockPos(0, 0, 0), new BlockPos(2, 0, -10)));
            assertNull(DirectionUtil.getSegmentDirection(new BlockPos(0, 0, 0), new BlockPos(5, 0, 5)), "equal deltas -> diagonal -> null");
            assertNull(DirectionUtil.getSegmentDirection(new BlockPos(0, 0, 0), new BlockPos(0, 0, 0)), "zero length -> null");
        }

        @Test
        void arePerpendicularIsTrueOnlyAcrossTheNsEwDivideAndFalseForNulls() {
            assertTrue(DirectionUtil.arePerpendicular(Direction.NORTH, Direction.EAST));
            assertTrue(DirectionUtil.arePerpendicular(Direction.EAST, Direction.SOUTH));
            assertFalse(DirectionUtil.arePerpendicular(Direction.NORTH, Direction.SOUTH), "both N/S -> parallel");
            assertFalse(DirectionUtil.arePerpendicular(Direction.EAST, Direction.WEST), "both E/W -> parallel");
            assertFalse(DirectionUtil.arePerpendicular(null, Direction.EAST), "null -> false");
        }
    }

    @Nested
    class AxisProjectionAndDistance {
        @Test
        void positionAlongAxisReadsTheRightCoordinate() {
            BlockPos p = new BlockPos(3, 5, 7);
            assertEquals(3, DirectionUtil.getPositionAlongAxis(p, Direction.EAST));
            assertEquals(7, DirectionUtil.getPositionAlongAxis(p, Direction.NORTH));
            assertEquals(5, DirectionUtil.getPositionAlongAxis(p, Direction.UP));
        }

        @Test
        void signedPositionNegatesForNegativeAxisDirections() {
            BlockPos p = new BlockPos(5, 0, 7);
            assertEquals(5, DirectionUtil.getSignedPositionAlongAxis(p, Direction.EAST), "positive axis");
            assertEquals(-5, DirectionUtil.getSignedPositionAlongAxis(p, Direction.WEST), "negative axis negates");
            assertEquals(-7, DirectionUtil.getSignedPositionAlongAxis(p, Direction.NORTH));
            assertEquals(7, DirectionUtil.getSignedPositionAlongAxis(p, Direction.SOUTH));
        }

        @Test
        void horizontalDistanceIgnoresY() {
            assertEquals(5.0, DirectionUtil.horizontalDistance(new BlockPos(0, 64, 0), new BlockPos(3, 9999, 4)), 1e-9);
        }

        @Test
        void directionalDistanceIsSignedAlongTheDirection() {
            BlockPos a = new BlockPos(0, 0, 0);
            assertEquals(7, DirectionUtil.calculateDirectionalDistance(a, new BlockPos(7, 0, 0), Direction.EAST));
            assertEquals(10, DirectionUtil.calculateDirectionalDistance(a, new BlockPos(0, 0, 10), Direction.SOUTH));
            assertEquals(-10, DirectionUtil.calculateDirectionalDistance(a, new BlockPos(0, 0, 10), Direction.NORTH));
        }
    }

    @Nested
    class DirectionalPredicates {
        private final BlockPos origin = new BlockPos(0, 0, 0);

        @Test
        void isInDirectionChecksTheRightHalfPlane() {
            assertTrue(DirectionUtil.isInDirection(origin, new BlockPos(0, 0, -5), Direction.NORTH));
            assertFalse(DirectionUtil.isInDirection(origin, new BlockPos(0, 0, 5), Direction.NORTH));
            assertTrue(DirectionUtil.isInDirection(origin, new BlockPos(5, 0, 0), Direction.EAST));
        }

        @Test
        void cardinalRangeRequiresTheDominantAxisToMatchTheDirection() {
            // NORTH: must move -Z and |dz| >= |dx|.
            assertTrue(DirectionUtil.isInCardinalRange(origin, new BlockPos(5, 0, -10), Direction.NORTH));
            assertFalse(DirectionUtil.isInCardinalRange(origin, new BlockPos(15, 0, -10), Direction.NORTH), "too lateral");
            assertFalse(DirectionUtil.isInCardinalRange(origin, new BlockPos(0, 0, 10), Direction.NORTH), "wrong side");
            // isNotBehind delegates to the cardinal range.
            assertTrue(DirectionUtil.isNotBehind(origin, new BlockPos(0, 0, -10), Direction.NORTH));
            assertFalse(DirectionUtil.isNotBehind(origin, new BlockPos(0, 0, 10), Direction.NORTH));
        }

        @Test
        void dominantInDirectionTightensWithStrictness() {
            // NORTH target: 10 forward (−Z), 9 lateral (X). Dominant at strictness 1.0 (dz>=dx) but not at 1.5.
            BlockPos target = new BlockPos(9, 0, -10);
            assertTrue(DirectionUtil.isDominantInDirection(origin, target, Direction.NORTH, 1.0),
                    "10 >= 9·1.0 -> dominant");
            assertFalse(DirectionUtil.isDominantInDirection(origin, target, Direction.NORTH, 1.5),
                    "10 < 9·1.5 -> too lateral at the tighter strictness");
            assertFalse(DirectionUtil.isDominantInDirection(origin, new BlockPos(9, 0, 10), Direction.NORTH, 1.0),
                    "wrong side (target is +Z) -> not in the direction at all");
        }
    }

    @Nested
    class ExplorationTarget {
        @Test
        void theForwardAxisIsDeterministicAndYIsPreserved() {
            BlockPos origin = new BlockPos(100, 64, 200);
            Random rng = new Random(42);
            BlockPos east = DirectionUtil.generateExplorationTarget(origin, Direction.EAST, 300, rng);
            assertEquals(100 + 300, east.getX(), "EAST advances exactly +distance in X");
            assertEquals(64, east.getY(), "Y is unchanged");

            BlockPos north = DirectionUtil.generateExplorationTarget(origin, Direction.NORTH, 300, new Random(42));
            assertEquals(200 - 300, north.getZ(), "NORTH advances exactly -distance in Z");
        }
    }

    @Nested
    class OffsetPosition {
        @Test
        void offsetsByADirectionVectorFromTheBlockCentre() {
            // atCenterOf adds 0.5; +10 along +X; containing() floors -> x=10, y/z carried.
            assertEquals(new BlockPos(10, 64, 0),
                    DirectionUtil.getOffsetPosition(new BlockPos(0, 64, 0), new Vec3(1, 0, 0), 10));
            assertEquals(new BlockPos(0, 64, 4),
                    DirectionUtil.getOffsetPosition(new BlockPos(0, 64, 0), new Vec3(0, 0, 1), 4));
        }
    }

    @Nested
    class ExplorationTargetAvoiding {
        private static final int DIST = 300;
        private final BlockPos origin = new BlockPos(100, 64, 200);

        @Test
        void withNoOtherHeadsReturnsTheForwardTarget() {
            BlockPos t = DirectionUtil.generateExplorationTargetAvoiding(
                    origin, Direction.EAST, DIST, new Random(1), List.of());
            assertEquals(100 + DIST, t.getX(), "EAST target advances +distance in X");
            assertEquals(64, t.getY());
        }

        @Test
        void avoidsAFarHeadByAcceptingTheFirstSeparatedCandidate() {
            // Other head is nowhere near the eastward target zone -> first candidate clears the 200-block
            // separation and is returned immediately.
            BlockPos far = new BlockPos(-10000, 64, -10000);
            BlockPos t = DirectionUtil.generateExplorationTargetAvoiding(
                    origin, Direction.EAST, DIST, new Random(1), List.of(far));
            assertEquals(100 + DIST, t.getX());
            assertTrue(DirectionUtil.horizontalDistance(t, far) >= 200);
        }

        @Test
        void whenEveryCandidateIsTooCloseItFallsBackToTheLeastBad() {
            // A head sitting in the target zone keeps every retried candidate within the 200 separation,
            // so the method exhausts its retries and returns the least-bad (still non-null, still forward).
            BlockPos inZone = new BlockPos(100 + DIST, 64, 200);
            BlockPos t = DirectionUtil.generateExplorationTargetAvoiding(
                    origin, Direction.EAST, DIST, new Random(7), List.of(inZone));
            assertEquals(100 + DIST, t.getX(), "fallback is still a forward exploration target");
            assertTrue(DirectionUtil.horizontalDistance(t, inZone) < 200,
                    "no candidate could clear separation, so the fallback is genuinely close");
        }
    }
}
