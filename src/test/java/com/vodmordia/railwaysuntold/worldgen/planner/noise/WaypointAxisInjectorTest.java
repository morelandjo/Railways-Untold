package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.track.SlopeValidator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterizes {@link WaypointAxisInjector} - the cardinal-axis anchor insertion at a coarse route's
 * ends. Uses EAST throughout (axis = X). The anchor distance is config-derived, so it is recomputed here
 * from the same constants rather than hard-coded. Config defaults are bootstrapped for the read.
 */
class WaypointAxisInjectorTest {

    @BeforeAll
    static void bootstrap() {
        PlannerTestConfig.bootstrapDefaults();
    }

    private static int anchorDistance() {
        return Math.max(CoarseRoutePlanner.SAMPLE_INTERVAL,
                RailwaysUntoldConfig.getMinCurveRadius() + SlopeValidator.STRAIGHT_ENDPOINT_BLOCKS);
    }

    @Test
    void forwardPrefixInsertsAnOnAxisWaypointForAnOffAxisPath() {
        int dist = anchorDistance();
        BlockPos start = new BlockPos(0, 64, 0);
        List<BlockPos> path = List.of(start, new BlockPos(50, 64, 30)); // second point is off the EAST axis
        List<BlockPos> result = WaypointAxisInjector.injectForwardAxisPrefix(path, start, Direction.EAST);

        assertEquals(3, result.size());
        assertEquals(start, result.get(0));
        assertEquals(new BlockPos(dist, 64, 0), result.get(1)); // on-axis ahead of the tip
        assertEquals(new BlockPos(50, 64, 30), result.get(2));
    }

    @Test
    void forwardPrefixIsANoOpWhenThePathAlreadyRunsStraightAhead() {
        BlockPos start = new BlockPos(0, 64, 0);
        // Second point is on the EAST axis and well past the anchor distance.
        List<BlockPos> path = List.of(start, new BlockPos(1000, 64, 0));
        List<BlockPos> result = WaypointAxisInjector.injectForwardAxisPrefix(path, start, Direction.EAST);

        assertSame(path, result);
    }

    @Test
    void arrivalSuffixInsertsAnOnAxisWaypointBehindTheTarget() {
        int dist = anchorDistance();
        BlockPos target = new BlockPos(100, 64, 0);
        // Second-to-last point is off the EAST arrival axis, so the suffix is inserted.
        List<BlockPos> path = List.of(new BlockPos(0, 64, 30), target);
        List<BlockPos> result = WaypointAxisInjector.injectArrivalAxisSuffix(path, target, Direction.EAST);

        assertEquals(3, result.size());
        assertEquals(new BlockPos(0, 64, 30), result.get(0));
        assertEquals(new BlockPos(100 - dist, 64, 0), result.get(1)); // on-axis behind the target
        assertEquals(target, result.get(2));
    }

    @Test
    void arrivalSuffixIsANoOpWhenTheTailAlreadyLandsOnAxis() {
        BlockPos target = new BlockPos(100, 64, 0);
        List<BlockPos> path = List.of(new BlockPos(0, 64, 0), target); // straight along EAST into the target
        List<BlockPos> result = WaypointAxisInjector.injectArrivalAxisSuffix(path, target, Direction.EAST);

        assertSame(path, result);
    }

    @Test
    void arrivalSuffixIsANoOpForASingleWaypointPath() {
        BlockPos target = new BlockPos(100, 64, 0);
        List<BlockPos> path = List.of(target);
        assertSame(path, WaypointAxisInjector.injectArrivalAxisSuffix(path, target, Direction.EAST));
    }

    // A village body occupying x in [380, 454], z in [-57, 80]. Used to check that the runway start backs
    // off past the body's extent so the tangent runway into the arrival lies alongside the body, not inside it.
    private static final BoundingBox BODY = new BoundingBox(380, 60, -57, 454, 75, 80);

    @Test
    void runwayStartBacksWestPastTheBodyForAnEastArrival() {
        // North-side arrival, runway heading EAST: the runway start must be west of the body's west edge so
        // the east-bound tangent run is north of the body (at the arrival's z) the whole way in.
        BlockPos arrival = new BlockPos(417, 64, -73);
        BlockPos r = WaypointAxisInjector.runwayStartClearingBody(arrival, Direction.EAST, BODY, 8);
        assertEquals(-73, r.getZ(), "runway start stays on the arrival axis (the tangent line)");
        assertTrue(r.getX() <= BODY.minX() - 8, "runway start is at least margin west of the body's west edge");
    }

    @Test
    void runwayStartBacksSouthPastTheBodyForANorthArrival() {
        // South-side arrival, runway heading NORTH: the runway start is south of the body's south edge.
        BlockPos arrival = new BlockPos(417, 64, 96);
        BlockPos r = WaypointAxisInjector.runwayStartClearingBody(arrival, Direction.NORTH, BODY, 8);
        assertEquals(417, r.getX(), "runway start stays on the arrival axis (the tangent line)");
        assertTrue(r.getZ() >= BODY.maxZ() + 8, "runway start is at least margin south of the body's south edge");
    }

    @Test
    void runwayStartFallsBackToAnchorDistanceWhenTheBodyIsShallowAlongTheAxis() {
        // Body's west edge is at the arrival's X, so it adds no back-extent; the anchor distance floors it.
        BoundingBox shallow = new BoundingBox(417, 60, -57, 419, 75, 80);
        BlockPos arrival = new BlockPos(417, 64, -73);
        BlockPos r = WaypointAxisInjector.runwayStartClearingBody(arrival, Direction.EAST, shallow, 8);
        assertEquals(417 - anchorDistance(), r.getX(), "shallow body -> anchor-distance floor");
        assertEquals(-73, r.getZ());
    }
}
