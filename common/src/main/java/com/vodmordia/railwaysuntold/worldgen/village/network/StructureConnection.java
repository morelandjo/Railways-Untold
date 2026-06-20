package com.vodmordia.railwaysuntold.worldgen.village.network;

import net.minecraft.core.BlockPos;

/**
 * A planned undirected edge in the village network: a candidate rail link
 * between two village centers.
 */
public record StructureConnection(BlockPos from, BlockPos to) {
}
