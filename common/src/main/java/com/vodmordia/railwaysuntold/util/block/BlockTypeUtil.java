package com.vodmordia.railwaysuntold.util.block;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class BlockTypeUtil {

    private BlockTypeUtil() {}

    private static String getBlockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    public enum PlantCheckMode {
        /** Includes mushrooms, vines, stems, bamboo, lily pads */
        FULL,
        /** Excludes nether plants, sweet berry bushes */
        GROUND_SUPPORTED
    }

    /** Includes vanilla and modded logs, leaves, bamboo, and roots */
    public static boolean isTreeOrWood(BlockState state) {
        // Fast tag checks cover vanilla + most modded blocks
        if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)) {
            return true;
        }
        // Specific block checks for bamboo and roots
        if (state.is(Blocks.BAMBOO) || state.is(Blocks.MANGROVE_ROOTS)) {
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

    /** Excludes solid terrain blocks like grass_block, podzol, mycelium */
    public static boolean isPlant(BlockState state) {
        return isPlant(state, PlantCheckMode.FULL);
    }

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
        if (state.is(Blocks.GRASS) || state.is(Blocks.TALL_GRASS) || state.is(Blocks.FERN)
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

    /**
     * Checks if a block is part of a building (wall, door, window, roof, etc.).
     * Used to detect village houses that may not be detected by structure bounding boxes.
     */
    public static boolean isBuildingBlock(BlockState state) {
        String blockId = getBlockId(state);

        // Walls and fences
        if (state.is(BlockTags.WALLS) || state.is(BlockTags.FENCES) || state.is(BlockTags.FENCE_GATES)) {
            return true;
        }

        // Doors and trapdoors
        if (state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS)) {
            return true;
        }

        // Windows (glass panes, stained glass)
        if (blockId.contains("glass_pane") || blockId.contains("stained_glass")) {
            return true;
        }

        // Building materials commonly used in village houses
        if (blockId.contains("planks") && !state.isAir()) {
            return true;
        }

        // Beds, crafting tables, furnaces (interior indicators)
        if (state.is(BlockTags.BEDS) ||
                state.is(Blocks.CRAFTING_TABLE) ||
                state.is(Blocks.FURNACE) ||
                state.is(Blocks.BLAST_FURNACE) ||
                state.is(Blocks.SMOKER)) {
            return true;
        }

        // Stairs (often used in roofs)
        if (state.is(BlockTags.WOODEN_STAIRS) || blockId.contains("stairs")) {
            return true;
        }

        return false;
    }
}
