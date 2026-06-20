package com.vodmordia.railwaysuntold.worldgen.placement.handler;

import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;

/**
 * Interface for placement execution strategies.
 * Each placement type (CURVE, BEZIER, STRAIGHT, etc.) has its own executor.
 */
public interface PlacementExecutor {

    /**
     * Gets the placement type this executor handles.
     *
     * @return The PlacementDecision.Type this executor is responsible for
     */
    PlacementDecision.Type getType();

    /**
     * Executes the placement decision.
     *
     * @param context The placement context containing all necessary data
     * @return HandlerResult indicating success, failure, or deferral
     */
    HandlerResult handle(PlacementContext context);
}
