package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.planner.PathSegment;
import com.vodmordia.railwaysuntold.worldgen.planner.PlannedPath;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.CoarseWaypoint;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoutePlanner.PlanResult;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseStructureScanner.PredictedStructure;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainSampler;
import com.vodmordia.railwaysuntold.worldgen.village.PredictedVillageLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the village-approach skirt: a head whose straight line to the arrival pose runs through the village
 * body must reach the arrival from outside the body, not drive through it. This is the failure that abandons
 * the village at commit (station footprint inside the pieces) and reverses the head onto its own approach curve.
 *
 * The head sits due west of the village on its center latitude, with an EAST-flank arrival - so the straight
 * start->arrival line runs east straight through the body. The fix (route to a runway start backed off past
 * the body + the body injected as a footprint obstacle) makes the route skirt the body and come onto the
 * runway from outside. Without it the route would place waypoints inside the body, so the "nothing inside the
 * body" assertions fail before the fix and pass after.
 */
class VillageApproachSkirtTest {

    private static final UUID HEAD_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    // Village body across the corridor: x in [380, 454], z in [-57, 80], center latitude z = 11.
    private static final BoundingBox BODY = new BoundingBox(380, 60, -57, 454, 75, 80);
    // East-flank arrival: 16 blocks east of the body's east edge, at the body's center latitude.
    private static final BlockPos ARRIVAL = new BlockPos(470, 64, 11);

    @BeforeAll
    static void setUp() {
        PlannerTestConfig.bootstrapDefaults();
    }

    @Test
    void approachReachesTheFlankArrivalFromOutsideTheBody() {
        NoiseTerrainSampler sampler = StubTerrainSampler.flat(64);
        // Due west of the village, on its center latitude: the straight east line to the arrival runs
        // through the body.
        BlockPos start = new BlockPos(104, 64, 11);

        PredictedVillageLayout layout =
                PredictedVillageLayout.create(new ChunkPos(ARRIVAL), BODY, List.of(BODY));
        // The target village body, injected as a footprint obstacle exactly as CoarseRouteFactory does.
        PredictedStructure bodyObstacle = new PredictedStructure(
                new ChunkPos(ARRIVAL), new BlockPos(417, 64, 11),
                "target_village_skirt", List.of(), false, List.of(BODY));

        // villageStation = null skips the station-fit check; this exercises the routing/skirt only.
        PlanResult result = CoarseRoutePlanner.planRouteOffThread(
                sampler, start, ARRIVAL, HEAD_ID, Direction.EAST, Direction.NORTH,
                List.of(bodyObstacle), null, List.of(), -1,
                layout, null, List.of(), false);

        PlannedPath path = result.route().getPrecisionPath();
        assertTrue(path.valid, "route invalid: " + path.invalidReason);

        // No coarse waypoint sits strictly inside the village body footprint.
        for (CoarseWaypoint wp : result.route().getCoarseRoute().getWaypoints()) {
            assertFalse(strictlyInside(wp.position()),
                    "coarse waypoint " + wp.position() + " is inside the village body " + BODY);
        }

        // No compiled segment endpoint sits inside the body either.
        for (PathSegment seg : path.segments) {
            assertFalse(strictlyInside(seg.getStart()), "segment start inside body: " + seg.getStart());
            assertFalse(strictlyInside(seg.getEndPosition()), "segment end inside body: " + seg.getEndPosition());
        }

        // The compiled path ends at the arrival, outside the body.
        assertFalse(strictlyInside(path.finalPosition),
                "final pose " + path.finalPosition + " is inside the body");
    }

    private static boolean strictlyInside(BlockPos p) {
        return p.getX() > BODY.minX() && p.getX() < BODY.maxX()
                && p.getZ() > BODY.minZ() && p.getZ() < BODY.maxZ();
    }
}
