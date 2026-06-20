package com.vodmordia.railwaysuntold.worldgen.placement.placer;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Loads Minecraft StructureTemplate .nbt files (Create's schematic format).
 */
public class NbtSchematicLoader {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Represents a loaded schematic with all block data.
     */
    public static class LoadedSchematic {
        private final int width;
        private final int height;
        private final int length;
        private final BlockState[] blocks;
        private final Map<BlockPos, CompoundTag> blockEntities;
        private final List<CompoundTag> entities;

        public LoadedSchematic(int width, int height, int length, BlockState[] blocks,
                              Map<BlockPos, CompoundTag> blockEntities, List<CompoundTag> entities) {
            this.width = width;
            this.height = height;
            this.length = length;
            this.blocks = blocks;
            this.blockEntities = blockEntities;
            this.entities = entities;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public int getLength() {
            return length;
        }

        public Vec3i getSize() {
            return new Vec3i(width, height, length);
        }

        /**
         * Gets the block at the given local coordinates.
         * Index formula: index = (y * length * width) + (z * width) + x
         */
        public BlockState getBlock(int x, int y, int z) {
            if (x < 0 || x >= width || y < 0 || y >= height || z < 0 || z >= length) {
                return Blocks.AIR.defaultBlockState();
            }
            int index = (y * length * width) + (z * width) + x;
            if (index < 0 || index >= blocks.length) {
                return Blocks.AIR.defaultBlockState();
            }
            return blocks[index];
        }

        public Map<BlockPos, CompoundTag> getBlockEntities() {
            return blockEntities;
        }

        /**
         * Returns the raw structure-template entity entries. Each entry is a compound with
         * a fractional {@code pos} (list of double), an integer {@code blockPos}, and the
         * entity's {@code nbt} compound.
         */
        public List<CompoundTag> getEntities() {
            return entities;
        }
    }

    /**
     * Mutable buffer for accumulating schematic data during parsing.
     */
    private static class SchematicBuffer {
        final List<BlockState> palette;
        final int width;
        final int height;
        final int length;
        final BlockState[] blocks;
        final Map<BlockPos, CompoundTag> blockEntities;

        SchematicBuffer(List<BlockState> palette, int width, int height, int length,
                        BlockState[] blocks, Map<BlockPos, CompoundTag> blockEntities) {
            this.palette = palette;
            this.width = width;
            this.height = height;
            this.length = length;
            this.blocks = blocks;
            this.blockEntities = blockEntities;
        }

        boolean isWithinBounds(int x, int y, int z) {
            return x >= 0 && x < width && y >= 0 && y < height && z >= 0 && z < length;
        }

        int toIndex(int x, int y, int z) {
            return (y * length * width) + (z * width) + x;
        }
    }

    /**
     * Loads an NBT schematic from mod resources.
     *
     * @param resourceManager The resource manager
     * @param schematicName Name of the schematic file without extension (e.g., "station")
     * @return LoadedSchematic, or null if loading failed
     */
    @Nullable
    public static LoadedSchematic loadFromResources(ResourceManager resourceManager, String schematicName) {
        ResourceLocation location = new ResourceLocation("railwaysuntold",
            "structure/" + schematicName + ".nbt");
        return loadFromResources(resourceManager, location);
    }

    /**
     * Loads a schematic from any data pack via a full ResourceLocation.
     * The location should point directly to the .nbt file (e.g., "railwaysuntold:structure/station.nbt").
     *
     * @param resourceManager The server's resource manager
     * @param location        Full resource location of the .nbt file
     * @return LoadedSchematic, or null if loading failed
     */
    @Nullable
    public static LoadedSchematic loadFromResources(ResourceManager resourceManager, ResourceLocation location) {
        try {
            Resource resource = resourceManager.getResource(location).orElse(null);
            if (resource == null) {
                LOGGER.error("[NBT-LOADER] Schematic resource not found: {}", location);
                return null;
            }

            CompoundTag nbt;
            try (InputStream stream = resource.open()) {
                nbt = NbtIo.readCompressed(stream);
            }

            return parseStructureTemplateNbt(nbt, location.toString());
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[NBT-LOADER] Failed to load schematic '{}': {}", location, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Converts a schematic ID (e.g., "railwaysuntold:station") to its full resource location
     * (e.g., "railwaysuntold:structure/station.nbt").
     */
    public static ResourceLocation schematicIdToResourceLocation(ResourceLocation schematicId) {
        return new ResourceLocation(
                schematicId.getNamespace(),
                "structure/" + schematicId.getPath() + ".nbt"
        );
    }

    /**
     * Parses a Minecraft StructureTemplate NBT into a LoadedSchematic.
     *
     * @param nbt The NBT to parse
     * @param name A name for logging purposes
     * @return The parsed schematic, or null if parsing failed
     */
    @Nullable
    public static LoadedSchematic parseStructureTemplateNbt(CompoundTag nbt, String name) {
        int[] size = parseSchematicSize(nbt, name);
        if (size == null) {
            return null;
        }
        int width = size[0];
        int height = size[1];
        int length = size[2];

        List<BlockState> palette = parsePalette(nbt, name);
        if (palette == null) {
            return null;
        }

        BlockState[] blocks = new BlockState[width * height * length];
        Arrays.fill(blocks, Blocks.AIR.defaultBlockState());
        Map<BlockPos, CompoundTag> blockEntities = new HashMap<>();
        List<CompoundTag> entities = parseEntities(nbt);

        if (!nbt.contains("blocks", Tag.TAG_LIST)) {
            LOGGER.warn("[NBT-LOADER] Schematic '{}' missing 'blocks' tag, returning empty schematic", name);
            return new LoadedSchematic(width, height, length, blocks, blockEntities, entities);
        }

        SchematicBuffer buffer = new SchematicBuffer(palette, width, height, length, blocks, blockEntities);
        parseBlocks(nbt.getList("blocks", Tag.TAG_COMPOUND), buffer);

        return new LoadedSchematic(width, height, length, blocks, blockEntities, entities);
    }

    /**
     * Reads the StructureTemplate 'entities' list. Each entry is kept verbatim (pos, blockPos, nbt);
     * the placer transforms and spawns them. Returns an empty list when the schematic has no entities.
     */
    private static List<CompoundTag> parseEntities(CompoundTag nbt) {
        List<CompoundTag> entities = new ArrayList<>();
        if (!nbt.contains("entities", Tag.TAG_LIST)) {
            return entities;
        }
        ListTag entitiesList = nbt.getList("entities", Tag.TAG_COMPOUND);
        for (int i = 0; i < entitiesList.size(); i++) {
            entities.add(entitiesList.getCompound(i));
        }
        return entities;
    }

    @Nullable
    private static int[] parseSchematicSize(CompoundTag nbt, String name) {
        if (!nbt.contains("size", Tag.TAG_LIST)) {
            LOGGER.error("[NBT-LOADER] Schematic '{}' missing 'size' tag", name);
            return null;
        }

        ListTag sizeTag = nbt.getList("size", Tag.TAG_INT);
        if (sizeTag.size() < 3) {
            LOGGER.error("[NBT-LOADER] Schematic '{}' has invalid size tag", name);
            return null;
        }

        return new int[]{sizeTag.getInt(0), sizeTag.getInt(1), sizeTag.getInt(2)};
    }

    @Nullable
    private static List<BlockState> parsePalette(CompoundTag nbt, String name) {
        if (!nbt.contains("palette", Tag.TAG_LIST)) {
            LOGGER.error("[NBT-LOADER] Schematic '{}' missing 'palette' tag", name);
            return null;
        }

        ListTag paletteTag = nbt.getList("palette", Tag.TAG_COMPOUND);
        List<BlockState> palette = new ArrayList<>();
        int airCount = 0;
        int resolvedCount = 0;

        for (int i = 0; i < paletteTag.size(); i++) {
            CompoundTag entry = paletteTag.getCompound(i);
            String blockName = entry.getString("Name");
            CompoundTag properties = entry.contains("Properties") ? entry.getCompound("Properties") : null;
            BlockState state = parseBlockState(blockName, properties);
            palette.add(state);
            if (state.isAir() && !blockName.equals("minecraft:air")) {
                airCount++;
            } else {
                resolvedCount++;
            }
        }


        return palette;
    }

    private static void parseBlocks(ListTag blocksList, SchematicBuffer buffer) {
        for (int i = 0; i < blocksList.size(); i++) {
            CompoundTag blockEntry = blocksList.getCompound(i);
            processBlockEntry(blockEntry, buffer);
        }
    }

    private static void processBlockEntry(CompoundTag blockEntry, SchematicBuffer buffer) {
        int[] position = parseBlockPosition(blockEntry);
        if (position == null) {
            return;
        }

        int x = position[0];
        int y = position[1];
        int z = position[2];

        if (!buffer.isWithinBounds(x, y, z)) {
            LOGGER.warn("[NBT-LOADER] Block at ({},{},{}) out of bounds, skipping", x, y, z);
            return;
        }

        int stateIndex = blockEntry.getInt("state");
        if (stateIndex < 0 || stateIndex >= buffer.palette.size()) {
            LOGGER.warn("[NBT-LOADER] Invalid palette index {} at ({},{},{})", stateIndex, x, y, z);
            return;
        }

        buffer.blocks[buffer.toIndex(x, y, z)] = buffer.palette.get(stateIndex);

        if (blockEntry.contains("nbt", Tag.TAG_COMPOUND)) {
            buffer.blockEntities.put(new BlockPos(x, y, z), blockEntry.getCompound("nbt"));
        }
    }

    @Nullable
    private static int[] parseBlockPosition(CompoundTag blockEntry) {
        if (blockEntry.contains("pos", Tag.TAG_LIST)) {
            ListTag posTag = blockEntry.getList("pos", Tag.TAG_INT);
            return new int[]{posTag.getInt(0), posTag.getInt(1), posTag.getInt(2)};
        } else if (blockEntry.contains("pos", Tag.TAG_INT_ARRAY)) {
            int[] pos = blockEntry.getIntArray("pos");
            if (pos.length < 3) {
                return null;
            }
            return pos;
        }
        return null;
    }

    private static BlockState parseBlockState(String blockName, @Nullable CompoundTag properties) {
        try {
            ResourceLocation blockLoc = new ResourceLocation(blockName);
            Block block = BuiltInRegistries.BLOCK.get(blockLoc);

            if (block == Blocks.AIR && !blockName.equals("minecraft:air")) {
                LOGGER.warn("[NBT-LOADER] Unknown block: {}", blockName);
                return Blocks.AIR.defaultBlockState();
            }

            BlockState state = block.defaultBlockState();
            state = applyProperties(state, properties);
            return state;
        } catch (RuntimeException e) {
            LOGGER.warn("[NBT-LOADER] Failed to parse block state '{}': {}", blockName, e.getMessage());
            return Blocks.AIR.defaultBlockState();
        }
    }

    private static BlockState applyProperties(BlockState state, @Nullable CompoundTag properties) {
        if (properties == null) {
            return state;
        }
        for (String propName : properties.getAllKeys()) {
            state = applyProperty(state, propName, properties.getString(propName));
        }
        return state;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState applyProperty(BlockState state, String propName, String propValue) {
        Property property = state.getBlock().getStateDefinition().getProperty(propName);
        if (property == null) {
            return state;
        }
        var optValue = property.getValue(propValue);
        if (optValue.isEmpty()) {
            return state;
        }
        return state.setValue(property, (Comparable) optValue.get());
    }
}
