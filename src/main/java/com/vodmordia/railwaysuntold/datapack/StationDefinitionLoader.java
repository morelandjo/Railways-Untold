package com.vodmordia.railwaysuntold.datapack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.worldgen.integration.create.CreateStationPlacer;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.NbtSchematicLoader;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.SchematicValidator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Loads station definitions from data packs at {@code data/<namespace>/railwaysuntold/stations/<name>.json}.
 * Supports multiple station designs selected by weight and biome filters.
 */
public class StationDefinitionLoader extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().create();

    public static final StationDefinitionLoader INSTANCE = new StationDefinitionLoader();

    /**
     * A validated station entry ready for placement.
     */
    public static class ValidatedStationEntry {
        private final StationDefinition definition;
        private final NbtSchematicLoader.LoadedSchematic schematic;
        private final SchematicValidator.SchematicValidationResult validation;
        private volatile List<BlockPos> stationBlockPositions;

        public ValidatedStationEntry(StationDefinition definition,
                                     NbtSchematicLoader.LoadedSchematic schematic,
                                     SchematicValidator.SchematicValidationResult validation) {
            this.definition = definition;
            this.schematic = schematic;
            this.validation = validation;
        }

        public StationDefinition definition() { return definition; }
        public NbtSchematicLoader.LoadedSchematic schematic() { return schematic; }
        public SchematicValidator.SchematicValidationResult validation() { return validation; }

        /**
         * Returns the local positions of Create station blocks within this schematic.
         * Lazily computed and cached.
         */
        public List<BlockPos> getStationBlockPositions() {
            if (stationBlockPositions == null) {
                stationBlockPositions = scanStationBlocks(schematic);
            }
            return stationBlockPositions;
        }

        private static List<BlockPos> scanStationBlocks(NbtSchematicLoader.LoadedSchematic schematic) {
            List<BlockPos> positions = new ArrayList<>();
            for (int y = 0; y < schematic.getHeight(); y++) {
                for (int z = 0; z < schematic.getLength(); z++) {
                    for (int x = 0; x < schematic.getWidth(); x++) {
                        BlockState state = schematic.getBlock(x, y, z);
                        if (CreateStationPlacer.isStationBlock(state)) {
                            positions.add(new BlockPos(x, y, z));
                        }
                    }
                }
            }
            return Collections.unmodifiableList(positions);
        }
    }

    private volatile List<ValidatedStationEntry> entries = Collections.emptyList();
    private volatile int[] maxDimensions = null;

    private StationDefinitionLoader() {
        super(GSON, "railwaysuntold/stations");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> jsons, ResourceManager resourceManager, ProfilerFiller profiler) {
        List<ValidatedStationEntry> newEntries = new ArrayList<>();
        int total = 0;

        for (Map.Entry<ResourceLocation, JsonElement> entry : jsons.entrySet()) {
            total++;
            ResourceLocation id = entry.getKey();
            try {
                JsonObject json = entry.getValue().getAsJsonObject();
                StationDefinition definition = parseDefinition(id, json);

                ResourceLocation nbtLocation = NbtSchematicLoader.schematicIdToResourceLocation(definition.schematic());
                NbtSchematicLoader.LoadedSchematic schematic = NbtSchematicLoader.loadFromResources(resourceManager, nbtLocation);
                if (schematic == null) {
                    LOGGER.warn("[STATION-DATAPACK] Failed to load schematic for station '{}': {}", id, nbtLocation);
                    continue;
                }

                SchematicValidator.SchematicValidationResult validation = SchematicValidator.validate(schematic);
                if (!validation.valid) {
                    LOGGER.warn("[STATION-DATAPACK] Validation failed for station '{}': {}", id, validation.errorMessage);
                    continue;
                }

                newEntries.add(new ValidatedStationEntry(definition, schematic, validation));
                LOGGER.info("[STATION-DATAPACK] Loaded station definition: {}", id);
            } catch (Exception e) {
                LOGGER.error("[STATION-DATAPACK] Failed to parse station definition '{}': {}", id, e.getMessage(), e);
            }
        }

        this.entries = Collections.unmodifiableList(newEntries);
        this.maxDimensions = computeMaxDimensions(newEntries);
        LOGGER.info("[STATION-DATAPACK] Loaded {} valid station definitions out of {} total", newEntries.size(), total);
    }

    private static StationDefinition parseDefinition(ResourceLocation id, JsonObject json) {
        String schematicStr = json.get("schematic").getAsString();
        ResourceLocation schematic = ResourceLocation.parse(schematicStr);

        int weight = json.has("weight") ? json.get("weight").getAsInt() : StationDefinition.DEFAULT_WEIGHT;

        Optional<String> foundationBlock = json.has("foundation_block")
                ? Optional.of(json.get("foundation_block").getAsString())
                : Optional.empty();

        List<BiomeFilter> whitelist = EventDefinitionLoader.parseBiomeFilters(json, "biome_whitelist");
        List<BiomeFilter> blacklist = EventDefinitionLoader.parseBiomeFilters(json, "biome_blacklist");

        LootConfig loot = LootConfig.fromJson(json);
        int maxAppearances = json.has("max_appearances") ? json.get("max_appearances").getAsInt() : StationDefinition.UNLIMITED;

        return new StationDefinition(id, schematic, weight, foundationBlock, whitelist, blacklist, loot, maxAppearances);
    }

    /**
     * Selects a random station filtered by biome and appearance limits using weighted random selection.
     */
    @Nullable
    public ValidatedStationEntry getWeightedRandomStation(Random random, Holder<Biome> biome, ServerLevel level) {
        AppearanceTracker tracker = AppearanceTracker.get(level);
        List<ValidatedStationEntry> candidates = entries.stream()
                .filter(e -> BiomeFilter.matchesFilters(biome, e.definition().biomeWhitelist(), e.definition().biomeBlacklist()))
                .filter(e -> tracker.canAppear(e.definition().id(), e.definition().maxAppearances()))
                .toList();

        return WeightedRandomPicker.pick(candidates, e -> e.definition().weight(), random);
    }

    /**
     * Returns the maximum dimensions across all loaded station schematics.
     *
     * @return Array of [width, height, length] or null if no entries loaded
     */
    @Nullable
    public int[] getMaxDimensions() {
        return maxDimensions;
    }

    private static int[] computeMaxDimensions(List<ValidatedStationEntry> entries) {
        if (entries.isEmpty()) return null;
        int maxW = 0, maxH = 0, maxL = 0;
        for (ValidatedStationEntry entry : entries) {
            maxW = Math.max(maxW, entry.schematic().getWidth());
            maxH = Math.max(maxH, entry.schematic().getHeight());
            maxL = Math.max(maxL, entry.schematic().getLength());
        }
        return new int[]{maxW, maxH, maxL};
    }
}
