package com.vodmordia.railwaysuntold.worldgen.terrain.harness;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The gametest world's global {@link TerrainAtlas}: a base atlas (the closed-form
 * {@link BandedTerrainAtlas}) with optional per-region overlays registered at runtime. A gametest
 * registers an overlay over a fresh, never-generated region before force-loading it, so chunks there
 * generate from the overlay (e.g. a {@link ReplayTerrainAtlas} reproducing a captured profile) while
 * the rest of the world stays on the base atlas.
 *
 * Queries consult the registry live, so registration only needs to precede chunk generation, not world
 * creation - which is why this is the seam that lets a gametest install terrain after the mixin has
 * already built the generator. The registry is static (the generator is built once at world creation,
 * long before any test runs) and copy-on-write (chunk generation runs on worker threads).
 */
public final class OverlayTerrainAtlas implements TerrainAtlas {

    /** A rectangular XZ region (inclusive bounds) served by its own atlas. */
    public record Region(int minX, int minZ, int maxX, int maxZ, TerrainAtlas atlas) {
        boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    private static final List<Region> OVERLAYS = new CopyOnWriteArrayList<>();

    /** Installs an overlay region. Register before force-loading the region's chunks. */
    public static void register(Region region) {
        OVERLAYS.add(region);
    }

    /** Removes all overlays. A gametest clears at the end so its terrain can't leak into another test. */
    public static void clear() {
        OVERLAYS.clear();
    }

    private final TerrainAtlas base;

    public OverlayTerrainAtlas(TerrainAtlas base) {
        this.base = base;
    }

    private TerrainAtlas atlasAt(int x, int z) {
        for (Region r : OVERLAYS) {
            if (r.contains(x, z)) {
                return r.atlas();
            }
        }
        return base;
    }

    @Override
    public int groundHeight(int x, int z) {
        return atlasAt(x, z).groundHeight(x, z);
    }

    @Override
    public int plannerHeight(int x, int z) {
        return atlasAt(x, z).plannerHeight(x, z);
    }

    @Override
    public boolean isWater(int x, int z) {
        return atlasAt(x, z).isWater(x, z);
    }

    @Override
    public int waterSurface(int x, int z) {
        return atlasAt(x, z).waterSurface(x, z);
    }

    @Override
    public VoidBand voidBandAt(int x, int z) {
        return atlasAt(x, z).voidBandAt(x, z);
    }
}
