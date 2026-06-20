package com.vodmordia.railwaysuntold.datapack;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Random;
import java.util.function.ToIntFunction;

/**
 * Utility for weighted random selection from a list of items.
 */
public final class WeightedRandomPicker {

    private WeightedRandomPicker() {
    }

    /**
     * Picks a random item from the list using weighted selection.
     *
     * @param items       The items to pick from
     * @param weightFunc  Function to extract the weight from each item
     * @param random      Random source
     * @return A randomly selected item, or null if the list is empty or all weights are zero
     */
    @Nullable
    public static <T> T pick(List<T> items, ToIntFunction<T> weightFunc, Random random) {
        if (items.isEmpty()) {
            return null;
        }

        int totalWeight = 0;
        for (T item : items) {
            totalWeight += Math.max(0, weightFunc.applyAsInt(item));
        }

        if (totalWeight <= 0) {
            return null;
        }

        int roll = random.nextInt(totalWeight);
        int cumulative = 0;
        for (T item : items) {
            cumulative += Math.max(0, weightFunc.applyAsInt(item));
            if (roll < cumulative) {
                return item;
            }
        }

        // Should not reach here, but safety fallback
        return items.get(items.size() - 1);
    }
}
