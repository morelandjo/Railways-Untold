package com.vodmordia.railwaysuntold.worldgen.village.network;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Computes a graph of candidate rail links over a set of village positions.
 * Implementations differ in topology (e.g. dense Delaunay mesh vs. sparse RNG).
 */
public interface NetworkPlanner {
    List<StructureConnection> plan(List<BlockPos> points, int maxEdgeLenBlocks);
}
