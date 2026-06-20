package com.vodmordia.railwaysuntold.worldgen.survey;

import net.minecraft.world.level.ChunkPos;

import java.util.HashSet;
import java.util.Set;

/**
 * Identity and footprint of a surveyed region: a square block of chunks centered on {@code anchor},
 * extending {@code radius} chunks in each direction (so a 2*radius+1 square). Used as the cache and
 * persistence key, and to enumerate which chunks a survey must load.
 */
public record RegionKey(ChunkPos anchor, int radius) {

    /** A region centered on the structure's chunk, sized to cover {@code chunkRadius} chunks around it. */
    public static RegionKey around(ChunkPos anchor, int chunkRadius) {
        return new RegionKey(anchor, Math.max(0, chunkRadius));
    }

    /** All chunks in the region's square footprint. */
    public Set<ChunkPos> chunks() {
        Set<ChunkPos> out = new HashSet<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                out.add(new ChunkPos(anchor.x + dx, anchor.z + dz));
            }
        }
        return out;
    }

    /** Stable string id for NBT map keys / logging. */
    public String id() {
        return anchor.x + "," + anchor.z + ",r" + radius;
    }
}
