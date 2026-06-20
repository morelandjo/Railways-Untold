package com.vodmordia.railwaysuntold.worldgen.placement.handler;

import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;

import java.util.EnumMap;
import java.util.Map;

/**
 * Registry for placement executors.
 */
public class PlacementExecutorRegistry {

    private static final Map<PlacementDecision.Type, PlacementExecutor> EXECUTORS = new EnumMap<>(PlacementDecision.Type.class);

    static {
        register(new CurvePlacementExecutor());
        register(new BranchPlacementExecutor());
        register(new BezierTrackPlacementExecutor());
        register(new StraightTrackPlacementExecutor());
        register(new TunnelPlacementExecutor());
        register(new BridgePlacementExecutor());
        register(new StationPlacementExecutor());
        register(new EventPlacementExecutor());
        register(new ElevatedTunnelPlacementExecutor());
        register(new SCurve45PlacementExecutor());
        register(new DiagonalCurveExecutor(DiagonalCurveExecutor.Mode.ENTRY));
        register(new DiagonalStraightExecutor());
        register(new DiagonalCurveExecutor(DiagonalCurveExecutor.Mode.EXIT));
    }

    private static void register(PlacementExecutor executor) {
        EXECUTORS.put(executor.getType(), executor);
    }

    /**
     * Gets the executor for the given placement type.
     *
     * @param type The placement decision type
     * @return The executor, or null if no executor is registered (e.g., INVALID)
     */
    public static PlacementExecutor getExecutor(PlacementDecision.Type type) {
        return EXECUTORS.get(type);
    }

}
