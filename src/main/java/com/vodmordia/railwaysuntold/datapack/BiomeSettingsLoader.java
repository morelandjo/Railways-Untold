package com.vodmordia.railwaysuntold.datapack;

import com.google.gson.*;
import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.config.ConfigBlockLoader;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.NbtSchematicLoader;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads biome settings definitions from data packs at
 * {@code data/<namespace>/railwaysuntold/biome_settings/<name>.json}.
 *
 * Resolves per-biome settings by layering matching definitions in priority order.
 * Higher priority definitions override lower ones; at equal priority, more specific
 * biome filters (direct IDs) win over less specific ones (tags).
 */
public class BiomeSettingsLoader extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().create();

    public static final BiomeSettingsLoader INSTANCE = new BiomeSettingsLoader();

    /**
     * Aggregated structure settings from all biome settings entries (regardless of biome filter).
     * Structure targeting is a world-level decision, so all entries contribute.
     */
    public record StructureSettings(List<String> targetTags, List<String> avoidanceBlacklist, List<String> undergroundList) {}

    /**
     * Raw (pre-resolution) biome settings as nullable strings/integers, layered from matching definitions.
     * First-non-null wins, so {@link #merge} takes from {@code def} only where this record's field is null.
     */
    private record RawBiomeSettings(
            String tunnelFacade, String terrainFill, String terrainFillBase,
            String stationFoundation, String lighting, Integer lightingAtt, String trackMat,
            String bridgeDecking, String bridgeEnd, Integer bridgeHalfWidth,
            String bridgeAbutment, String bridgePier, Integer bridgePillarDeckRow,
            String bridgeRailing) {

        static RawBiomeSettings empty() {
            return new RawBiomeSettings(null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        }

        RawBiomeSettings merge(BiomeSettingsDefinition def) {
            return new RawBiomeSettings(
                    tunnelFacade != null ? tunnelFacade : def.tunnelFacadeBlock().orElse(null),
                    terrainFill != null ? terrainFill : def.terrainFillBlock().orElse(null),
                    terrainFillBase != null ? terrainFillBase : def.terrainFillBaseBlock().orElse(null),
                    stationFoundation != null ? stationFoundation : def.stationFoundationBlock().orElse(null),
                    lighting != null ? lighting : def.lightingBlock().orElse(null),
                    lightingAtt != null ? lightingAtt : def.lightingAttachment().orElse(null),
                    trackMat != null ? trackMat : def.trackMaterial().orElse(null),
                    bridgeDecking != null ? bridgeDecking : def.bridgeDeckingNbt().orElse(null),
                    bridgeEnd != null ? bridgeEnd : def.bridgeEndNbt().orElse(null),
                    bridgeHalfWidth != null ? bridgeHalfWidth : def.bridgeHalfWidth().orElse(null),
                    bridgeAbutment != null ? bridgeAbutment : def.bridgeAbutmentNbt().orElse(null),
                    bridgePier != null ? bridgePier : def.bridgePierNbt().orElse(null),
                    bridgePillarDeckRow != null ? bridgePillarDeckRow : def.bridgePillarDeckRow().orElse(null),
                    bridgeRailing != null ? bridgeRailing : def.bridgeRailingNbt().orElse(null));
        }
    }

    private volatile List<BiomeSettingsDefinition> entries = Collections.emptyList();
    private final Map<ResourceLocation, ResolvedBiomeSettings> cache = new ConcurrentHashMap<>();
    private volatile ResolvedBiomeSettings globalDefaults;
    private volatile StructureSettings cachedStructureSettings;
    private volatile Set<Block> allOverrideBlocks = Collections.emptySet();

    private BiomeSettingsLoader() {
        super(GSON, "railwaysuntold/biome_settings");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> jsons, ResourceManager resourceManager, ProfilerFiller profiler) {
        List<BiomeSettingsDefinition> newEntries = new ArrayList<>();
        int total = 0;

        for (Map.Entry<ResourceLocation, JsonElement> entry : jsons.entrySet()) {
            total++;
            ResourceLocation id = entry.getKey();
            try {
                JsonObject json = entry.getValue().getAsJsonObject();
                BiomeSettingsDefinition definition = parseDefinition(id, json);
                newEntries.add(definition);
                LOGGER.info("[BIOME-SETTINGS] Loaded biome settings definition: {}", id);
            } catch (Exception e) {
                LOGGER.error("[BIOME-SETTINGS] Failed to parse biome settings '{}': {}", id, e.getMessage(), e);
            }
        }

        // Sort by priority descending, then specificity descending
        newEntries.sort(Comparator
                .<BiomeSettingsDefinition>comparingInt(d -> -d.priority())
                .thenComparingInt(d -> -d.specificityScore()));

        this.entries = Collections.unmodifiableList(newEntries);
        this.cache.clear();
        this.globalDefaults = null;
        this.cachedStructureSettings = null;

        // Build set of all blocks referenced by override entries for isAnyFacadeBlock checks
        rebuildOverrideBlockSet();

        LOGGER.info("[BIOME-SETTINGS] Loaded {} biome settings definitions out of {} total", newEntries.size(), total);
    }

    private void rebuildOverrideBlockSet() {
        Set<Block> blocks = new HashSet<>();
        for (BiomeSettingsDefinition def : entries) {
            def.tunnelFacadeBlock().ifPresent(id -> blocks.add(ConfigBlockLoader.loadBlockState(id).getBlock()));
            def.terrainFillBlock().ifPresent(id -> blocks.add(ConfigBlockLoader.loadBlockState(id).getBlock()));
            def.terrainFillBaseBlock().ifPresent(id -> blocks.add(ConfigBlockLoader.loadBlockState(id).getBlock()));
            def.stationFoundationBlock().ifPresent(id -> blocks.add(ConfigBlockLoader.loadBlockState(id).getBlock()));
            def.lightingBlock().ifPresent(id -> blocks.add(ConfigBlockLoader.loadBlockState(id).getBlock()));
        }
        this.allOverrideBlocks = Collections.unmodifiableSet(blocks);
    }

    /**
     * Resolves the effective settings for a biome by layering matching definitions.
     * Results are cached per biome.
     */
    public ResolvedBiomeSettings resolve(Holder<Biome> biome) {
        ResourceLocation biomeId = biome.unwrapKey()
                .map(key -> key.location())
                .orElse(null);
        if (biomeId == null) {
            return resolveGlobal();
        }
        return cache.computeIfAbsent(biomeId, id -> buildResolved(biome));
    }

    /**
     * Resolves settings without biome context (global defaults only).
     * Used for settings like structure tags and track material where no biome is available.
     */
    public ResolvedBiomeSettings resolveGlobal() {
        ResolvedBiomeSettings cached = globalDefaults;
        if (cached != null) return cached;
        cached = buildResolvedGlobal();
        globalDefaults = cached;
        return cached;
    }

    /**
     * Resolves structure settings by aggregating across ALL entries (regardless of biome filter).
     * Structure targeting, avoidance, and underground lists are world-level decisions.
     */
    public StructureSettings resolveStructureSettings() {
        StructureSettings cached = cachedStructureSettings;
        if (cached != null) return cached;
        cached = buildStructureSettings();
        cachedStructureSettings = cached;
        return cached;
    }

    private StructureSettings buildStructureSettings() {
        java.util.LinkedHashSet<String> allTargets = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> allAvoidance = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> allUnderground = new java.util.LinkedHashSet<>();
        for (BiomeSettingsDefinition def : entries) {
            def.structureTargetTags().ifPresent(allTargets::addAll);
            def.structureAvoidanceBlacklist().ifPresent(allAvoidance::addAll);
            def.structureUndergroundList().ifPresent(allUnderground::addAll);
        }
        return new StructureSettings(
                List.copyOf(allTargets),
                List.copyOf(allAvoidance),
                List.copyOf(allUnderground));
    }

    /**
     * Returns true if the block is used by any biome settings entry (for facade detection).
     */
    public boolean isOverrideBlock(Block block) {
        return allOverrideBlocks.contains(block);
    }

    public List<BiomeSettingsDefinition> getEntries() {
        return entries;
    }

    private ResolvedBiomeSettings buildResolved(Holder<Biome> biome) {
        RawBiomeSettings raw = RawBiomeSettings.empty();
        for (BiomeSettingsDefinition def : entries) {
            if (def.matchesBiome(biome)) {
                raw = raw.merge(def);
            }
        }
        return buildFromRaw(raw);
    }

    private ResolvedBiomeSettings buildResolvedGlobal() {
        // Only match entries with empty biome filter (global defaults)
        RawBiomeSettings raw = RawBiomeSettings.empty();
        for (BiomeSettingsDefinition def : entries) {
            if (!def.biomeFilter().isEmpty()) continue;
            raw = raw.merge(def);
        }
        return buildFromRaw(raw);
    }

    private static ResolvedBiomeSettings buildFromRaw(RawBiomeSettings raw) {
        // Hardcoded fallbacks in case no datapack provides defaults (should never happen with built-in defaults.json)
        BlockState tunnelFacadeBlock = ConfigBlockLoader.loadBlockState(raw.tunnelFacade() != null ? raw.tunnelFacade() : "minecraft:stone_bricks");
        BlockState terrainFillBlock = ConfigBlockLoader.loadBlockState(raw.terrainFill() != null ? raw.terrainFill() : "minecraft:gravel");
        BlockState terrainFillBaseBlock = ConfigBlockLoader.loadBlockState(raw.terrainFillBase() != null ? raw.terrainFillBase() : "minecraft:cobblestone");
        BlockState stationFoundationBlock = ConfigBlockLoader.loadBlockState(raw.stationFoundation() != null ? raw.stationFoundation() : "minecraft:cobblestone");
        BlockState lightingBlock = ConfigBlockLoader.loadBlockState(raw.lighting() != null ? raw.lighting() : "minecraft:wall_torch");

        ResourceLocation bridgeDeckingId = resolveSchematicId(raw.bridgeDecking(), "railwaysuntold:bridge_cross_section");
        ResourceLocation bridgeEndId = resolveSchematicId(raw.bridgeEnd(), "railwaysuntold:decking_end");
        ResourceLocation bridgeAbutmentId = resolveSchematicId(raw.bridgeAbutment(), "railwaysuntold:bridge_abutment");
        ResourceLocation bridgePierId = resolveSchematicId(raw.bridgePier(), "railwaysuntold:bridge_pillar");
        ResourceLocation bridgeRailingId = resolveSchematicId(raw.bridgeRailing(), "railwaysuntold:bridge_railing");

        return new ResolvedBiomeSettings(
                tunnelFacadeBlock,
                terrainFillBlock,
                terrainFillBaseBlock,
                stationFoundationBlock,
                lightingBlock,
                raw.lightingAtt() != null ? raw.lightingAtt() : 1,
                raw.trackMat() != null ? raw.trackMat() : "andesite",
                bridgeDeckingId,
                bridgeEndId,
                raw.bridgeHalfWidth() != null ? raw.bridgeHalfWidth() : 3,
                bridgeAbutmentId,
                bridgePierId,
                raw.bridgePillarDeckRow() != null ? raw.bridgePillarDeckRow() : 0,
                bridgeRailingId
        );
    }

    private static ResourceLocation resolveSchematicId(String raw, String fallback) {
        String value = raw != null ? raw : fallback;
        ResourceLocation shortId = ResourceLocation.parse(value);
        return NbtSchematicLoader.schematicIdToResourceLocation(shortId);
    }

    private static BiomeSettingsDefinition parseDefinition(ResourceLocation id, JsonObject json) {
        List<BiomeFilter> biomeFilter = EventDefinitionLoader.parseBiomeFilters(json, "biome_filter");
        int priority = json.has("priority") ? json.get("priority").getAsInt() : BiomeSettingsDefinition.DEFAULT_PRIORITY;

        Optional<String> tunnelFacadeBlock = optString(json, "tunnel_facade_block");
        Optional<String> terrainFillBlock = optString(json, "terrain_fill_block");
        Optional<String> terrainFillBaseBlock = optString(json, "terrain_fill_base_block");
        Optional<String> stationFoundationBlock = optString(json, "station_foundation_block");
        Optional<String> lightingBlock = optString(json, "lighting_block");
        Optional<Integer> lightingAttachment = json.has("lighting_attachment")
                ? Optional.of(json.get("lighting_attachment").getAsInt())
                : Optional.empty();
        Optional<String> trackMaterial = optString(json, "track_material");
        Optional<List<String>> structureTargetTags = optStringList(json, "structure_target_tags");
        Optional<List<String>> structureAvoidanceBlacklist = optStringList(json, "structure_avoidance_blacklist");
        Optional<List<String>> structureUndergroundList = optStringList(json, "structure_underground_list");
        Optional<String> bridgeDeckingNbt = optString(json, "bridge_decking_nbt");
        Optional<String> bridgeEndNbt = optString(json, "bridge_end_nbt");
        Optional<Integer> bridgeHalfWidth = json.has("bridge_half_width")
                ? Optional.of(json.get("bridge_half_width").getAsInt())
                : Optional.empty();
        Optional<String> bridgeAbutmentNbt = optString(json, "bridge_abutment_nbt");
        Optional<String> bridgePierNbt = optString(json, "bridge_pier_nbt");
        Optional<Integer> bridgePillarDeckRow = json.has("bridge_pillar_deck_row")
                ? Optional.of(json.get("bridge_pillar_deck_row").getAsInt())
                : Optional.empty();
        Optional<String> bridgeRailingNbt = optString(json, "bridge_railing_nbt");

        return new BiomeSettingsDefinition(id, biomeFilter, priority,
                tunnelFacadeBlock, terrainFillBlock, terrainFillBaseBlock,
                stationFoundationBlock, lightingBlock, lightingAttachment,
                trackMaterial,
                structureTargetTags, structureAvoidanceBlacklist, structureUndergroundList,
                bridgeDeckingNbt, bridgeEndNbt, bridgeHalfWidth,
                bridgeAbutmentNbt, bridgePierNbt, bridgePillarDeckRow,
                bridgeRailingNbt);
    }

    private static Optional<String> optString(JsonObject json, String key) {
        return json.has(key) ? Optional.of(json.get(key).getAsString()) : Optional.empty();
    }

    private static Optional<List<String>> optStringList(JsonObject json, String key) {
        if (!json.has(key)) return Optional.empty();
        JsonArray array = json.getAsJsonArray(key);
        List<String> list = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            list.add(element.getAsString());
        }
        return Optional.of(Collections.unmodifiableList(list));
    }
}
