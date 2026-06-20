package com.vodmordia.railwaysuntold.util.block;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Utility class for block type detection and classification.
 */
public final class BlockTypeUtil {

    private BlockTypeUtil() {
    }

    private static String getBlockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    /**
     * Mode for plant detection, controlling which edge cases are included.
     */
    public enum PlantCheckMode {
        /**
         * Full plant detection including mushrooms, vines, stems, bamboo, lily pads
         */
        FULL,
        /**
         * Vegetation that requires ground support - excludes nether plants, sweet berry bushes
         */
        GROUND_SUPPORTED
    }

    /** Includes logs, leaves, bamboo, and roots (vanilla and modded). */
    public static boolean isTreeOrWood(BlockState state) {
        // Fast tag checks cover vanilla + most modded blocks
        if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)) {
            return true;
        }
        // Specific block checks for bamboo and roots
        if (state.is(Blocks.BAMBOO) || state.is(Blocks.BAMBOO_BLOCK)
                || state.is(Blocks.MANGROVE_ROOTS) || state.is(Blocks.MUDDY_MANGROVE_ROOTS)) {
            return true;
        }
        // String fallback for modded blocks not in standard tags
        String blockId = getBlockId(state);
        return blockId.contains("log") || blockId.contains("wood")
                || blockId.contains("leaves") || blockId.contains("bamboo")
                || blockId.contains("roots");
    }

    /** Water, ice, and fluids that require bridge/girder support for track placement. */
    public static boolean isWater(BlockState state) {
        // Direct block/tag/fluid checks - no string allocation needed
        return state.is(Blocks.WATER) || state.is(BlockTags.ICE)
                || state.getFluidState().is(net.minecraft.world.level.material.Fluids.WATER)
                || state.getFluidState().is(net.minecraft.world.level.material.Fluids.FLOWING_WATER);
    }

    /** Default FULL mode. Excludes solid terrain (grass_block, podzol, mycelium). */
    public static boolean isPlant(BlockState state) {
        return isPlant(state, PlantCheckMode.FULL);
    }

    /**
     * Checks if a block is a plant based on the specified mode.
     *
     * @param state The block state to check
     * @param mode  FULL includes all plants; GROUND_SUPPORTED excludes nether plants and sweet berry bushes
     * @return true if the block is considered a plant for the given mode
     */
    public static boolean isPlant(BlockState state, PlantCheckMode mode) {
        // Fast exclusion of common solid terrain blocks
        if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.PODZOL)
                || state.is(Blocks.MYCELIUM) || state.is(Blocks.DIRT_PATH)) {
            return false;
        }

        // Fast tag checks cover most common plants
        if (state.is(BlockTags.FLOWERS) || state.is(BlockTags.SAPLINGS)
                || state.is(BlockTags.TALL_FLOWERS) || state.is(BlockTags.SMALL_FLOWERS)) {
            return true;
        }

        // Specific vanilla blocks for common cases
        if (state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS) || state.is(Blocks.FERN)
                || state.is(Blocks.LARGE_FERN) || state.is(Blocks.WHEAT)
                || state.is(Blocks.CARROTS) || state.is(Blocks.POTATOES) || state.is(Blocks.BEETROOTS)) {
            return true;
        }

        if (mode == PlantCheckMode.FULL) {
            if (state.is(Blocks.BAMBOO) || state.is(Blocks.LILY_PAD)
                    || state.is(Blocks.BROWN_MUSHROOM) || state.is(Blocks.RED_MUSHROOM)
                    || state.is(Blocks.MUSHROOM_STEM) || state.is(Blocks.DEAD_BUSH)) {
                return true;
            }
        } else {
            if (state.is(Blocks.BROWN_MUSHROOM) || state.is(Blocks.RED_MUSHROOM)
                    || state.is(Blocks.DEAD_BUSH) || state.is(Blocks.DANDELION)
                    || state.is(Blocks.POPPY)) {
                return true;
            }
        }

        // String fallback for modded blocks not caught by tags/direct checks
        String blockId = getBlockId(state);

        // Exclude modded grass blocks
        if (blockId.contains("_grass_block")) {
            return false;
        }

        boolean isCommonPlant = blockId.contains("grass") || blockId.contains("flower")
                || blockId.contains("sapling") || blockId.contains("fern")
                || blockId.contains("crop") || blockId.contains("wheat")
                || blockId.contains("carrot") || blockId.contains("potato")
                || blockId.contains("beetroot");

        if (isCommonPlant) {
            return true;
        }

        if (mode == PlantCheckMode.FULL) {
            return blockId.contains("plant") || blockId.contains("bamboo")
                    || blockId.contains("lily_pad") || blockId.contains("mushroom")
                    || blockId.contains("stem") || blockId.contains("vine")
                    || blockId.contains("bush");
        } else {
            boolean isPlantBlock = blockId.contains("plant") && !blockId.contains("nether");
            boolean isBush = blockId.contains("bush") && !blockId.contains("sweet_berry");
            return isPlantBlock || isBush;
        }
    }

    /** Includes brown/red mushroom blocks and stems. */
    public static boolean isMushroom(BlockState state) {
        if (state.is(Blocks.BROWN_MUSHROOM_BLOCK) || state.is(Blocks.RED_MUSHROOM_BLOCK)
                || state.is(Blocks.MUSHROOM_STEM)) {
            return true;
        }
        // String fallback for modded mushroom blocks
        String blockId = getBlockId(state);
        return blockId.contains("mushroom_block") || blockId.contains("mushroom_stem");
    }

    public static boolean isBamboo(BlockState state) {
        return state.is(Blocks.BAMBOO);
    }

    /** Includes vanilla, cave, weeping, and twisting vines. */
    public static boolean isVine(BlockState state) {
        if (state.is(Blocks.VINE) ||
                state.is(Blocks.CAVE_VINES) ||
                state.is(Blocks.CAVE_VINES_PLANT) ||
                state.is(Blocks.WEEPING_VINES) ||
                state.is(Blocks.WEEPING_VINES_PLANT) ||
                state.is(Blocks.TWISTING_VINES) ||
                state.is(Blocks.TWISTING_VINES_PLANT)) {
            return true;
        }
        return getBlockId(state).contains("vine");
    }

    /** Cocoa pods that grow on the side of jungle logs. */
    public static boolean isCocoa(BlockState state) {
        return state.is(Blocks.COCOA);
    }

    /** Biomes O' Plenty dead branches that attach to dead logs. */
    public static boolean isDeadBranch(BlockState state) {
        return getBlockId(state).equals("biomesoplenty:dead_branch");
    }

    /** Mangrove propagules that hang below mangrove leaves. */
    public static boolean isPropagule(BlockState state) {
        return state.is(Blocks.MANGROVE_PROPAGULE);
    }

    /** Moss carpet that commonly appears on mangrove roots. */
    public static boolean isMossCarpet(BlockState state) {
        return state.is(Blocks.MOSS_CARPET);
    }

    /**
     * Returns true for blocks that are part of trees, mushroom structures, bamboo, or vines.
     * Used by pillar placement to look through vegetation when finding solid ground.
     */
    public static boolean isVegetation(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)
                || isMushroom(state) || isBamboo(state) || isVine(state)
                || isCocoa(state) || isPropagule(state) || isMossCarpet(state)
                || isDeadBranch(state);
    }

    public static boolean isAirOrLiquid(BlockState state) {
        return state.isAir() || !state.getFluidState().isEmpty();
    }

    /** Checks if a block contains any fluid (water, lava, or modded fluids like oil). */
    public static boolean isAnyFluid(BlockState state) {
        return !state.getFluidState().isEmpty();
    }

    public static boolean isWaterOrLava(BlockState state) {
        return state.is(Blocks.WATER) || state.is(Blocks.LAVA);
    }

    /** Includes chests, trapped chests, ender chests, barrels, and modded containers. */
    public static boolean isChest(BlockState state) {
        String blockId = getBlockId(state);

        return state.is(Blocks.CHEST) ||
                state.is(Blocks.TRAPPED_CHEST) ||
                state.is(Blocks.ENDER_CHEST) ||
                state.is(Blocks.BARREL) ||
                blockId.contains("chest");
    }
}
