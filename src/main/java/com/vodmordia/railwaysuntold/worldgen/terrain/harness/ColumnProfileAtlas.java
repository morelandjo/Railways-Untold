package com.vodmordia.railwaysuntold.worldgen.terrain.harness;

/**
 * A {@link TerrainAtlas} that lays a flat surface and carves a single rectangular cave beneath part
 * of it, so a gametest can drive a head across a known void and observe the support the placement
 * layer commits there. The cave reaches up to the block directly below the track (surface minus one),
 * leaving the track spanning open air with a solid floor far below - the shape the placement layer's
 * support report records for a track that crossed a cave.
 *
 * The surface height is uniform and unchanged by the cave: a sub-surface void is invisible to the
 * route planner, which only ever reads the surface heightmap.
 */
public final class ColumnProfileAtlas implements TerrainAtlas {

    private final int surfaceY;
    private final int caveMinX;
    private final int caveMaxX;
    private final int caveMinZ;
    private final int caveMaxZ;
    private final int caveFloorY;
    private final boolean fluid;

    /**
     * @param surfaceY   the Y the planner's sampler reports for every column (the track elevation)
     * @param caveMinX   inclusive world-X where the cave begins
     * @param caveMaxX   inclusive world-X where the cave ends
     * @param caveMinZ   inclusive world-Z where the cave begins
     * @param caveMaxZ   inclusive world-Z where the cave ends
     * @param caveFloorY Y of the solid floor under the cave (the void sits above it)
     * @param fluid      whether the cave is flooded
     */
    public ColumnProfileAtlas(int surfaceY, int caveMinX, int caveMaxX,
                              int caveMinZ, int caveMaxZ, int caveFloorY, boolean fluid) {
        this.surfaceY = surfaceY;
        this.caveMinX = caveMinX;
        this.caveMaxX = caveMaxX;
        this.caveMinZ = caveMinZ;
        this.caveMaxZ = caveMaxZ;
        this.caveFloorY = caveFloorY;
        this.fluid = fluid;
    }

    private boolean inCave(int x, int z) {
        return x >= caveMinX && x <= caveMaxX && z >= caveMinZ && z <= caveMaxZ;
    }

    @Override
    public int groundHeight(int x, int z) {
        return surfaceY - 1;
    }

    @Override
    public boolean isWater(int x, int z) {
        return false;
    }

    @Override
    public int waterSurface(int x, int z) {
        return surfaceY - 1;
    }

    @Override
    public VoidBand voidBandAt(int x, int z) {
        if (!inCave(x, z)) {
            return null;
        }
        // hiY is the block directly below the track (groundHeight); loY sits one above the floor.
        return new VoidBand(caveFloorY + 1, surfaceY - 1, fluid);
    }
}
