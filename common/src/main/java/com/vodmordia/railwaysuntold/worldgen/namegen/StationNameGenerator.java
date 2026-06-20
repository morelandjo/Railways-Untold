package com.vodmordia.railwaysuntold.worldgen.namegen;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Set;

/**
 * Generates unique station names in the format: "Adjective BiomeWord Station"
 * Tracks used names to avoid duplicates, appending Roman numerals when needed.
 */
public class StationNameGenerator extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DATA_NAME = "railwaysuntold_station_names";


    private static final String[] ADJECTIVES = {
        // Pleasant/Positive
        "Sunny", "Bright", "Golden", "Silver", "Pleasant", "Peaceful", "Serene", "Tranquil",
        "Merry", "Jolly", "Happy", "Lucky", "Blessed", "Grand", "Noble", "Royal",

        // Nature-themed
        "Mossy", "Leafy", "Shady", "Misty", "Dewy", "Breezy", "Windy", "Cloudy",
        "Starry", "Moonlit", "Sunset", "Dawn", "Twilight", "Morning", "Evening",

        // Directional/Positional
        "Northern", "Southern", "Eastern", "Western", "Upper", "Lower", "Central", "Outer",
        "Inner", "Far", "Near", "High", "Low", "Deep",

        // Size/Scale
        "Little", "Great", "Tall", "Wide", "Broad", "Vast", "Tiny", "Grand",
        "Mighty", "Humble", "Modest", "Sprawling",

        // Age/Time
        "Old", "New", "Ancient", "Young", "Timeless", "Eternal", "Ageless", "Historic",

        // Color-themed
        "Green", "Blue", "Red", "White", "Black", "Grey", "Amber", "Russet",
        "Copper", "Bronze", "Ivory", "Jade", "Coral", "Scarlet", "Azure",

        // Texture/Quality
        "Smooth", "Rugged", "Gentle", "Rough", "Soft", "Quiet", "Still", "Wild",
        "Tame", "Rustic", "Quaint", "Cozy",

        // Weather/Season
        "Rainy", "Snowy", "Frosty", "Warm", "Cool", "Crisp", "Fresh", "Balmy",
        "Spring", "Summer", "Autumn", "Winter",

        // Character/Feeling
        "Hidden", "Secret", "Lost", "Forgotten", "Lonely", "Quiet", "Silent", "Sleepy",
        "Busy", "Bustling", "Lively", "Lazy", "Drowsy",

        // Material/Substance
        "Stone", "Iron", "Copper", "Wooden", "Marble", "Crystal", "Glass", "Steel",

        // Whimsical/Fantasy
        "Enchanted", "Magical", "Fairy", "Elfin", "Mystic", "Wondrous", "Charmed", "Dreamy"
    };

    private final Set<String> usedNames = new HashSet<>();

    public StationNameGenerator() {
    }

    /**
     * Generates a unique station name based on the biome at the given position.
     *
     * @param level The server level
     * @param pos   The position to check biome at
     * @param rand  Random source for selection
     * @return A unique station name
     */
    public synchronized String generateName(ServerLevel level, BlockPos pos, RandomSource rand) {
        Holder<Biome> biomeHolder = level.getBiome(pos);
        String biomeName = biomeHolder.unwrapKey()
            .map(key -> key.location().getPath())
            .orElse("plains");

        String[] biomeWords = BiomeWordPools.getWordsForBiome(biomeName);
        String adjective = ADJECTIVES[rand.nextInt(ADJECTIVES.length)];
        String biomeWord = biomeWords[rand.nextInt(biomeWords.length)];
        String baseName = adjective + " " + biomeWord + " Station";

        String finalName = resolveDuplicate(baseName);
        usedNames.add(finalName);
        setDirty();

        return finalName;
    }

    private String resolveDuplicate(String name) {
        if (!usedNames.contains(name)) {
            return name;
        }

        int i = 2;
        String tryName;
        do {
            tryName = name + " " + toRoman(i);
            i++;
        } while (usedNames.contains(tryName));

        return tryName;
    }

    private static String toRoman(int number) {
        if (number < 1 || number > 3999) {
            return String.valueOf(number);
        }

        StringBuilder result = new StringBuilder();
        int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] numerals = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};

        for (int i = 0; i < values.length; i++) {
            while (number >= values[i]) {
                result.append(numerals[i]);
                number -= values[i];
            }
        }
        return result.toString();
    }

    public static StationNameGenerator load(CompoundTag tag) {
        StationNameGenerator generator = new StationNameGenerator();
        ListTag nameList = tag.getList(NameGenNbtKeys.USED_NAMES, Tag.TAG_STRING);
        for (Tag nameTag : nameList) {
            generator.usedNames.add(nameTag.getAsString());
        }
        return generator;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag nameList = new ListTag();
        for (String name : usedNames) {
            nameList.add(StringTag.valueOf(name));
        }
        tag.put(NameGenNbtKeys.USED_NAMES, nameList);
        return tag;
    }

    public static StationNameGenerator get(ServerLevel level) {
        ServerLevel overworld = level.getServer().getLevel(Level.OVERWORLD);
        if (overworld == null) {
            LOGGER.error("[STATION-NAME] Could not get overworld level!");
            return new StationNameGenerator();
        }
        return overworld.getDataStorage().computeIfAbsent(
            StationNameGenerator::load,
            StationNameGenerator::new,
            DATA_NAME
        );
    }
}
