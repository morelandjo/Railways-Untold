package com.vodmordia.railwaysuntold.worldgen.integration.create;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

/**
 * Utility for loading Create mod blocks.
 */
public class CreateBlockLoader {

    /**
     * Loads a Create block.
     *
     * @param createBlockId Block ID without "create:" prefix (e.g., "metal_girder")
     * @return Block from Create
     */
    public static Block loadBlock(String createBlockId) {
        return loadCreateBlock(createBlockId);
    }

    /**
     * Internal helper to load a Create block.
     */
    private static Block loadCreateBlock(String createBlockId) {
        ResourceLocation loc = new ResourceLocation("create:" + createBlockId);
        return BuiltInRegistries.BLOCK.get(loc);
    }

    private CreateBlockLoader() {
    }
}
