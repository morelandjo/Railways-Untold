package com.vodmordia.railwaysuntold.worldgen.namegen;

import java.util.HashMap;
import java.util.Map;

/**
 * Contains biome-specific word pools for station name generation.
 */
public final class BiomeWordPools {

    private static final Map<String, String[]> BIOME_WORDS = new HashMap<>();
    private static final String[] DEFAULT_WORDS = {
        "Crossing", "Junction", "Halt", "Stop", "Way", "Pass", "Gate", "Point", "End", "View"
    };

    static {
        String[] oceanWords = {
            "Harbor", "Pier", "Dock", "Wharf", "Cove", "Bay", "Shore", "Tide", "Wave", "Reef",
            "Anchor", "Coral", "Depths", "Current", "Shoal", "Marina", "Jetty", "Seascape",
            "Kelp", "Brine", "Surge", "Swell", "Breakwater", "Lighthouse", "Beacon"
        };
        BIOME_WORDS.put("ocean", oceanWords);
        BIOME_WORDS.put("deep_ocean", oceanWords);
        BIOME_WORDS.put("warm_ocean", oceanWords);
        BIOME_WORDS.put("lukewarm_ocean", oceanWords);
        BIOME_WORDS.put("deep_lukewarm_ocean", oceanWords);
        BIOME_WORDS.put("cold_ocean", oceanWords);
        BIOME_WORDS.put("deep_cold_ocean", oceanWords);
        BIOME_WORDS.put("frozen_ocean", new String[]{
            "Iceberg", "Floe", "Glacier", "Frost", "Frozen", "Arctic", "Polar", "Chill",
            "Rime", "Sleet", "Permafrost", "Tundra", "Frigid", "Boreal", "Frostbite"
        });
        BIOME_WORDS.put("deep_frozen_ocean", BIOME_WORDS.get("frozen_ocean"));

        BIOME_WORDS.put("mushroom_fields", new String[]{
            "Spore", "Cap", "Mycelium", "Fungal", "Morel", "Truffle", "Shroom", "Toadstool",
            "Puffball", "Chanterelle", "Shiitake", "Portobello", "Fairy", "Ring", "Gill"
        });

        String[] peakWords = {
            "Summit", "Peak", "Ridge", "Crest", "Pinnacle", "Apex", "Crown", "Heights",
            "Spire", "Cliff", "Bluff", "Precipice", "Overlook", "Vista", "Skyline",
            "Alpine", "Highlands", "Crag", "Escarpment", "Promontory"
        };
        BIOME_WORDS.put("jagged_peaks", peakWords);
        BIOME_WORDS.put("frozen_peaks", new String[]{
            "Glacier", "Frost", "Ice", "Snow", "Frozen", "Chill", "Arctic", "Polar",
            "Summit", "Peak", "Ridge", "Alpine", "Tundra", "Permafrost", "Rime",
            "Avalanche", "Snowdrift", "Icecap", "Frostpeak", "Winterhorn"
        });
        BIOME_WORDS.put("stony_peaks", new String[]{
            "Stone", "Rock", "Boulder", "Granite", "Slate", "Quarry", "Cliff", "Crag",
            "Summit", "Peak", "Ridge", "Outcrop", "Ledge", "Basalt", "Monolith"
        });
        BIOME_WORDS.put("meadow", new String[]{
            "Meadow", "Field", "Pasture", "Glade", "Dell", "Lea", "Green", "Vale",
            "Bloom", "Blossom", "Petal", "Daisy", "Clover", "Buttercup", "Wildflower",
            "Grassland", "Prairie", "Clearing", "Glen", "Hollow"
        });
        BIOME_WORDS.put("cherry_grove", new String[]{
            "Blossom", "Petal", "Cherry", "Sakura", "Bloom", "Pink", "Spring", "Garden",
            "Grove", "Orchard", "Arbor", "Bower", "Canopy", "Shade", "Drift"
        });
        BIOME_WORDS.put("grove", new String[]{
            "Grove", "Copse", "Thicket", "Stand", "Arbor", "Bower", "Spinney", "Coppice",
            "Woodland", "Timber", "Shade", "Canopy", "Glade", "Dell", "Hollow"
        });
        BIOME_WORDS.put("snowy_slopes", new String[]{
            "Snow", "Frost", "Powder", "Drift", "Slope", "Slide", "Run", "Chute",
            "Flurry", "Blizzard", "Glacier", "Alpine", "Snowfield", "Cornice", "Mogul"
        });
        String[] windsweptWords = {
            "Wind", "Gust", "Gale", "Breeze", "Zephyr", "Squall", "Draft", "Tempest",
            "Bluster", "Howl", "Swept", "Bare", "Exposed", "Wild", "Rugged"
        };
        BIOME_WORDS.put("windswept_hills", windsweptWords);
        BIOME_WORDS.put("windswept_gravelly_hills", windsweptWords);
        BIOME_WORDS.put("windswept_forest", windsweptWords);

        String[] forestWords = {
            "Forest", "Wood", "Timber", "Grove", "Copse", "Thicket", "Brake", "Bower",
            "Canopy", "Shade", "Glade", "Dell", "Glen", "Hollow", "Clearing",
            "Oak", "Elm", "Maple", "Ash", "Beech", "Trunk", "Bough", "Branch"
        };
        BIOME_WORDS.put("forest", forestWords);
        BIOME_WORDS.put("flower_forest", new String[]{
            "Bloom", "Blossom", "Petal", "Garden", "Meadow", "Flower", "Rose", "Lily",
            "Tulip", "Daisy", "Orchid", "Violet", "Poppy", "Sunflower", "Lavender",
            "Grove", "Glade", "Dell", "Bower", "Arbor"
        });
        String[] taigaWords = {
            "Pine", "Spruce", "Fir", "Evergreen", "Conifer", "Needle", "Taiga", "Boreal",
            "Forest", "Wood", "Timber", "Grove", "Frost", "Snow", "Cold",
            "Wolf", "Bear", "Fox", "Elk", "Moose"
        };
        BIOME_WORDS.put("taiga", taigaWords);
        BIOME_WORDS.put("old_growth_pine_taiga", new String[]{
            "Ancient", "Elder", "Giant", "Towering", "Primeval", "Old", "Venerable",
            "Pine", "Spruce", "Fir", "Evergreen", "Conifer", "Needle", "Taiga", "Boreal"
        });
        BIOME_WORDS.put("old_growth_spruce_taiga", BIOME_WORDS.get("old_growth_pine_taiga"));
        BIOME_WORDS.put("snowy_taiga", new String[]{
            "Frost", "Snow", "Ice", "Frozen", "Winter", "Cold", "Chill", "Powder",
            "Pine", "Spruce", "Fir", "Evergreen", "Taiga", "Boreal", "Tundra"
        });
        String[] birchWords = {
            "Birch", "White", "Silver", "Pale", "Bark", "Paper", "Aspen", "Poplar",
            "Grove", "Stand", "Copse", "Wood", "Forest", "Glade", "Dell"
        };
        BIOME_WORDS.put("birch_forest", birchWords);
        BIOME_WORDS.put("old_growth_birch_forest", new String[]{
            "Ancient", "Elder", "Tall", "Towering", "Grand", "Majestic", "Venerable",
            "Birch", "White", "Silver", "Pale", "Grove", "Stand", "Cathedral"
        });
        BIOME_WORDS.put("dark_forest", new String[]{
            "Shadow", "Dark", "Gloom", "Dusk", "Twilight", "Murk", "Shade", "Night",
            "Thicket", "Tangle", "Bramble", "Briar", "Undergrowth", "Canopy", "Dense",
            "Raven", "Crow", "Owl", "Bat", "Witch", "Haunted", "Eerie"
        });
        BIOME_WORDS.put("pale_garden", new String[]{
            "Pale", "White", "Ghost", "Mist", "Fog", "Spectral", "Ethereal", "Faded",
            "Silent", "Hush", "Whisper", "Garden", "Grove", "Bower", "Glade"
        });
        String[] jungleWords = {
            "Jungle", "Rainforest", "Canopy", "Vine", "Fern", "Palm", "Tropical", "Lush",
            "Dense", "Verdant", "Emerald", "Green", "Humid", "Steamy", "Wild",
            "Parrot", "Monkey", "Jaguar", "Ocelot", "Toucan", "Macaw"
        };
        BIOME_WORDS.put("jungle", jungleWords);
        BIOME_WORDS.put("sparse_jungle", new String[]{
            "Sparse", "Open", "Clearing", "Edge", "Fringe", "Border", "Margin",
            "Jungle", "Tropical", "Palm", "Fern", "Vine", "Green", "Verdant"
        });
        BIOME_WORDS.put("bamboo_jungle", new String[]{
            "Bamboo", "Reed", "Cane", "Stalk", "Grove", "Thicket", "Green", "Emerald",
            "Panda", "Jungle", "Tropical", "Dense", "Lush", "Verdant", "Oriental"
        });

        String[] riverWords = {
            "River", "Stream", "Creek", "Brook", "Run", "Ford", "Crossing", "Bridge",
            "Bank", "Bend", "Eddy", "Rapids", "Falls", "Current", "Flow",
            "Otter", "Salmon", "Trout", "Heron", "Kingfisher"
        };
        BIOME_WORDS.put("river", riverWords);
        BIOME_WORDS.put("frozen_river", new String[]{
            "Frozen", "Ice", "Frost", "Cold", "Chill", "Winter", "Crystal", "Glaze",
            "River", "Stream", "Creek", "Brook", "Ford", "Crossing", "Bridge"
        });
        String[] swampWords = {
            "Swamp", "Marsh", "Bog", "Fen", "Mire", "Quagmire", "Wetland", "Bayou",
            "Murk", "Moss", "Reed", "Cattail", "Lily", "Pad", "Cypress",
            "Frog", "Toad", "Heron", "Gator", "Crane", "Egret"
        };
        BIOME_WORDS.put("swamp", swampWords);
        BIOME_WORDS.put("mangrove_swamp", new String[]{
            "Mangrove", "Root", "Tangle", "Prop", "Stilt", "Coastal", "Tidal", "Brackish",
            "Swamp", "Marsh", "Wetland", "Bayou", "Estuary", "Delta", "Lagoon"
        });
        BIOME_WORDS.put("beach", new String[]{
            "Beach", "Shore", "Sand", "Dune", "Coast", "Strand", "Surf", "Tide",
            "Shell", "Seagull", "Pelican", "Crab", "Palm", "Tropical", "Sunny"
        });
        BIOME_WORDS.put("snowy_beach", new String[]{
            "Frost", "Ice", "Snow", "Cold", "Frozen", "Arctic", "Polar", "Winter",
            "Beach", "Shore", "Coast", "Strand", "Dune", "Sand", "Frigid"
        });
        BIOME_WORDS.put("stony_shore", new String[]{
            "Stone", "Rock", "Boulder", "Pebble", "Cobble", "Cliff", "Crag", "Bluff",
            "Shore", "Coast", "Tide", "Wave", "Surf", "Spray", "Jagged"
        });

        String[] plainsWords = {
            "Plains", "Prairie", "Meadow", "Field", "Grassland", "Pasture", "Range", "Steppe",
            "Flat", "Open", "Wide", "Vast", "Endless", "Horizon", "Sky",
            "Wheat", "Hay", "Grain", "Cattle", "Horse", "Bison"
        };
        BIOME_WORDS.put("plains", plainsWords);
        BIOME_WORDS.put("sunflower_plains", new String[]{
            "Sunflower", "Golden", "Yellow", "Sunny", "Bright", "Radiant", "Bloom",
            "Plains", "Prairie", "Meadow", "Field", "Pasture", "Garden", "Petal"
        });
        BIOME_WORDS.put("snowy_plains", new String[]{
            "Snow", "Frost", "Ice", "Frozen", "Winter", "Cold", "White", "Powder",
            "Plains", "Prairie", "Tundra", "Steppe", "Flat", "Open", "Vast"
        });
        BIOME_WORDS.put("ice_spikes", new String[]{
            "Ice", "Spike", "Crystal", "Frost", "Frozen", "Glacier", "Shard", "Pillar",
            "Arctic", "Polar", "Permafrost", "Frigid", "Chill", "Winter", "Blue"
        });

        String[] desertWords = {
            "Desert", "Dune", "Sand", "Oasis", "Mirage", "Arid", "Dry", "Barren",
            "Cactus", "Palm", "Scorpion", "Lizard", "Camel", "Nomad", "Caravan",
            "Sun", "Heat", "Dust", "Wind", "Storm", "Sahara", "Mesa"
        };
        BIOME_WORDS.put("desert", desertWords);
        String[] savannaWords = {
            "Savanna", "Veldt", "Safari", "Grassland", "Plain", "Acacia", "Baobab", "Thorn",
            "Lion", "Elephant", "Giraffe", "Zebra", "Rhino", "Gazelle", "Cheetah",
            "Golden", "Amber", "Dry", "Warm", "Sunset", "Horizon"
        };
        BIOME_WORDS.put("savanna", savannaWords);
        BIOME_WORDS.put("savanna_plateau", savannaWords);
        BIOME_WORDS.put("windswept_savanna", new String[]{
            "Wind", "Gust", "Gale", "Swept", "Wild", "Rugged", "Exposed", "Bare",
            "Savanna", "Veldt", "Grassland", "Acacia", "Thorn", "Dry", "Amber"
        });
        String[] badlandsWords = {
            "Badlands", "Mesa", "Canyon", "Gulch", "Ravine", "Gorge", "Butte", "Bluff",
            "Red", "Rust", "Terracotta", "Clay", "Ochre", "Crimson", "Scarlet",
            "Dust", "Dry", "Barren", "Desolate", "Wild", "Frontier", "Outlaw"
        };
        BIOME_WORDS.put("badlands", badlandsWords);
        BIOME_WORDS.put("wooded_badlands", new String[]{
            "Wooded", "Oak", "Forest", "Grove", "Thicket", "Scrub", "Brush",
            "Badlands", "Mesa", "Canyon", "Gulch", "Red", "Terracotta", "Clay"
        });
        BIOME_WORDS.put("eroded_badlands", new String[]{
            "Eroded", "Carved", "Sculpted", "Weathered", "Ancient", "Worn", "Rugged",
            "Badlands", "Mesa", "Canyon", "Spire", "Pillar", "Hoodoo", "Red"
        });

        BIOME_WORDS.put("deep_dark", new String[]{
            "Deep", "Dark", "Abyss", "Void", "Shadow", "Gloom", "Murk", "Pitch",
            "Sculk", "Echo", "Silence", "Warden", "Ancient", "Forgotten", "Lost",
            "Cavern", "Chasm", "Depths", "Underground", "Subterranean"
        });
        BIOME_WORDS.put("dripstone_caves", new String[]{
            "Drip", "Stone", "Stalactite", "Stalagmite", "Spire", "Point", "Spike", "Column",
            "Cavern", "Grotto", "Cave", "Hollow", "Chamber", "Echo", "Dripping"
        });
        BIOME_WORDS.put("lush_caves", new String[]{
            "Lush", "Green", "Verdant", "Moss", "Fern", "Vine", "Glow", "Bloom",
            "Cavern", "Grotto", "Cave", "Hollow", "Underground", "Hidden", "Secret",
            "Axolotl", "Pool", "Spring", "Drip", "Leaf"
        });

        BIOME_WORDS.put("nether_wastes", new String[]{
            "Nether", "Inferno", "Hell", "Flame", "Fire", "Ember", "Ash", "Cinder",
            "Waste", "Desolate", "Barren", "Bleak", "Forsaken", "Damned", "Burning"
        });
        BIOME_WORDS.put("soul_sand_valley", new String[]{
            "Soul", "Spirit", "Ghost", "Specter", "Phantom", "Wraith", "Shade", "Lost",
            "Sand", "Valley", "Hollow", "Vale", "Basin", "Fog", "Mist", "Blue"
        });
        BIOME_WORDS.put("crimson_forest", new String[]{
            "Crimson", "Red", "Scarlet", "Blood", "Ruby", "Vermilion", "Carmine", "Rust",
            "Forest", "Fungus", "Spore", "Shroomlight", "Nether", "Twisted", "Warped"
        });
        BIOME_WORDS.put("warped_forest", new String[]{
            "Warped", "Twisted", "Bent", "Strange", "Alien", "Bizarre", "Surreal", "Cyan",
            "Forest", "Fungus", "Spore", "Enderman", "Nether", "Teal", "Blue"
        });
        BIOME_WORDS.put("basalt_deltas", new String[]{
            "Basalt", "Lava", "Magma", "Volcanic", "Molten", "Obsidian", "Black", "Dark",
            "Delta", "Flow", "River", "Stream", "Cascade", "Eruption", "Ash"
        });

        String[] endWords = {
            "End", "Void", "Abyss", "Null", "Ender", "Dragon", "Obsidian", "Purple",
            "Star", "Cosmos", "Eternal", "Infinite", "Beyond", "Final", "Last",
            "Chorus", "Shulker", "Crystal", "Gateway", "Portal"
        };
        BIOME_WORDS.put("the_end", endWords);
        BIOME_WORDS.put("small_end_islands", new String[]{
            "Isle", "Island", "Float", "Drift", "Fragment", "Shard", "Remnant", "Piece",
            "End", "Void", "Ender", "Purple", "Chorus", "Small", "Tiny"
        });
        BIOME_WORDS.put("end_midlands", new String[]{
            "Midland", "Central", "Middle", "Heart", "Core", "Interior", "Inner",
            "End", "Void", "Ender", "Purple", "Chorus", "Shulker", "Gateway"
        });
        BIOME_WORDS.put("end_highlands", new String[]{
            "Highland", "Heights", "Plateau", "Elevated", "Upper", "High", "Apex",
            "End", "Void", "Ender", "Purple", "Chorus", "City", "Gateway"
        });
        BIOME_WORDS.put("end_barrens", new String[]{
            "Barren", "Empty", "Desolate", "Bleak", "Waste", "Forsaken", "Abandoned",
            "End", "Void", "Ender", "Purple", "Edge", "Rim", "Outer"
        });
    }

    private BiomeWordPools() {
    }

    /**
     * Gets the word pool for a specific biome.
     *
     * @param biomeName The biome resource location path (e.g., "plains", "dark_forest")
     * @return Array of thematic words for the biome, or default words if biome not found
     */
    public static String[] getWordsForBiome(String biomeName) {
        // Strip namespace if present (e.g., "minecraft:plains" -> "plains")
        if (biomeName.contains(":")) {
            biomeName = biomeName.substring(biomeName.indexOf(':') + 1);
        }
        return BIOME_WORDS.getOrDefault(biomeName, DEFAULT_WORDS);
    }
}
