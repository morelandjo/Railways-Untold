package com.vodmordia.railwaysuntold.worldgen.train;

import com.vodmordia.railwaysuntold.worldgen.train.TrainValidationData.BogeyInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Characterizes {@link BogeyLocationCalculator#computeBogeyLocations} - the pure layout pass that turns
 * each bogey's schematic-relative position into an integer offset along the assembly axis. Covers the
 * two load-bearing behaviours: inter-carriage spacing accumulates into later carriages' offsets, and the
 * whole result is normalized so the minimum (the leading bogey) sits at 0.
 */
class BogeyLocationCalculatorTest {

    private static BogeyInfo bogeyAt(int x, int z) {
        return new BogeyInfo(new BlockPos(x, 0, z), "", null, false);
    }

    @Test
    void emptyBogeyListReturnsNull() {
        assertNull(BogeyLocationCalculator.computeBogeyLocations(
                List.of(), Direction.EAST, List.of(), List.of()));
    }

    @Test
    void singleCarriageOffsetsAreRelativeToTheLeadingBogey() {
        // Two bogeys along EAST (+X) at x=0 and x=10, one carriage, no inter-carriage gap.
        int[] locs = BogeyLocationCalculator.computeBogeyLocations(
                List.of(bogeyAt(0, 0), bogeyAt(10, 0)),
                Direction.EAST, List.of(2), List.of());
        assertArrayEquals(new int[]{0, 10}, locs);
    }

    @Test
    void negativePositionsAreNormalizedSoTheLeadingBogeyIsZero() {
        int[] locs = BogeyLocationCalculator.computeBogeyLocations(
                List.of(bogeyAt(-5, 0), bogeyAt(5, 0)),
                Direction.EAST, List.of(2), List.of());
        assertArrayEquals(new int[]{0, 10}, locs);
    }

    @Test
    void interCarriageSpacingAccumulatesIntoLaterCarriageOffsets() {
        // Carriage 0: bogeys at x=2, x=8. Carriage 1: bogeys at x=20, x=26. Gap of 10.
        // c0 -> [2, 8]; carriageOffset = 8 + 10 = 18; c1 -> [38, 44]; normalize by min(2) -> [0,6,36,42].
        int[] locs = BogeyLocationCalculator.computeBogeyLocations(
                List.of(bogeyAt(2, 0), bogeyAt(8, 0), bogeyAt(20, 0), bogeyAt(26, 0)),
                Direction.EAST, List.of(2, 2), List.of(10));
        assertArrayEquals(new int[]{0, 6, 36, 42}, locs);
    }
}
