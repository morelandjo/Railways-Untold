package com.vodmordia.railwaysuntold.blocks.core;

import net.minecraft.world.level.block.Block;

/**
 * Marker block for event schematics that indicates where track should terminate.
 * Placed in schematics on the edge opposite to the entry track.
 * Skipped during world placement (not placed in the world).
 */
public class DeadEndBlock extends Block {

    public DeadEndBlock(Properties properties) {
        super(properties);
    }
}
