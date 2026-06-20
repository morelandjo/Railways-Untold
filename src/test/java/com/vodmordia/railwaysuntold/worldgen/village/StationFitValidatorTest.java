package com.vodmordia.railwaysuntold.worldgen.village;

import com.vodmordia.railwaysuntold.datapack.SelectedStation;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.NbtSchematicLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Characterizes {@link StationFitValidator}: the compile-time check that a route's final pose is a
 * usable station site - near enough to the village (proximity), and with the runway exit tangent
 * rather than radial (pointing into the village body). The collision criterion is only evaluated when
 * the layout has piece bounds, so an empty-pieces layout keeps this a pure geometry test.
 */
class StationFitValidatorTest {

    // 64-wide village centred on the origin, Y 60..80, with NO piece bounds (skips the collision check).
    private static PredictedVillageLayout layout() {
        BoundingBox bounds = new BoundingBox(-32, 60, -32, 32, 80, 32);
        return PredictedVillageLayout.create(new ChunkPos(0, 0), bounds, List.of());
    }

    private static SelectedStation station() {
        NbtSchematicLoader.LoadedSchematic schem = mock(NbtSchematicLoader.LoadedSchematic.class);
        when(schem.getWidth()).thenReturn(8);
        when(schem.getLength()).thenReturn(8);
        return new SelectedStation(null, schem, null, List.of());
    }

    @Test
    void aPoseJustOutsideTheEdgeFacingAwayIsAccepted() {
        // 8 blocks east of the east edge, facing further EAST (away from the village body).
        StationFitValidator.Result result = StationFitValidator.validate(
                new BlockPos(40, 72, 0), Direction.EAST, layout(), station());
        assertTrue(result.ok(), "should accept a near, tangent pose; reason=" + result.reason());
    }

    @Test
    void aPoseFarFromTheVillageIsRejectedForProximity() {
        StationFitValidator.Result result = StationFitValidator.validate(
                new BlockPos(200, 72, 0), Direction.EAST, layout(), station());
        assertFalse(result.ok(), "a pose 168 blocks out should be rejected");
        assertTrue(result.reason().contains("village envelope"), "reason should cite proximity: " + result.reason());
    }

    @Test
    void aRunwayPointingIntoTheVillageIsRejectedAsRadial() {
        // Just west of the west edge, but facing EAST - the exit probe extends into the village body.
        StationFitValidator.Result result = StationFitValidator.validate(
                new BlockPos(-40, 72, 0), Direction.EAST, layout(), station());
        assertFalse(result.ok(), "a radial runway (exit into village) must be rejected");
        assertTrue(result.reason().contains("radial"), "reason should cite radial arrival: " + result.reason());
    }

    @Test
    void isRadialArrivalDistinguishesInwardFromOutwardRunways() {
        PredictedVillageLayout layout = layout();
        SelectedStation station = station();
        // Outside the west edge facing EAST -> exit probe lands inside the village -> radial.
        assertTrue(StationFitValidator.isRadialArrival(new BlockPos(-40, 72, 0), Direction.EAST, layout, station),
                "facing into the village is radial");
        // Same spot facing WEST (away) -> probe stays outside -> not radial.
        assertFalse(StationFitValidator.isRadialArrival(new BlockPos(-40, 72, 0), Direction.WEST, layout, station),
                "facing away from the village is tangent, not radial");
    }
}
