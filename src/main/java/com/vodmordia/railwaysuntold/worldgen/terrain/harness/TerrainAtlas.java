package com.vodmordia.railwaysuntold.worldgen.terrain.harness;

/**
 * A height function for the test terrain generator: maps each world column (x, z) to a top solid
 * block Y and optional standing water above it. Pluggable so the generator can be driven by a fixed
 * coordinate-band map or, later, a seeded shape sweep.
 *
 * Water surfaces are local to each column rather than a single global sea level: the test world's
 * flat baseline sits far below Y=63 (so the gametest arenas placed near Y=-59 stay above ground),
 * which makes a global sea level meaningless. A river band instead reports its own water surface,
 * just high enough above the carved bed that the planner's heightmap-gap water check fires.
 */
public interface TerrainAtlas {

    /** Y of the highest solid block at (x, z) - what {@code fillFromNoise} actually places. */
    int groundHeight(int x, int z);

    /**
     * The ground height the planner's terrain sampler reports for (x, z), which normally equals
     * {@link #groundHeight}. A drift band can make it under-report the real ground to model "actual
     * terrain is taller than the noise sampler predicted" - the condition that makes the planner
     * plan flat where the world rises, so placement drifts off the plan.
     */
    default int plannerHeight(int x, int z) {
        return groundHeight(x, z);
    }

    /** Whether this column has standing water above the ground. */
    boolean isWater(int x, int z);

    /** Y of the highest water block at (x, z); only meaningful when {@link #isWater} is true. */
    int waterSurface(int x, int z);

    /** A sub-surface band [loY, hiY] (inclusive) carved out below the solid ground, or air/fluid. */
    record VoidBand(int loY, int hiY, boolean fluid) {
        boolean contains(int y) {
            return y >= loY && y <= hiY;
        }
    }

    /**
     * A void carved into the solid column below {@link #groundHeight} at (x, z), or null for a fully
     * solid column. The surface above the band stays solid (a cave roof) and the floor below it stays
     * solid, so the band models a cave/ravine the placement layer must bridge. The carved band does not
     * change the reported {@link #groundHeight}: a sub-surface void is invisible to the route planner,
     * which only ever sees the surface heightmap.
     */
    default VoidBand voidBandAt(int x, int z) {
        return null;
    }
}
