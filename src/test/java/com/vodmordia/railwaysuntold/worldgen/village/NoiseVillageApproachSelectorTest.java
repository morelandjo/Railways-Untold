package com.vodmordia.railwaysuntold.worldgen.village;

import com.vodmordia.railwaysuntold.datapack.SelectedStation;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.NbtSchematicLoader;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Characterizes {@link NoiseVillageApproachSelector#selectBestApproach} (the villageCenter overload -
 * sampler + math only, no layout/station/config). The scorer weighs elevation change, terrain
 * smoothness, ocean crossings, and the angle of the approach side vs the head's heading. Pure given a
 * mocked terrain sampler. The flank sides (runway parallel to travel) beat the near side (perpendicular
 * runway, +150 angle penalty); the far side (== heading) is excluded as a candidate entirely.
 */
class NoiseVillageApproachSelectorTest {

    private static final int FLAT_Y = 72;
    // Village at the origin; head 200 blocks WEST -> heading is EAST, so EAST (the far side) is excluded
    // and the candidate sides are NORTH, SOUTH (flanks) and WEST (near side).
    private static final BlockPos VILLAGE = new BlockPos(0, FLAT_Y, 0);
    private static final BlockPos HEAD_WEST = new BlockPos(-200, FLAT_Y, 0);

    private interface Height { int at(int x, int z); }
    private interface Ocean { boolean at(int x, int z); }

    private static NoiseTerrainSampler sampler(Height h, Ocean o) {
        NoiseTerrainSampler s = mock(NoiseTerrainSampler.class);
        when(s.getBaseHeight(anyInt(), anyInt()))
                .thenAnswer(inv -> h.at(inv.getArgument(0), inv.getArgument(1)));
        when(s.isLikelyOcean(anyInt(), anyInt()))
                .thenAnswer(inv -> o.at(inv.getArgument(0), inv.getArgument(1)));
        return s;
    }

    @Test
    void onFlatTerrainTheHeadApproachesFromAFlankNotTheNearOrFarSide() {
        NoiseTerrainSampler flat = sampler((x, z) -> FLAT_Y, (x, z) -> false);

        Direction approach = NoiseVillageApproachSelector.selectBestApproach(flat, VILLAGE, HEAD_WEST, FLAT_Y);

        // Flank = runway parallel to the head's EAST travel; the only flanks here are NORTH/SOUTH.
        assertTrue(approach == Direction.NORTH || approach == Direction.SOUTH,
                "expected a flank approach (N/S), got " + approach);
        assertNotEquals(Direction.WEST, approach, "near side (perpendicular runway) should lose to a flank");
        assertNotEquals(Direction.EAST, approach, "far side is excluded as a candidate");
    }

    @Test
    void anOceanCrossingFlankIsRejectedInFavourOfTheDryFlank() {
        // Ocean everywhere NORTH of the village (z < -40); the NORTH approach samples into it.
        NoiseTerrainSampler oceanNorth = sampler((x, z) -> FLAT_Y, (x, z) -> z < -40);

        Direction approach = NoiseVillageApproachSelector.selectBestApproach(oceanNorth, VILLAGE, HEAD_WEST, FLAT_Y);

        // SOUTH (dry flank, score 0) beats NORTH (dry-flank score + 50 water penalty) and WEST (+150 angle).
        assertEquals(Direction.SOUTH, approach, "should approach from the dry flank, avoiding the ocean");
    }

    @Test
    void aSteepFlankLosesToTheFlatFlank() {
        // Tall ridge SOUTH of the village (z > 40) -> the SOUTH approach has a large elevation delta;
        // NORTH stays flat. Elevation is weighted 3x, so the flat NORTH flank wins.
        NoiseTerrainSampler ridgeSouth = sampler((x, z) -> z > 40 ? FLAT_Y + 50 : FLAT_Y, (x, z) -> false);

        Direction approach = NoiseVillageApproachSelector.selectBestApproach(ridgeSouth, VILLAGE, HEAD_WEST, FLAT_Y);

        assertEquals(Direction.NORTH, approach, "should approach from the flat flank, not over the ridge");
    }

    // --- selectRankedApproaches: the layout + station path (edge sampling, reachability/route-slope
    //     penalties, and the Lever-1 radial-arrival drop) ---

    /** A small station whose schematic dimensions feed the radial-arrival tangency probe. */
    private static SelectedStation smallStation() {
        NbtSchematicLoader.LoadedSchematic schem = mock(NbtSchematicLoader.LoadedSchematic.class);
        when(schem.getWidth()).thenReturn(8);
        when(schem.getLength()).thenReturn(8);
        return new SelectedStation(null, schem, null, List.of());
    }

    private static PredictedVillageLayout flatVillageLayout() {
        // A 64-wide village centred on the origin (Y 60..80).
        BoundingBox bounds = new BoundingBox(-32, 60, -32, 32, 80, 32);
        return PredictedVillageLayout.create(new ChunkPos(0, 0), bounds, List.of());
    }

    @Test
    void rankedApproachesExcludeTheFarSideAndReturnSaneDistinctSides() {
        NoiseTerrainSampler flat = sampler((x, z) -> FLAT_Y, (x, z) -> false);

        List<Direction> ranked = NoiseVillageApproachSelector.selectRankedApproaches(
                flat, flatVillageLayout(), HEAD_WEST, FLAT_Y, smallStation());

        // Heading is EAST, so the far side (EAST) is never a candidate; viable sides come from {N,S,W}.
        assertFalse(ranked.isEmpty(), "flat terrain + small station should leave at least one viable side");
        assertFalse(ranked.contains(Direction.EAST), "the far side (== heading) must not be ranked");
        for (Direction d : ranked) {
            assertTrue(Direction.Plane.HORIZONTAL.test(d), "ranked side must be horizontal: " + d);
        }
        assertEquals(ranked.size(), ranked.stream().distinct().count(), "ranked sides must be distinct");
        // The best (first) viable side is a flank - the parallel-runway sides beat the near side.
        assertTrue(ranked.get(0) == Direction.NORTH || ranked.get(0) == Direction.SOUTH,
                "top-ranked approach should be a flank, got " + ranked.get(0));
    }

    @Test
    void rankedApproachesAvoidAnOceanFlankWhenADryFlankExists() {
        NoiseTerrainSampler oceanNorth = sampler((x, z) -> FLAT_Y, (x, z) -> z < -40);

        List<Direction> ranked = NoiseVillageApproachSelector.selectRankedApproaches(
                oceanNorth, flatVillageLayout(), HEAD_WEST, FLAT_Y, smallStation());

        assertFalse(ranked.isEmpty(), "a dry flank remains viable");
        // The dry flank (SOUTH) outranks the ocean-crossing flank (NORTH).
        assertTrue(ranked.indexOf(Direction.SOUTH) >= 0, "the dry SOUTH flank should be viable");
        if (ranked.contains(Direction.NORTH)) {
            assertTrue(ranked.indexOf(Direction.SOUTH) < ranked.indexOf(Direction.NORTH),
                    "dry flank should outrank the ocean flank: " + ranked);
        }
    }
}
