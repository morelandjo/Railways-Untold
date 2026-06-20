package com.vodmordia.railwaysuntold.worldgen.village;

import com.vodmordia.railwaysuntold.util.chunk.ChunkBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterizes the pure logic in the small village records: {@link StationPlan}'s int-array round-trip,
 * {@link VillageInfo}'s deterministic region-based ID (the invariant the network planner's dedup relies on),
 * and {@link PredictedVillageLayout#create}'s derived chunk bounds + centroid. World/config-free.
 */
class VillageRecordsTest {

    @Nested
    class StationPlanSerialization {
        @Test
        void intArrayRoundTripsThePoseExactly() {
            StationPlan plan = StationPlan.of(new BlockPos(10, 64, -20), Direction.EAST);
            StationPlan restored = StationPlan.fromIntArray(plan.toIntArray());
            assertEquals(plan, restored, "pose survives the int-array round trip");
            assertEquals(new BlockPos(10, 64, -20), restored.arrivalPos());
            assertEquals(Direction.EAST, restored.arrivalDir());
        }

        @Test
        void fromIntArrayRejectsMissingOrTruncatedData() {
            assertNull(StationPlan.fromIntArray(null), "null -> null");
            assertNull(StationPlan.fromIntArray(new int[]{1, 2, 3}), "fewer than 4 ints -> null (old/incompatible)");
        }
    }

    @Nested
    class VillageIdentity {
        // Explicit spacing so the region size is deterministic: regionBlockSize = spacing * 16 = 160.
        private static VillageInfo at(int x, int z, String set) {
            return new VillageInfo(new BlockPos(x, 64, z), "plains", 10, set);
        }

        @Test
        void twoPositionsInTheSameStructureRegionGetTheSameId() {
            // floorDiv(50,160) == floorDiv(100,160) == 0 -> same region -> same UUID.
            assertEquals(at(50, 50, "village").villageId, at(100, 100, "village").villageId,
                    "same region + same structure set -> identical deterministic ID");
        }

        @Test
        void positionsInDifferentRegionsGetDifferentIds() {
            // floorDiv(200,160) == 1 -> a different region.
            assertNotEquals(at(50, 50, "village").villageId, at(200, 200, "village").villageId);
        }

        @Test
        void theStructureSetNameDistinguishesIds() {
            assertNotEquals(at(50, 50, "village").villageId, at(50, 50, "fortress").villageId,
                    "different structure set at the same spot -> different ID");
        }

        @Test
        void theIdIgnoresYAndIsPurelyHorizontal() {
            VillageInfo low = new VillageInfo(new BlockPos(50, 1, 50), "plains", 10, "village");
            VillageInfo high = new VillageInfo(new BlockPos(50, 250, 50), "plains", 10, "village");
            assertEquals(low.villageId, high.villageId, "Y plays no part in the region ID");
        }
    }

    @Nested
    class LayoutCreation {
        @Test
        void createDerivesChunkBoundsAndTheCentroidFromTheTotalBounds() {
            BoundingBox total = new BoundingBox(32, 60, 48, 95, 80, 111);
            PredictedVillageLayout layout = PredictedVillageLayout.create(
                    new ChunkPos(2, 3), total, List.of());

            ChunkBounds cb = layout.chunkBounds();
            assertEquals(2, cb.minChunkX(), "32 >> 4");
            assertEquals(5, cb.maxChunkX(), "95 >> 4");
            assertEquals(3, cb.minChunkZ(), "48 >> 4");
            assertEquals(6, cb.maxChunkZ(), "111 >> 4");

            // Centroid is the integer midpoint of each axis.
            assertEquals(new BlockPos((32 + 95) / 2, (60 + 80) / 2, (48 + 111) / 2), layout.actualCenter());
        }
    }
}
