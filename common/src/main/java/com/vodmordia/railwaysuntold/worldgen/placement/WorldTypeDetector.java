package com.vodmordia.railwaysuntold.worldgen.placement;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * Detects the world type from the chunk generator's registry name and checks it
 * against the configured allowed list.
 */
public class WorldTypeDetector {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<String, String> ALIASES = Map.of(
            "default", "noise",
            "noise", "default"
    );

    private static final Method CODEC_METHOD;

    static {
        Method found = null;
        for (String name : new String[]{"codec", "m_6909_", "method_28506"}) {
            try {
                found = ChunkGenerator.class.getDeclaredMethod(name);
                found.setAccessible(true);
                break;
            } catch (NoSuchMethodException ignored) {
            }
        }
        if (found == null) {
            throw new RuntimeException("Failed to find ChunkGenerator.codec() method");
        }
        CODEC_METHOD = found;
    }

    private WorldTypeDetector() {}

    /**
     * Resolves the chunk generator type identifier from the registry.
     *
     * @return the generator's registry name (e.g. "minecraft:noise", "bigglobe:scripted"),
     *         or null if the generator codec is not registered
     */
    @SuppressWarnings("unchecked")
    @Nullable
    public static String detectWorldType(ServerLevel level) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        try {
            Codec<? extends ChunkGenerator> codec =
                    (Codec<? extends ChunkGenerator>) CODEC_METHOD.invoke(generator);
            ResourceLocation key = BuiltInRegistries.CHUNK_GENERATOR.getKey(codec);
            return key != null ? key.toString() : null;
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[WORLD-TYPE] Failed to resolve chunk generator codec: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Checks whether the world type of the given level is in the allowed list.
     * Matching is case-insensitive.
     */
    public static boolean isWorldTypeAllowed(ServerLevel level, List<? extends String> allowedTypes) {
        String worldType = detectWorldType(level);
        if (worldType == null) {
            return false;
        }

        String worldTypeLower = worldType.toLowerCase();
        for (String allowed : allowedTypes) {
            String normalized = allowed.trim().toLowerCase();
            if (matchesWorldType(normalized, worldTypeLower)) {
                return true;
            }
            String alias = ALIASES.get(normalized);
            if (alias != null && matchesWorldType(alias, worldTypeLower)) {
                return true;
            }
        }

        return false;
    }

    private static boolean matchesWorldType(String allowed, String worldType) {
        if (allowed.equals(worldType)) {
            return true;
        }
        return !allowed.contains(":") && worldType.endsWith(":" + allowed);
    }
}
