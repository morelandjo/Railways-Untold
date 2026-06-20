package com.vodmordia.railwaysuntold.worldgen.integration.railway;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.datapack.BiomeSettingsLoader;
import com.vodmordia.railwaysuntold.worldgen.integration.create.CreateReflectionFields;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.CreateTrackUtil;
import com.vodmordia.railwaysuntold.worldgen.train.CreateTrainUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Soft dependency integration with Steam 'n' Rails (Railway) mod.
 * Resolves configured track material + gauge into the correct TrackMaterial
 * and updates CreateTrackUtil with the resolved material and block.
 *
 */
public final class RailwayModCompat {

    private static final Logger LOGGER = LogUtils.getLogger();

    private RailwayModCompat() {
    }

    /** Cached resolved materials keyed by lowercase material name. */
    private static final Map<String, ResolvedMaterial> materialCache = new HashMap<>();

    record ResolvedMaterial(Object trackMaterial, Object trackBlock) {}

    /**
     * Initializes Railway mod compatibility.
     * Reads material/gauge config, pre-resolves all track materials from biome settings,
     * and sets the global default.
     */
    public static void initialize() {
        // Always reset to defaults first so config changes between server restarts take effect
        resetToDefaults();
        materialCache.clear();

        // Always cache andesite as the default
        Object defaultMaterial = CreateTrackUtil.getDefaultAndesiteMaterial();
        if (defaultMaterial != null) {
            materialCache.put("andesite", new ResolvedMaterial(defaultMaterial, null));
        }

        String gauge = RailwaysUntoldConfig.getTrackGauge().toLowerCase();

        // Collect all unique material names from ALL biome settings entries
        Set<String> allMaterials = new HashSet<>();
        for (var def : BiomeSettingsLoader.INSTANCE.getEntries()) {
            def.trackMaterial().ifPresent(m -> allMaterials.add(m.toLowerCase()));
        }
        // Also include the global default
        String globalMaterial = BiomeSettingsLoader.INSTANCE.resolveGlobal().getTrackMaterial().toLowerCase();
        allMaterials.add(globalMaterial);


        // Check if any non-andesite/non-standard materials need Railway mod
        boolean needsRailway = allMaterials.stream().anyMatch(m -> !"andesite".equals(m))
                || !"standard".equals(gauge);

        if (!needsRailway) {
            return;
        }

        boolean railwayPresent = isRailwayModPresent();

        if (!railwayPresent) {
            return;
        }

        // Pre-resolve all unique materials
        for (String material : allMaterials) {
            if ("andesite".equals(material) && "standard".equals(gauge)) {
                continue; // Already cached as default
            }
            ResolvedMaterial resolved = resolveMaterial(material, gauge);
            if (resolved != null) {
                materialCache.put(material, resolved);
            }
        }

        // Apply the global default material
        applyMaterial(globalMaterial);
    }

    /**
     * Applies the track material for the biome at the given position.
     */
    public static void applyMaterialForBiome(ServerLevel level, BlockPos pos) {
        String material = BiomeSettingsLoader.INSTANCE.resolve(level.getBiome(pos))
                .getTrackMaterial().toLowerCase();
        applyMaterial(material);
    }

    private static void applyMaterial(String material) {
        ResolvedMaterial resolved = materialCache.get(material);
        if (resolved != null) {
            CreateTrackUtil.setActiveTrackMaterial(resolved.trackMaterial());
            CreateTrackUtil.setActiveTrackBlock(resolved.trackBlock());
        }
    }

    /**
     * Returns the appropriate bogey block for the configured track gauge.
     * Uses Railway mod's wide/narrow bogeys when available, falls back to Create's small bogey.
     */
    @Nullable
    public static AbstractBogeyBlock<?> getDefaultBogeyBlockForGauge() {
        String gauge = RailwaysUntoldConfig.getTrackGauge().toLowerCase();
        boolean railwayPresent = isRailwayModPresent();

        if (!"standard".equals(gauge) && railwayPresent) {
            String bogeyName = switch (gauge) {
                case "wide" -> "railways:wide_doubleaxle_bogey";
                case "narrow" -> "railways:narrow_small_bogey";
                default -> null;
            };                

            if (bogeyName != null) {
                AbstractBogeyBlock<?> bogey = CreateTrainUtils.getBogeyBlockByName(bogeyName);                    
                if (bogey != null) {
                    return bogey;
                }
                LOGGER.warn("[RAILWAY-COMPAT] Could not find bogey block '{}' - falling back to standard", bogeyName);
            }
        }

        AbstractBogeyBlock<?> fallback = CreateTrainUtils.getSmallBogeyBlock();            
        return fallback;
    }

    /**
     * Returns the appropriate bogey style ID for the configured track gauge.
     * Uses Railway mod's wide/narrow styles when available, falls back to Create's standard style.
     */
    @Nullable
    public static ResourceLocation getDefaultBogeyStyleForGauge() {
        String gauge = RailwaysUntoldConfig.getTrackGauge().toLowerCase();
        boolean railwayPresent = isRailwayModPresent();            

        if (!"standard".equals(gauge) && railwayPresent) {
            ResourceLocation styleId = switch (gauge) {
                case "wide" -> ResourceLocation.fromNamespaceAndPath("railways", "wide_default");
                case "narrow" -> ResourceLocation.fromNamespaceAndPath("railways", "narrow_default");
                default -> CreateTrainUtils.getStandardBogeyStyleId();
            };                
            return styleId;
        }

        ResourceLocation fallback = CreateTrainUtils.getStandardBogeyStyleId();            
        return fallback;
    }

    private static void resetToDefaults() {
        CreateTrackUtil.setActiveTrackMaterial(CreateTrackUtil.getDefaultAndesiteMaterial());
        CreateTrackUtil.setActiveTrackBlock(null);
    }

    private static final String RAILWAY_MOD_ID = "railways";

    private static boolean isRailwayModPresent() {
        return ModList.get().isLoaded(RAILWAY_MOD_ID);
    }

    @Nullable
    private static ResolvedMaterial resolveMaterial(String material, String gauge) {
        Class<?> trackMaterialClass = CreateTrackUtil.getTrackMaterialClass();
        if (trackMaterialClass == null) {
            LOGGER.warn("[RAILWAY-COMPAT] TrackMaterial class not available - cannot resolve '{}'", material);
            return null;
        }

        ResourceLocation materialId = buildMaterialId(material, gauge);

        try {
            Map<?, ?> allMats = getMaterialMap(trackMaterialClass);
            if (allMats == null) {
                LOGGER.warn("[RAILWAY-COMPAT] Could not access TrackMaterial.ALL map");
                return null;
            }

            Object resolved = allMats.get(materialId);
            if (resolved == null) {
                return null;
            }

            Object trackBlock = getBlockFromMaterial(resolved);
            if (trackBlock == null) {
                LOGGER.warn("[RAILWAY-COMPAT] Failed to get block from material '{}'", materialId);
                return null;
            }

            return new ResolvedMaterial(resolved, trackBlock);

        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /**
     * Builds the ResourceLocation for the material+gauge combination.
     *
     * - andesite/standard -> create:andesite
     * - oak/standard -> railways:oak
     * - andesite/wide -> railways:create_andesite_wide
     * - oak/wide -> railways:oak_wide
     * - oak/narrow -> railways:oak_narrow
     */
    static ResourceLocation buildMaterialId(String material, String gauge) {
        boolean isDefault = "andesite".equals(material);
        boolean isStandard = "standard".equals(gauge);

        if (isDefault && isStandard) {
            return ResourceLocation.fromNamespaceAndPath("create", "andesite");
        }

        if (isDefault && !isStandard) {
            return ResourceLocation.fromNamespaceAndPath("railways", "create_andesite_" + gauge);
        }

        if (isStandard) {
            return ResourceLocation.fromNamespaceAndPath("railways", material);
        }

        return ResourceLocation.fromNamespaceAndPath("railways", material + "_" + gauge);
    }

    private static Map<?, ?> getMaterialMap(Class<?> trackMaterialClass) throws ReflectiveOperationException {
        Object allField = trackMaterialClass.getField(CreateReflectionFields.TRACK_MATERIAL_ALL).get(null);
        if (allField instanceof Map<?, ?> map) {
            return map;
        }
        return null;
    }

    private static Object getBlockFromMaterial(Object material) throws ReflectiveOperationException {
        Method getBlockMethod = material.getClass().getMethod(CreateReflectionFields.GET_BLOCK);
        return getBlockMethod.invoke(material);
    }
}
