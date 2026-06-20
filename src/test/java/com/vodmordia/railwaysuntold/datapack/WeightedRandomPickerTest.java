package com.vodmordia.railwaysuntold.datapack;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterizes {@link WeightedRandomPicker} - the generic weighted-selection algorithm behind event
 * weighting. Pure and deterministic once the {@link Random} is scripted: null when nothing can be chosen,
 * negative and zero weights clamped out of the running total, and cumulative-boundary selection by the roll.
 */
class WeightedRandomPickerTest {

    private record Item(String name, int weight) {
    }

    private static final ToIntFunction<Item> WEIGHT = Item::weight;

    /** A Random whose {@code nextInt(bound)} returns a fixed roll and records the bound it was asked for. */
    private static final class ScriptedRandom extends Random {
        private final int roll;
        int observedBound = -1;

        ScriptedRandom(int roll) {
            this.roll = roll;
        }

        @Override
        public int nextInt(int bound) {
            observedBound = bound;
            return roll;
        }
    }

    @Nested
    class NothingToPick {
        @Test
        void emptyListReturnsNull() {
            assertNull(WeightedRandomPicker.pick(List.of(), WEIGHT, new Random()));
        }

        @Test
        void allZeroWeightsReturnsNull() {
            List<Item> items = List.of(new Item("a", 0), new Item("b", 0));
            assertNull(WeightedRandomPicker.pick(items, WEIGHT, new Random()));
        }

        @Test
        void allNegativeWeightsReturnsNull() {
            List<Item> items = List.of(new Item("a", -5), new Item("b", -1));
            assertNull(WeightedRandomPicker.pick(items, WEIGHT, new Random()));
        }
    }

    @Nested
    class WeightClamping {
        @Test
        void negativeAndZeroWeightsAreExcludedFromTheTotal() {
            // Only the two positive weights (3 + 7) count toward the bound handed to nextInt.
            List<Item> items = List.of(
                    new Item("neg", -100),
                    new Item("three", 3),
                    new Item("zero", 0),
                    new Item("seven", 7));
            ScriptedRandom random = new ScriptedRandom(0);

            WeightedRandomPicker.pick(items, WEIGHT, random);

            assertEquals(10, random.observedBound);
        }

        @Test
        void negativeWeightItemIsNeverSelected() {
            // roll 0 falls past the clamped (zero-width) negative item and lands on the first positive one.
            List<Item> items = List.of(new Item("neg", -3), new Item("pos", 5));
            assertEquals("pos", WeightedRandomPicker.pick(items, WEIGHT, new ScriptedRandom(0)).name());
        }

        @Test
        void zeroWeightItemIsSkippedAtItsBoundary() {
            // a(2) spans rolls 0..1; zero contributes no width; b(3) spans 2..4. Roll 2 must reach b, not zero.
            List<Item> items = List.of(new Item("a", 2), new Item("zero", 0), new Item("b", 3));
            assertEquals("b", WeightedRandomPicker.pick(items, WEIGHT, new ScriptedRandom(2)).name());
        }
    }

    @Nested
    class CumulativeBoundarySelection {
        private final List<Item> items = List.of(new Item("a", 2), new Item("b", 3), new Item("c", 5));

        @Test
        void singlePositiveItemIsAlwaysPicked() {
            List<Item> single = List.of(new Item("only", 4));
            assertEquals("only", WeightedRandomPicker.pick(single, WEIGHT, new ScriptedRandom(0)).name());
            assertEquals("only", WeightedRandomPicker.pick(single, WEIGHT, new ScriptedRandom(3)).name());
        }

        @Test
        void lowRollsLandInTheFirstBucket() {
            // a occupies cumulative [0,2): rolls 0 and 1.
            assertEquals("a", WeightedRandomPicker.pick(items, WEIGHT, new ScriptedRandom(0)).name());
            assertEquals("a", WeightedRandomPicker.pick(items, WEIGHT, new ScriptedRandom(1)).name());
        }

        @Test
        void midRollsLandInTheMiddleBucket() {
            // b occupies cumulative [2,5): rolls 2, 3, 4.
            assertEquals("b", WeightedRandomPicker.pick(items, WEIGHT, new ScriptedRandom(2)).name());
            assertEquals("b", WeightedRandomPicker.pick(items, WEIGHT, new ScriptedRandom(4)).name());
        }

        @Test
        void highRollsLandInTheLastBucket() {
            // c occupies cumulative [5,10): rolls 5 through 9.
            assertEquals("c", WeightedRandomPicker.pick(items, WEIGHT, new ScriptedRandom(5)).name());
            assertEquals("c", WeightedRandomPicker.pick(items, WEIGHT, new ScriptedRandom(9)).name());
        }

        @Test
        void theBoundIsTheSumOfAllPositiveWeights() {
            ScriptedRandom random = new ScriptedRandom(0);
            WeightedRandomPicker.pick(items, WEIGHT, random);
            assertEquals(10, random.observedBound);
        }
    }

    @Nested
    class DuplicateItems {
        @Test
        void equalItemsEachKeepTheirOwnBucket() {
            // Two distinct instances that happen to be equal still partition the roll space by position.
            Item shared = new Item("dup", 4);
            List<Item> items = List.of(shared, shared);
            // Bound is 8; roll 0 lands in the first bucket, roll 4 in the second - both return the equal value.
            assertSame(shared, WeightedRandomPicker.pick(items, WEIGHT, new ScriptedRandom(0)));
            assertSame(shared, WeightedRandomPicker.pick(items, WEIGHT, new ScriptedRandom(4)));
        }
    }
}
