package com.vodmordia.railwaysuntold.worldgen.placement.handler;

import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision.Type;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterizes the placement-executor dispatch: {@link PlacementExecutorRegistry#getExecutor} maps each
 * decision {@link Type} a segment can emit to the executor registered under that type, and
 * returns null for the control types the orchestrator handles before dispatch. This pins the seam the
 * orchestrator relies on - a segment's decision type must always resolve to exactly one matching handler.
 */
class PlacementExecutorRegistryTest {

    @Test
    void everyEmittableTypeResolvesToAnExecutorOfThatType() {
        // The decision types a PathSegment.execute can produce (see PathSegmentExecutionTest), plus the
        // BRANCH/STATION/EVENT types the orchestrator emits directly.
        Type[] dispatched = {
                Type.CURVE, Type.BRANCH, Type.BEZIER, Type.STRAIGHT, Type.TUNNEL, Type.BRIDGE,
                Type.STATION, Type.EVENT, Type.ELEVATED_TUNNEL, Type.SCURVE_45,
                Type.DIAGONAL_ENTRY, Type.DIAGONAL_STRAIGHT, Type.DIAGONAL_EXIT,
        };
        for (Type t : dispatched) {
            PlacementExecutor exec = PlacementExecutorRegistry.getExecutor(t);
            assertNotNull(exec, "no executor registered for " + t);
            assertEquals(t, exec.getType(), "executor registered under the wrong type for " + t);
        }
    }

    @Test
    void controlTypesHaveNoExecutor() {
        // INVALID and DEFER never reach dispatch - the orchestrator branches on them first - so the
        // registry deliberately has no entry for them.
        assertNull(PlacementExecutorRegistry.getExecutor(Type.INVALID));
        assertNull(PlacementExecutorRegistry.getExecutor(Type.DEFER));
    }
}
