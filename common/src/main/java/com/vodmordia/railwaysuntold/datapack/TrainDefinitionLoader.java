package com.vodmordia.railwaysuntold.datapack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.NbtSchematicLoader;
import com.vodmordia.railwaysuntold.worldgen.train.TrainSchematicValidator;
import com.vodmordia.railwaysuntold.worldgen.train.TrainValidationData.ValidationResult;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.biome.Biome;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.util.*;

/**
 * Loads train definitions from data packs at {@code data/<namespace>/railwaysuntold/trains/<name>.json}.
 */
public class TrainDefinitionLoader extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().create();

    public static final TrainDefinitionLoader INSTANCE = new TrainDefinitionLoader();

    /**
     * A validated train entry ready for placement.
     */
    public record ValidatedTrainEntry(
            TrainDefinition definition,
            ValidationResult validation,
            CompoundTag schematicNbt
    ) {}

    private volatile List<ValidatedTrainEntry> entries = Collections.emptyList();
    private volatile List<String> loadErrors = Collections.emptyList();

    private TrainDefinitionLoader() {
        super(GSON, "railwaysuntold/trains");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> jsons, ResourceManager resourceManager, ProfilerFiller profiler) {
        List<ValidatedTrainEntry> newEntries = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int total = 0;

        for (Map.Entry<ResourceLocation, JsonElement> entry : jsons.entrySet()) {
            total++;
            ResourceLocation id = entry.getKey();
            try {
                JsonObject json = entry.getValue().getAsJsonObject();
                TrainDefinition definition = parseDefinition(id, json);

                ResourceLocation nbtLocation = NbtSchematicLoader.schematicIdToResourceLocation(definition.schematic());
                Resource resource = resourceManager.getResource(nbtLocation).orElse(null);
                if (resource == null) {
                    String error = "Schematic not found for train '" + id + "': " + nbtLocation;
                    LOGGER.warn("[TRAIN-DATAPACK] {}", error);
                    errors.add(error);
                    continue;
                }

                CompoundTag nbt;
                try (InputStream stream = resource.open()) {
                    nbt = NbtIo.readCompressed(stream);
                }

                ValidationResult validation = TrainSchematicValidator.loadAndValidateFlexible(nbt);
                if (!validation.valid) {
                    String error = "Validation failed for train '" + id + "': " + validation.errorMessage;
                    LOGGER.warn("[TRAIN-DATAPACK] {}", error);
                    errors.add(error);
                    continue;
                }

                newEntries.add(new ValidatedTrainEntry(definition, validation, nbt));
                LOGGER.info("[TRAIN-DATAPACK] Loaded train definition: {}", id);
            } catch (Exception e) {
                String error = "Failed to parse train definition '" + id + "': " + e.getMessage();
                LOGGER.error("[TRAIN-DATAPACK] {}", error, e);
                errors.add(error);
            }
        }

        this.entries = Collections.unmodifiableList(newEntries);
        this.loadErrors = Collections.unmodifiableList(errors);
        LOGGER.info("[TRAIN-DATAPACK] Loaded {} valid train definitions out of {} total", newEntries.size(), total);
    }

    private static TrainDefinition parseDefinition(ResourceLocation id, JsonObject json) {
        String schematicStr = json.get("schematic").getAsString();
        ResourceLocation schematic = new ResourceLocation(schematicStr);

        String frontFacing = json.has("front_facing")
                ? json.get("front_facing").getAsString()
                : TrainDefinition.DEFAULT_FRONT_FACING;

        int weight = json.has("weight") ? json.get("weight").getAsInt() : TrainDefinition.DEFAULT_WEIGHT;

        List<BiomeFilter> whitelist = EventDefinitionLoader.parseBiomeFilters(json, "biome_whitelist");
        List<BiomeFilter> blacklist = EventDefinitionLoader.parseBiomeFilters(json, "biome_blacklist");

        LootConfig loot = LootConfig.fromJson(json);
        return new TrainDefinition(id, schematic, frontFacing, weight, whitelist, blacklist, loot);
    }

    /**
     * Selects a random train filtered by biome using weighted random selection.
     */
    @Nullable
    public ValidatedTrainEntry getWeightedRandomTrain(Random random, Holder<Biome> biome) {
        List<ValidatedTrainEntry> candidates = entries.stream()
                .filter(e -> BiomeFilter.matchesFilters(biome, e.definition().biomeWhitelist(), e.definition().biomeBlacklist()))
                .toList();

        return WeightedRandomPicker.pick(candidates, e -> e.definition().weight(), random);
    }

    /**
     * Returns true if at least one valid train definition is loaded.
     */
    public boolean hasValidEntries() {
        return !entries.isEmpty();
    }

    public List<String> getLoadErrors() {
        return loadErrors;
    }

    public boolean hasLoadErrors() {
        return !loadErrors.isEmpty();
    }
}
