package com.vodmordia.railwaysuntold.worldgen.survey.extractor;

import com.vodmordia.railwaysuntold.worldgen.village.PredictedVillageLayout;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The surveyed layout is the bridge between ground-truth extraction and the existing consumers, so it
 * must round-trip through NBT losslessly and adapt to PredictedVillageLayout with its exact piece boxes
 * intact. Pure (no ServerLevel); runs on both branches.
 */
class SurveyedStructureLayoutTest {

    private static SurveyedStructureLayout sample() {
        BoundingBox total = new BoundingBox(-832, 60, -320, -816, 95, -305);
        List<BoundingBox> pieces = List.of(
                new BoundingBox(-830, 62, -318, -820, 70, -310),
                new BoundingBox(-825, 64, -312, -818, 72, -306));
        return new SurveyedStructureLayout(new ChunkPos(-52, -20), total, pieces, true);
    }

    private static void assertBoxEquals(BoundingBox expected, BoundingBox actual) {
        assertEquals(expected.minX(), actual.minX());
        assertEquals(expected.minY(), actual.minY());
        assertEquals(expected.minZ(), actual.minZ());
        assertEquals(expected.maxX(), actual.maxX());
        assertEquals(expected.maxY(), actual.maxY());
        assertEquals(expected.maxZ(), actual.maxZ());
    }

    @Test
    void roundTripsThroughNbt() {
        SurveyedStructureLayout original = sample();
        SurveyedStructureLayout parsed = SurveyedStructureLayout.fromNbt(original.toNbt());

        assertEquals(original.structureChunk(), parsed.structureChunk());
        assertEquals(original.isVillage(), parsed.isVillage());
        assertBoxEquals(original.totalBounds(), parsed.totalBounds());
        assertEquals(original.pieceBounds().size(), parsed.pieceBounds().size());
        for (int i = 0; i < original.pieceBounds().size(); i++) {
            assertBoxEquals(original.pieceBounds().get(i), parsed.pieceBounds().get(i));
        }
    }

    @Test
    void asPredictedLayoutPreservesExactPieceBounds() {
        SurveyedStructureLayout surveyed = sample();
        PredictedVillageLayout layout = surveyed.asPredictedLayout();

        assertBoxEquals(surveyed.totalBounds(), layout.totalBounds());
        assertEquals(surveyed.pieceBounds().size(), layout.pieceBounds().size());
        for (int i = 0; i < surveyed.pieceBounds().size(); i++) {
            assertBoxEquals(surveyed.pieceBounds().get(i), layout.pieceBounds().get(i));
        }
        // Center is the centroid of the total bounds (matches PredictedVillageLayout.create).
        assertEquals((-832 + -816) / 2, layout.actualCenter().getX());
        assertEquals((-320 + -305) / 2, layout.actualCenter().getZ());
    }
}
