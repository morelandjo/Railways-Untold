package com.vodmordia.railwaysuntold.datapack;

import com.google.gson.*;
import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.NbtSchematicLoader;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.SchematicValidator;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.biome.Biome;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Loads event definitions from data packs at {@code data/<namespace>/railwaysuntold/events/<name>.json}.
 * Each JSON defines an event schematic with weight and biome filters.
 * Participates in Minecraft's reload system ({@code /reload}).
 */
public class EventDefinitionLoader extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().create();

    public static final EventDefinitionLoader INSTANCE = new EventDefinitionLoader();

    /**
     * A validated event entry ready for placement.
     * Sealed interface with NBT and Jigsaw variants.
     */
    public sealed interface ValidatedEventEntry {
        EventDefinition definition();

        record NbtEntry(
                EventDefinition definition,
                NbtSchematicLoader.LoadedSchematic schematic,
                SchematicValidator.SchematicValidationResult validation
        ) implements ValidatedEventEntry {}

        record JigsawEntry(
                EventDefinition definition
        ) implements ValidatedEventEntry {}
    }

    private volatile List<ValidatedEventEntry> entries = Collections.emptyList();

    private final Deque<ResourceLocation> forcedQueue = new ArrayDeque<>();

    private EventDefinitionLoader() {
        super(GSON, "railwaysuntold/events");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> jsons, ResourceManager resourceManager, ProfilerFiller profiler) {
        List<ValidatedEventEntry> newEntries = new ArrayList<>();
        int total = 0;

        for (Map.Entry<ResourceLocation, JsonElement> entry : jsons.entrySet()) {
            total++;
            ResourceLocation id = entry.getKey();
            try {
                JsonObject json = entry.getValue().getAsJsonObject();
                EventDefinition definition = parseDefinition(id, json);

                ValidatedEventEntry validatedEntry = switch (definition.eventType()) {
                    case NBT -> loadNbtEntry(id, definition, resourceManager);
                    case JIGSAW -> loadJigsawEntry(id, definition);
                };

                if (validatedEntry == null) continue;

                newEntries.add(validatedEntry);
                LOGGER.info("[EVENT-DATAPACK] Loaded {} event definition: {}", definition.eventType(), id);
            } catch (Exception e) {
                LOGGER.error("[EVENT-DATAPACK] Failed to parse event definition '{}': {}", id, e.getMessage(), e);
            }
        }

        this.entries = Collections.unmodifiableList(newEntries);
        LOGGER.info("[EVENT-DATAPACK] Loaded {} valid event definitions out of {} total", newEntries.size(), total);
    }

    @Nullable
    private static ValidatedEventEntry loadNbtEntry(ResourceLocation id, EventDefinition definition,
                                                     ResourceManager resourceManager) {
        ResourceLocation nbtLocation = NbtSchematicLoader.schematicIdToResourceLocation(definition.schematic());
        NbtSchematicLoader.LoadedSchematic schematic = NbtSchematicLoader.loadFromResources(resourceManager, nbtLocation);
        if (schematic == null) {
            LOGGER.warn("[EVENT-DATAPACK] Failed to load schematic for event '{}': {}", id, nbtLocation);
            return null;
        }

        SchematicValidator.SchematicValidationResult validation = SchematicValidator.validate(schematic);
        if (!validation.valid) {
            LOGGER.warn("[EVENT-DATAPACK] Validation failed for event '{}': {}", id, validation.errorMessage);
            return null;
        }

        return new ValidatedEventEntry.NbtEntry(definition, schematic, validation);
    }

    @Nullable
    private static ValidatedEventEntry loadJigsawEntry(ResourceLocation id, EventDefinition definition) {
        if (definition.structure() == null) {
            LOGGER.warn("[EVENT-DATAPACK] Jigsaw event '{}' missing 'structure' field", id);
            return null;
        }
        LOGGER.info("[EVENT-DATAPACK] Registered jigsaw event '{}' -> structure '{}'", id, definition.structure());
        return new ValidatedEventEntry.JigsawEntry(definition);
    }

    private static EventDefinition parseDefinition(ResourceLocation id, JsonObject json) {
        String typeStr = json.has("type") ? json.get("type").getAsString() : "nbt";
        EventDefinition.EventType eventType = EventDefinition.EventType.valueOf(typeStr.toUpperCase());

        ResourceLocation schematic = null;
        ResourceLocation structure = null;
        int estimatedFootprint = EventDefinition.DEFAULT_JIGSAW_FOOTPRINT;

        if (eventType == EventDefinition.EventType.NBT) {
            schematic = new ResourceLocation(json.get("schematic").getAsString());
        } else {
            structure = new ResourceLocation(json.get("structure").getAsString());
            if (json.has("estimated_footprint")) {
                estimatedFootprint = json.get("estimated_footprint").getAsInt();
            }
        }

        int weight = json.has("weight") ? json.get("weight").getAsInt() : EventDefinition.DEFAULT_WEIGHT;

        List<BiomeFilter> whitelist = parseBiomeFilters(json, "biome_whitelist");
        List<BiomeFilter> blacklist = parseBiomeFilters(json, "biome_blacklist");

        LootConfig loot = LootConfig.fromJson(json);
        List<EventTrigger> triggers = parseTriggers(json);
        int maxAppearances = json.has("max_appearances") ? json.get("max_appearances").getAsInt() : EventDefinition.UNLIMITED;

        return new EventDefinition(id, eventType, schematic, structure, weight, whitelist, blacklist,
                loot, triggers, maxAppearances, estimatedFootprint);
    }

    static List<BiomeFilter> parseBiomeFilters(JsonObject json, String key) {
        if (!json.has(key)) {
            return Collections.emptyList();
        }
        JsonArray array = json.getAsJsonArray(key);
        if (array.size() == 0) {
            return Collections.emptyList();
        }
        List<BiomeFilter> filters = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            filters.add(BiomeFilter.parse(element.getAsString()));
        }
        return Collections.unmodifiableList(filters);
    }

    static List<EventTrigger> parseTriggers(JsonObject json) {
        if (!json.has("triggers")) {
            return Collections.emptyList();
        }
        JsonArray array = json.getAsJsonArray("triggers");
        if (array.size() == 0) {
            return Collections.emptyList();
        }
        List<EventTrigger> triggers = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            triggers.add(EventTrigger.parse(element.getAsJsonObject()));
        }
        return Collections.unmodifiableList(triggers);
    }

    /**
     * Selects a random event filtered by biome using weighted random selection.
     */
    @Nullable
    public ValidatedEventEntry getWeightedRandomEvent(Random random, Holder<Biome> biome, TriggerContext triggerContext) {
        ValidatedEventEntry forced = consumeForcedEvent();
        if (forced != null) {
            return forced;
        }

        AppearanceTracker tracker = AppearanceTracker.get(triggerContext.level());
        List<ValidatedEventEntry> candidates = entries.stream()
                .filter(e -> BiomeFilter.matchesFilters(biome, e.definition().biomeWhitelist(), e.definition().biomeBlacklist()))
                .filter(e -> tracker.canAppear(e.definition().id(), e.definition().maxAppearances()))
                .filter(e -> EventTrigger.allMatch(e.definition().triggers(), triggerContext))
                .toList();

        return WeightedRandomPicker.pick(candidates, e -> e.definition().weight(), random);
    }

    @Nullable
    private synchronized ValidatedEventEntry consumeForcedEvent() {
        while (!forcedQueue.isEmpty()) {
            ResourceLocation forcedId = forcedQueue.poll();
            for (ValidatedEventEntry entry : entries) {
                if (entry.definition().id().equals(forcedId)) {
                    LOGGER.info("[EVENT-DATAPACK] Forced next event: {}", forcedId);
                    return entry;
                }
            }
            LOGGER.warn("[EVENT-DATAPACK] Forced event '{}' not found in registry - dropped", forcedId);
        }
        return null;
    }

    /**
     * Queues an event id to be served as the next event, ahead of weighted random
     * selection. Consumed once by the next {@link #getWeightedRandomEvent} call; if
     * the id is not a loaded event it is dropped with a warning.
     */
    public synchronized void forceNextEvent(ResourceLocation eventId) {
        forcedQueue.offer(eventId);
    }

    /**
     * Returns true if at least one valid event definition is loaded.
     */
    public boolean hasValidEntries() {
        return !entries.isEmpty();
    }

}
