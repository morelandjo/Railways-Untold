package com.vodmordia.railwaysuntold.worldgen.train;

import com.vodmordia.railwaysuntold.worldgen.train.TrainValidationData.BogeyInfo;
import com.vodmordia.railwaysuntold.worldgen.train.TrainValidationData.CarriageContraptionData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Characterizes {@link CarriageGeometryCalculator} - the pure geometry/layout math pulled out of
 * TrainSchematicValidator. All cases use the EAST assembly direction (axis = X), so positions along the
 * axis are just the X coordinate. Create-free, so this runs on both branches.
 */
class CarriageGeometryCalculatorTest {

    private static BogeyInfo bogeyAtX(int x) {
        return new BogeyInfo(new BlockPos(x, 0, 0), "", null, false);
    }

    private static CompoundTag contraptionWithBlockYs(int... ys) {
        CompoundTag root = new CompoundTag();
        CompoundTag blocks = new CompoundTag();
        ListTag blockList = new ListTag();
        for (int y : ys) {
            CompoundTag entry = new CompoundTag();
            entry.putLong(ContraptionNbtKeys.POS, new BlockPos(0, y, 0).asLong());
            blockList.add(entry);
        }
        blocks.put(ContraptionNbtKeys.BLOCK_LIST, blockList);
        root.put(ContraptionNbtKeys.BLOCKS, blocks);
        return root;
    }

    @Test
    void bogeySpacingIsTheAbsoluteAxisGapBetweenFirstAndLast() {
        assertEquals(18, CarriageGeometryCalculator.bogeySpacing(
                List.of(bogeyAtX(0), bogeyAtX(18)), Direction.EAST));
    }

    @Test
    void bogeySpacingIsZeroForFewerThanTwoBogeys() {
        assertEquals(0, CarriageGeometryCalculator.bogeySpacing(List.of(bogeyAtX(5)), Direction.EAST));
    }

    @Test
    void trainHeightIsTheVerticalSpanPlusOne() {
        assertEquals(4, CarriageGeometryCalculator.trainHeight(contraptionWithBlockYs(64, 67, 65)));
    }

    @Test
    void trainHeightFallsBackToThreeWhenNoBlocks() {
        assertEquals(3, CarriageGeometryCalculator.trainHeight(new CompoundTag()));
    }

    @Test
    void findSpreadAxisPicksTheWiderHorizontalAxis() {
        assertEquals(Direction.Axis.X, CarriageGeometryCalculator.findSpreadAxis(
                List.of(new Vec3(0, 0, 0), new Vec3(20, 0, 2))));
        assertEquals(Direction.Axis.Z, CarriageGeometryCalculator.findSpreadAxis(
                List.of(new Vec3(0, 0, 0), new Vec3(2, 0, 20))));
    }

    @Test
    void spreadOnAxisIsMaxMinusMinComponent() {
        assertEquals(20.0, CarriageGeometryCalculator.spreadOnAxis(
                List.of(new Vec3(0, 0, 0), new Vec3(20, 0, 0)), Direction.Axis.X));
    }

    @Test
    void interCarriageSpacingIsEntityDistanceMinusNextCarriageBogeySpan() {
        // Two carriages, entities at X=0 and X=30 (entity sits at trailing bogey).
        // Carriage 0 bogeys X=0,10; carriage 1 bogeys X=20,30 (span 10).
        // gap = round(|30-0|) - 10 = 20.
        List<Integer> gaps = CarriageGeometryCalculator.interCarriageSpacings(
                List.of(new Vec3(0, 0, 0), new Vec3(30, 0, 0)),
                List.of(2, 2),
                List.of(bogeyAtX(0), bogeyAtX(10), bogeyAtX(20), bogeyAtX(30)),
                Direction.EAST);
        assertEquals(List.of(20), gaps);
    }

    @Test
    void interCarriageSpacingIsEmptyForASingleCarriage() {
        List<Integer> gaps = CarriageGeometryCalculator.interCarriageSpacings(
                List.of(new Vec3(0, 0, 0)), List.of(2),
                List.of(bogeyAtX(0), bogeyAtX(10)), Direction.EAST);
        assertEquals(List.of(), gaps);
    }

    @Test
    void glueBoxIsAssignedToTheNearestCarriageAndMadeAnchorRelative() {
        // Carriage 0 bogeys X=0,10 (center 5); carriage 1 bogeys X=20,30 (center 25).
        // A box centered at X=25 lands in carriage 1, then shifts by -entityPos (30) -> minX 24-30=-6.
        CarriageContraptionData c0 = new CarriageContraptionData(new CompoundTag(), false, false, List.of());
        CarriageContraptionData c1 = new CarriageContraptionData(new CompoundTag(), false, false, List.of());
        AABB box = new AABB(24, 0, 0, 26, 1, 1); // center x = 25

        List<CarriageContraptionData> result = CarriageGeometryCalculator.distributeGlueToCarriages(
                List.of(c0, c1), List.of(box),
                List.of(bogeyAtX(0), bogeyAtX(10), bogeyAtX(20), bogeyAtX(30)),
                List.of(2, 2), Direction.EAST,
                List.of(new Vec3(0, 0, 0), new Vec3(30, 0, 0)));

        assertEquals(0, result.get(0).glueBoxes().size());
        assertEquals(1, result.get(1).glueBoxes().size());
        assertEquals(-6.0, result.get(1).glueBoxes().get(0).minX);
    }
}
