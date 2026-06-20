package com.vodmordia.railwaysuntold.worldgen.village;

import com.vodmordia.railwaysuntold.datapack.SelectedStation;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.NbtSchematicLoader;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.SchematicValidator;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Characterizes {@link NoiseStationPlanner#planStation} / {@code planStationWithAlternatives}: it ranks
 * the village's approach sides ({@link NoiseVillageApproachSelector}) and turns each into an arrival
 * pose - the edge midpoint pushed 16 blocks outward, facing a tangent (perpendicular to the approach
 * side, away from the head). Pure given a mocked sampler + layout + station.
 */
class NoiseStationPlannerTest {

    private static final int FLAT_Y = 72;
    private static final BlockPos HEAD_WEST = new BlockPos(-200, FLAT_Y, 0);

    private static NoiseTerrainSampler flatSampler() {
        NoiseTerrainSampler s = mock(NoiseTerrainSampler.class);
        when(s.getBaseHeight(anyInt(), anyInt())).thenReturn(FLAT_Y);
        return s; // isLikelyOcean defaults to false
    }

    private static SelectedStation smallStation() {
        NbtSchematicLoader.LoadedSchematic schem = mock(NbtSchematicLoader.LoadedSchematic.class);
        when(schem.getWidth()).thenReturn(8);
        when(schem.getLength()).thenReturn(8);
        return new SelectedStation(null, schem, null, List.of());
    }

    // 64-wide village centred on the origin, Y 60..80.
    private static PredictedVillageLayout flatVillageLayout() {
        BoundingBox bounds = new BoundingBox(-32, 60, -32, 32, 80, 32);
        return PredictedVillageLayout.create(new ChunkPos(0, 0), bounds, List.of());
    }

    /** Safety margin the planner uses when rejecting colliding approaches. */
    private static final int STATION_COLLISION_MARGIN = 5;

    // A 10x5 station with a real validation, so it can be driven through the collision footprint math
    // (the no-validation smallStation above short-circuits only on empty piece lists).
    private static SelectedStation collisionStation() {
        NbtSchematicLoader.LoadedSchematic schem = mock(NbtSchematicLoader.LoadedSchematic.class);
        when(schem.getWidth()).thenReturn(10);
        when(schem.getLength()).thenReturn(5);
        when(schem.getSize()).thenReturn(new Vec3i(10, 5, 5));
        SchematicValidator.SchematicValidationResult validation =
                SchematicValidator.SchematicValidationResult.success(Direction.EAST, 0, 10, 0);
        return new SelectedStation(null, schem, validation, List.of());
    }

    private static boolean collides(StationPlan plan, SelectedStation station, List<BoundingBox> pieces) {
        return StationPlacementGeometry.wouldCollideWithVillagePieces(
                plan.arrivalPos(), plan.arrivalDir(), station, pieces, STATION_COLLISION_MARGIN);
    }

    @Test
    void planStationReturnsAnArrivalPoseOutsideTheVillageFacingATangent() {
        PredictedVillageLayout layout = flatVillageLayout();
        StationPlan plan = NoiseStationPlanner.planStation(
                flatSampler(), layout, HEAD_WEST, Direction.EAST, FLAT_Y, smallStation());

        assertNotNull(plan, "flat terrain should yield a viable station plan");

        // The head heads EAST and the best approach is a flank (N/S), so the runway tangent runs E-W;
        // the tangent points away from the head (which is far WEST) -> EAST.
        assertEquals(Direction.EAST, plan.arrivalDir(),
                "arrival should face the tangent pointing away from the head, got " + plan.arrivalDir());

        // The arrival pose sits outside the village envelope (pushed 16 blocks past the chosen edge).
        BoundingBox b = layout.totalBounds();
        boolean outside = plan.arrivalPos().getX() < b.minX() || plan.arrivalPos().getX() > b.maxX()
                || plan.arrivalPos().getZ() < b.minZ() || plan.arrivalPos().getZ() > b.maxZ();
        assertTrue(outside, "arrival pos should be outside the village bounds: " + plan.arrivalPos());

        // Y is carried from the (flat) sampler.
        assertEquals(FLAT_Y, plan.arrivalPos().getY());
    }

    @Test
    void planStationWithAlternativesIsRankedAndHeadedByTheBestPlan() {
        PredictedVillageLayout layout = flatVillageLayout();
        List<StationPlan> plans = NoiseStationPlanner.planStationWithAlternatives(
                flatSampler(), layout, HEAD_WEST, Direction.EAST, FLAT_Y, smallStation());

        assertTrue(plans.size() >= 1, "expected at least one viable plan");
        StationPlan best = NoiseStationPlanner.planStation(
                flatSampler(), layout, HEAD_WEST, Direction.EAST, FLAT_Y, smallStation());
        assertEquals(best.arrivalPos(), plans.get(0).arrivalPos(),
                "planStation should return the top-ranked alternative");
        assertEquals(best.arrivalDir(), plans.get(0).arrivalDir());
    }

    @Test
    void buildabilityFilterDropsApproachesThatCollideWithVillagePieces() {
        SelectedStation station = collisionStation();
        BoundingBox bounds = new BoundingBox(-32, 60, -32, 32, 80, 32);

        // Baseline with no village pieces: every ranked approach is offered.
        PredictedVillageLayout clear = PredictedVillageLayout.create(new ChunkPos(0, 0), bounds, List.of());
        List<StationPlan> baseline = NoiseStationPlanner.planStationWithAlternatives(
                flatSampler(), clear, HEAD_WEST, Direction.EAST, FLAT_Y, station);
        assertTrue(baseline.size() >= 2, "need at least one alternative to fall back to, got " + baseline.size());

        // Drop one village piece onto the top-ranked approach's arrival pose. It sits far from every
        // other approach pose (the sides are 48+ blocks apart), so exactly that one approach now
        // collides. The collision function itself is the oracle, so the assertions don't depend on the
        // exact station footprint math.
        BlockPos blockedPose = baseline.get(0).arrivalPos();
        List<BoundingBox> piece = List.of(new BoundingBox(
                blockedPose.getX() - 2, 60, blockedPose.getZ() - 2,
                blockedPose.getX() + 2, 80, blockedPose.getZ() + 2));
        assertTrue(collides(baseline.get(0), station, piece), "test setup: the blocked approach must collide");

        PredictedVillageLayout blocked = PredictedVillageLayout.create(new ChunkPos(0, 0), bounds, piece);
        List<StationPlan> filtered = NoiseStationPlanner.planStationWithAlternatives(
                flatSampler(), blocked, HEAD_WEST, Direction.EAST, FLAT_Y, station);

        assertFalse(filtered.isEmpty(), "clear approaches remain, so a plan must still be returned");
        assertTrue(filtered.size() < baseline.size(), "the colliding approach should have been dropped");
        for (StationPlan plan : filtered) {
            assertFalse(collides(plan, station, piece),
                    "filter must drop colliding approaches, but kept " + plan.arrivalPos());
        }
    }
}
