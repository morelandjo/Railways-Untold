package com.vodmordia.railwaysuntold.config;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * Utility for loading blocks from full namespace IDs, with optional block state properties.
 * Supports syntax like "minecraft:redstone_lamp[lit=true]" or "minecraft:campfire[lit=false,signal_fire=true]".
 */
public class ConfigBlockLoader {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Returns Blocks.STONE if not found.
     * Properties in brackets are stripped before lookup.
     */
    public static Block loadBlock(String fullBlockId) {
        String blockId = stripProperties(fullBlockId);
        try {
            ResourceLocation loc = new ResourceLocation(blockId);
            Block block = BuiltInRegistries.BLOCK.get(loc);
            if (block == Blocks.AIR && !blockId.equals("minecraft:air")) {
                LOGGER.warn("Block not found: {}, falling back to stone", blockId);
                return Blocks.STONE;
            }
            return block;
        } catch (RuntimeException e) {
            LOGGER.error("Invalid block ID: {}, falling back to stone", blockId, e);
            return Blocks.STONE;
        }
    }

    /**
     * Loads a block state from a full namespaced ID, optionally with block state properties.
     * Supports bracket syntax for specifying block state properties:
     *   - {@code "minecraft:redstone_lamp[lit=true]"}
     *   - {@code "minecraft:campfire[lit=false,signal_fire=true]"}
     *   - {@code "minecraft:wall_torch"} (no properties = default state)
     */
    public static BlockState loadBlockState(String fullBlockId) {
        Block block = loadBlock(fullBlockId);
        BlockState state = block.defaultBlockState();

        String propsString = extractProperties(fullBlockId);
        if (propsString == null) {
            return state;
        }

        for (String entry : propsString.split(",")) {
            String[] parts = entry.split("=", 2);
            if (parts.length != 2) {
                LOGGER.warn("Invalid property format '{}' in block ID: {}", entry.trim(), fullBlockId);
                continue;
            }
            String propName = parts[0].trim();
            String propValue = parts[1].trim();
            state = applyProperty(state, propName, propValue, fullBlockId);
        }

        return state;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState applyProperty(BlockState state, String propName, String propValue, String blockId) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(propName)) {
                Optional<?> value = property.getValue(propValue);
                if (value.isPresent()) {
                    return state.setValue((Property) property, (Comparable) value.get());
                } else {
                    LOGGER.warn("Invalid value '{}' for property '{}' on block {}. Valid values: {}",
                            propValue, propName, blockId, property.getPossibleValues());
                    return state;
                }
            }
        }
        LOGGER.warn("Block {} does not have property '{}'. Available properties: {}",
                blockId, propName, state.getProperties().stream().map(Property::getName).toList());
        return state;
    }

    private static String stripProperties(String blockId) {
        int bracket = blockId.indexOf('[');
        return bracket >= 0 ? blockId.substring(0, bracket).trim() : blockId.trim();
    }

    private static String extractProperties(String blockId) {
        int open = blockId.indexOf('[');
        int close = blockId.lastIndexOf(']');
        if (open >= 0 && close > open) {
            return blockId.substring(open + 1, close).trim();
        }
        return null;
    }

    private ConfigBlockLoader() {}
}
