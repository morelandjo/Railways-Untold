package com.vodmordia.railwaysuntold.worldgen.village;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.datapack.SelectedStation;
import com.vodmordia.railwaysuntold.datapack.StationDefinitionLoader;
import com.vodmordia.railwaysuntold.worldgen.integration.create.CreateStationPlacer;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.NbtSchematicLoader;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.SchematicValidator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.biome.Biome;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;


/**
 * Station selection service. Selects a station from data pack definitions
 * filtered by biome with weighted random, falling back to the built-in default.
 */
public class StationSchematicCache {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DEFAULT_STATION_SCHEMATIC = "station";

    // Default station fallback cache (loaded once from built-in resources)
    private static volatile SelectedStation cachedDefault = null;
    private static volatile boolean defaultLoadAttempted = false;
    private static final Object DEFAULT_LOCK = new Object();

    private StationSchematicCache() {
    }

    /**
     * Selects a station appropriate for the given position's biome.
     * Uses weighted random selection from data pack definitions, falling back
     * to the built-in default station if no definitions match.
     *
     * @param level  The server level
     * @param pos    Position to check biome at (e.g. village center, spawn point)
     * @param random Random source for weighted selection
     * @return A SelectedStation, or null if no station could be loaded
     */
    @Nullable
    public static SelectedStation selectStation(ServerLevel level, BlockPos pos, Random random) {
        Holder<Biome> biome = level.getBiome(pos);

        // Try data pack definitions first
        StationDefinitionLoader.ValidatedStationEntry entry =
                StationDefinitionLoader.INSTANCE.getWeightedRandomStation(random, biome, level);

        if (entry != null) {
            return SelectedStation.fromLoaderEntry(entry);
        }

        // Fall back to built-in default
        return getDefaultStation(level);
    }

    /**
     * Returns the built-in default station schematic.
     * Loaded lazily from resources and cached for the server lifetime.
     *
     * @param level The server level (needed for resource manager)
     * @return The default SelectedStation, or null if loading failed
     */
    @Nullable
    public static SelectedStation getDefaultStation(ServerLevel level) {
        if (defaultLoadAttempted) {
            return cachedDefault;
        }

        synchronized (DEFAULT_LOCK) {
            if (defaultLoadAttempted) {
                return cachedDefault;
            }

            ResourceManager resourceManager = level.getServer().getResourceManager();
            NbtSchematicLoader.LoadedSchematic schematic =
                    NbtSchematicLoader.loadFromResources(resourceManager, DEFAULT_STATION_SCHEMATIC);
            if (schematic == null) {
                LOGGER.error("[STATION-SELECT] Failed to load default station schematic '{}.nbt'", DEFAULT_STATION_SCHEMATIC);
                defaultLoadAttempted = true;
                return null;
            }

            SchematicValidator.SchematicValidationResult validation = SchematicValidator.validate(schematic);
            if (!validation.valid) {
                LOGGER.error("[STATION-SELECT] Default station validation failed: {}", validation.errorMessage);
                defaultLoadAttempted = true;
                return null;
            }

            List<BlockPos> stationBlocks = scanStationBlocks(schematic);
            cachedDefault = SelectedStation.fromDefault(schematic, validation, stationBlocks);
            defaultLoadAttempted = true;

            return cachedDefault;
        }
    }

    /**
     * Returns the maximum dimensions across all loaded station definitions,
     * or the default station dimensions if no definitions are loaded.
     * Used by BezierTrackPlacementExecutor for clearance calculations.
     *
     * @param level The server level (fallback to load default)
     * @return Array of [width, height, length] or null
     */
    @Nullable
    public static int[] getMaxDimensions(ServerLevel level) {
        int[] loaderDims = StationDefinitionLoader.INSTANCE.getMaxDimensions();
        if (loaderDims != null) {
            // Include default station dimensions in the max calculation
            SelectedStation defaultStation = getDefaultStation(level);
            if (defaultStation != null) {
                int[] defaultDims = defaultStation.getDimensions();
                return new int[]{
                        Math.max(loaderDims[0], defaultDims[0]),
                        Math.max(loaderDims[1], defaultDims[1]),
                        Math.max(loaderDims[2], defaultDims[2])
                };
            }
            return loaderDims;
        }

        // No data pack stations - use default
        SelectedStation defaultStation = getDefaultStation(level);
        return defaultStation != null ? defaultStation.getDimensions() : null;
    }

    /**
     * Clears the default station cache. Called on server stop / cache clear.
     */
    public static void clearCache() {
        synchronized (DEFAULT_LOCK) {
            cachedDefault = null;
            defaultLoadAttempted = false;
        }
    }

    private static List<BlockPos> scanStationBlocks(NbtSchematicLoader.LoadedSchematic schematic) {
        List<BlockPos> positions = new ArrayList<>();
        for (int y = 0; y < schematic.getHeight(); y++) {
            for (int z = 0; z < schematic.getLength(); z++) {
                for (int x = 0; x < schematic.getWidth(); x++) {
                    if (CreateStationPlacer.isStationBlock(schematic.getBlock(x, y, z))) {
                        positions.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        return Collections.unmodifiableList(positions);
    }
}
