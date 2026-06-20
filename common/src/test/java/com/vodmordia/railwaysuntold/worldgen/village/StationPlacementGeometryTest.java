package com.vodmordia.railwaysuntold.worldgen.village;

import com.vodmordia.railwaysuntold.datapack.SelectedStation;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.NbtSchematicLoader;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.SchematicValidator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Characterizes {@link StationPlacementGeometry}: the pure footprint/track math that places a station
 * schematic for a given runway pose. All cases use an EAST-running 10x5 schematic with a perpendicular
 * track offset of 2 and the runway heading EAST (rotation NONE), which keeps the world transform
 * additive and the expected coordinates hand-checkable.
 */
class StationPlacementGeometryTest {

    private static final int WIDTH = 10;   // schematic X extent (track runs along X)
    private static final int LENGTH = 5;   // schematic Z extent
    private static final int PERP_OFFSET = 2;
    private static final int TRACK_Y = 0;

    private static SelectedStation eastStation() {
        NbtSchematicLoader.LoadedSchematic schem = mock(NbtSchematicLoader.LoadedSchematic.class);
        when(schem.getWidth()).thenReturn(WIDTH);
        when(schem.getLength()).thenReturn(LENGTH);
        when(schem.getSize()).thenReturn(new Vec3i(WIDTH, 5, LENGTH));
        SchematicValidator.SchematicValidationResult validation =
                SchematicValidator.SchematicValidationResult.success(Direction.EAST, TRACK_Y, WIDTH, PERP_OFFSET);
        return new SelectedStation(null, schem, validation, List.of());
    }

    @Test
    void placementPositionCentersOnRunwayAlongTheTrackAxis() {
        SelectedStation station = eastStation();
        // halfTrack = (WIDTH-1)/2 = 4 shifts X back; perpOffset shifts Z back.
        BlockPos pos = StationPlacementGeometry.calculateStationPlacementPosition(
                new BlockPos(100, 64, 200), Rotation.NONE, station.validation(), station.schematic());
        assertEquals(new BlockPos(96, 64, 198), pos);
    }

    @Test
    void placementPositionWithoutCenteringAlignsOriginToRunway() {
        SelectedStation station = eastStation();
        // centerOnRunway=false drops the halfTrack shift; only the perpendicular offset remains.
        BlockPos pos = StationPlacementGeometry.calculateStationPlacementPosition(
                new BlockPos(100, 64, 200), Rotation.NONE, station.validation(), station.schematic(), false);
        assertEquals(new BlockPos(100, 64, 198), pos);
    }

    @Test
    void trackEndpointsSpanTheCenteredFootprintAlongTheRunway() {
        SelectedStation station = eastStation();
        StationPlacementGeometry.TrackEndpoints ends =
                StationPlacementGeometry.calculateTrackEndpoints(station, new BlockPos(100, 64, 200), Direction.EAST);
        assertEquals(new BlockPos(96, 64, 200), ends.trackStart());
        assertEquals(new BlockPos(105, 64, 200), ends.trackEnd());
        assertEquals(Direction.EAST, ends.trackDirection());
    }

    @Test
    void entryPointPicksTheEndNearerTheApproach() {
        StationPlacementGeometry.TrackEndpoints ends = new StationPlacementGeometry.TrackEndpoints(
                new BlockPos(96, 64, 200), new BlockPos(105, 64, 200), Direction.EAST);
        assertEquals(new BlockPos(96, 64, 200), ends.getEntryPoint(new BlockPos(90, 64, 200)));
        assertEquals(new BlockPos(105, 64, 200), ends.getEntryPoint(new BlockPos(120, 64, 200)));
    }

    @Test
    void offsetEntryExitStepsOneBlockOutsideEachTrackEnd() {
        StationPlacementGeometry.TrackEndpoints ends = new StationPlacementGeometry.TrackEndpoints(
                new BlockPos(96, 64, 200), new BlockPos(105, 64, 200), Direction.EAST);
        StationPlacementGeometry.EntryExitPoints ee = ends.getOffsetEntryExit(new BlockPos(90, 64, 200));
        assertEquals(new BlockPos(95, 64, 200), ee.entry());   // one west of trackStart
        assertEquals(new BlockPos(106, 64, 200), ee.exit());   // one east of trackEnd
        assertEquals(Direction.EAST, ee.internalDir());
    }

    @Test
    void travelDirectionIsPositiveWhenExitIsFurtherAlongTheAxis() {
        assertTrue(StationPlacementGeometry.calculateTravelDirectionPositive(
                new BlockPos(95, 64, 200), new BlockPos(106, 64, 200), Direction.EAST));
        assertFalse(StationPlacementGeometry.calculateTravelDirectionPositive(
                new BlockPos(106, 64, 200), new BlockPos(95, 64, 200), Direction.EAST));
    }

    @Test
    void collisionDetectsAPieceOverlappingTheCenteredFootprint() {
        SelectedStation station = eastStation();
        // Footprint (margin 0): X[96..105], Z[198..202]. This piece sits inside it.
        List<BoundingBox> overlapping = List.of(new BoundingBox(100, 0, 200, 101, 16, 201));
        assertTrue(StationPlacementGeometry.wouldCollideWithVillagePieces(
                new BlockPos(100, 64, 200), Direction.EAST, station, overlapping, 0));
    }

    @Test
    void collisionIgnoresAPieceOutsideTheFootprintAndMargin() {
        SelectedStation station = eastStation();
        List<BoundingBox> faraway = List.of(new BoundingBox(500, 0, 500, 510, 16, 510));
        assertFalse(StationPlacementGeometry.wouldCollideWithVillagePieces(
                new BlockPos(100, 64, 200), Direction.EAST, station, faraway, 0));
    }

    @Test
    void collisionShortCircuitsOnEmptyPieceList() {
        SelectedStation station = eastStation();
        assertFalse(StationPlacementGeometry.wouldCollideWithVillagePieces(
                new BlockPos(100, 64, 200), Direction.EAST, station, List.of(), 4));
    }
}
