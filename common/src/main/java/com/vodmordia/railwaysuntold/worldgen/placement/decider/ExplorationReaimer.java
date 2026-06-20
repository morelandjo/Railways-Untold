package com.vodmordia.railwaysuntold.worldgen.placement.decider;

import net.minecraft.core.Direction;

/**
 * Chooses the direction a stuck exploring head should aim a freshly forced exploration target. A head
 * that has wandered into a reversal - its current heading now points opposite the outward direction it
 * set out along - must not have its next target placed further along that reversed heading, or it keeps
 * curving back on itself and loops into its own line. In that case the target is re-aimed along the
 * outward direction. A head still heading outward (or laterally) keeps its current heading.
 */
public final class ExplorationReaimer {

    private ExplorationReaimer() {}

    public static Direction chooseEscalationDirection(Direction initialOutward, Direction currentHeading) {
        return currentHeading == initialOutward.getOpposite() ? initialOutward : currentHeading;
    }
}
