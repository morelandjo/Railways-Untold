package com.vodmordia.railwaysuntold;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Railways Untold configuration using TOML
 */
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // Track Clearing Settings
    public static final ModConfigSpec.IntValue HORIZONTAL_EXPANSION;
    public static final ModConfigSpec.IntValue VERTICAL_EXPANSION;

    // World Generation Settings
    public static final ModConfigSpec.ConfigValue<String> GENERATE_IN_WORLD_TYPES;

    // Feature Toggles
    public static final ModConfigSpec.BooleanValue STARTING_TRAIN;
    public static final ModConfigSpec.BooleanValue BRANCHES_ENABLED;
    public static final ModConfigSpec.BooleanValue RECURSIVE_BRANCHING_ENABLED;
    public static final ModConfigSpec.IntValue MAX_BRANCH_DEPTH;
    public static final ModConfigSpec.IntValue MAX_ACTIVE_HEADS;
    public static final ModConfigSpec.IntValue MIN_BRANCH_SPACING;
    public static final ModConfigSpec.BooleanValue STRUCTURE_TARGETING;
    public static final ModConfigSpec.BooleanValue ALL_AVOIDANCE;
    public static final ModConfigSpec.BooleanValue SIDE_BY_SIDE_TRACKS;
    public static final ModConfigSpec.BooleanValue PARALLEL_MERGE_ENABLED;
    public static final ModConfigSpec.BooleanValue SIDINGS_ENABLED;
    public static final ModConfigSpec.IntValue SIDING_MIN_STRAIGHT_SEGMENTS;
    public static final ModConfigSpec.ConfigValue<String> TRACK_GAUGE;
    // Block IDs

    // Bridge decking
    public static final ModConfigSpec.IntValue BRIDGE_ELEVATION_THRESHOLD;

    // Terrain Detection
    public static final ModConfigSpec.BooleanValue BANKING_CURVES;
    public static final ModConfigSpec.BooleanValue LATERALS;
    public static final ModConfigSpec.IntValue LATERAL_OFFSET;
    public static final ModConfigSpec.IntValue SLOPE_RISE;
    public static final ModConfigSpec.IntValue SLOPE_RUN;
    public static final ModConfigSpec.IntValue MIN_CURVE_RADIUS;
    public static final ModConfigSpec.IntValue MAX_CURVE_RADIUS;
    public static final ModConfigSpec.IntValue BRIDGE_WATER_DISTANCE;

    // Tunnel Settings
    public static final ModConfigSpec.IntValue TUNNEL_LIGHT_SPACING;

    // Station Settings
    public static final ModConfigSpec.BooleanValue STARTING_STATION;

    // Debug Settings
    public static final ModConfigSpec.BooleanValue VERBOSE_LOGGING;

    // Event Settings
    public static final ModConfigSpec.BooleanValue CUSTOM_EVENTS;
    public static final ModConfigSpec.IntValue EVENT_SEPARATION_MIN_DISTANCE;
    public static final ModConfigSpec.IntValue EVENT_CHANCE;

    static {
        BUILDER.comment("Railways Untold Configuration")
                .push("track_clearing");

        HORIZONTAL_EXPANSION = BUILDER
                .comment("Number of blocks to clear horizontally on each side of the track.")
                .defineInRange("horizontalExpansion", 3, 1, 16);

        VERTICAL_EXPANSION = BUILDER
                .comment("", "Number of blocks to clear vertically above the track.")
                .defineInRange("verticalExpansion", 6, 2, 32);

        BUILDER.pop();

        BUILDER.comment("World Generation Settings",
                        "Controls which world types allow track generation")
                .push("world_generation");

        GENERATE_IN_WORLD_TYPES = BUILDER
                .comment("Comma-separated list of chunk generator types where track generation is allowed.",
                        "Uses the registry name of the chunk generator (e.g. 'minecraft:noise', 'minecraft:flat').",
                        "Modded generators can be added by their full ID (e.g. 'bigglobe:scripted').",
                        "Set to an empty string to disable generation in all world types.")
                .define("generateInWorldTypes", "minecraft:noise,minecraft:flat");

        BUILDER.pop();

        BUILDER.comment("Feature Toggles")
                .push("features");

        STARTING_TRAIN = BUILDER
                .comment("Whether to place a starting train at world generation.",
                        "If false, only the track network will be generated.")
                .define("startingTrain", true);

        BRANCHES_ENABLED = BUILDER
                .comment("", "Whether track branches (junctions) are enabled.",
                        "If false, tracks will only generate as a single continuous line.")
                .define("branchesEnabled", true);

        RECURSIVE_BRANCHING_ENABLED = BUILDER
                .comment("", "Whether branches can create additional branches (recursive branching).",
                        "If false, only the main track lines can create branches.",
                        "Has no effect if branchesEnabled is false.")
                .define("recursiveBranchingEnabled", true);

        MAX_BRANCH_DEPTH = BUILDER
                .comment("", "Maximum depth for recursive branching.",
                        "1 = one level of branches, 2 = branches can branch once, 3 = two levels of recursive branches, etc.",
                        "Setting to 0 means unlimited branch depth which could affect performance.",
                        "Only applies when recursiveBranchingEnabled is true.")
                .defineInRange("maxBranchDepth", 3, 0, 10);

        MAX_ACTIVE_HEADS = BUILDER
                .comment("", "Maximum number of simultaneously active track heads (including main tracks and all branches).",
                        "Limits how many track lines can be generating at once.",
                        "Setting to 0 means unlimited heads which could affect performance.")
                .defineInRange("maxActiveHeads", 15, 0, 100);

        MIN_BRANCH_SPACING = BUILDER
                .comment("", "Minimum distance in blocks between consecutive branches from the same track head.",
                        "After spawning a branch, the parent head must travel this distance before branching again.")
                .defineInRange("minBranchSpacing", 150, 50, 1000);

        STRUCTURE_TARGETING = BUILDER
                .comment("", "Whether tracks should seek out and path toward structures for station placement.",
                        "Which structures are targeted is configured via biome settings data packs (structure_target_tags).",
                        "When disabled, tracks will not target any structures regardless of data pack settings.")
                .define("structureTargeting", true);

        ALL_AVOIDANCE = BUILDER
                .comment("", "Whether tracks should avoid obstacles by turning.",
                        "When enabled, tracks will curve around structures, terrain obstacles, and other hazards.",
                        "When disabled, tracks will go straight through all terrain (tunneling through mountains,",
                        "descending into ravines) without ever turning.")
                .define("allAvoidance", true);

        SIDE_BY_SIDE_TRACKS = BUILDER
                .comment("", "Enable side-by-side dual track mode.",
                        "Two parallel tracks with opposite travel directions.",
                        "Separation = horizontalExpansion * 2 (minimum 5 blocks).")
                .define("sideBySideTracks", false);

        PARALLEL_MERGE_ENABLED = BUILDER
                .comment("", "Whether a head running parallel and adjacent to another line should merge into it.",
                        "When enabled, a head that meets another track running its way merges in (curving across",
                        "when touching, or steering over from up to ~70 blocks away) instead of running side by side.",
                        "When disabled, parallel lines are left as separate side-by-side tracks.")
                .define("parallelMergeEnabled", true);

        SIDINGS_ENABLED = BUILDER
                .comment("", "Enable rail sidings (passing loops).",
                        "When enabled, long straight sections of track will occasionally",
                        "generate a parallel siding that diverges and reconnects.")
                .define("sidingsEnabled", true);

        SIDING_MIN_STRAIGHT_SEGMENTS = BUILDER
                .comment("", "Minimum number of consecutive straight segments before a siding can be placed.",
                        "Each segment is approximately 16 blocks long.")
                .defineInRange("sidingMinStraightSegments", 6, 3, 100);

        TRACK_GAUGE = BUILDER
                .comment("", "Track width/gauge.",
                        "Values: standard, wide, narrow.",
                        "Non-standard values require Steam 'n' Rails.",
                        "Note: phantom tracks only support standard gauge.")
                .define("gauge", "standard");

        BUILDER.pop();

        BUILDER.comment("Bridge Settings",
                        "Controls bridge decking placement (pier spacing comes from the railing pattern NBT)")
                .push("pillars");

        BRIDGE_ELEVATION_THRESHOLD = BUILDER
                .comment("", "Minimum height above ground to use bridge decking.",
                        "When the track is this many blocks above ground, bridge decking and",
                        "pillar schematics are placed instead of terrain fill.",
                        "Set to 0 to only use bridge decking over water.")
                .defineInRange("bridgeElevationThreshold", 4, 0, 32);

        BUILDER.pop();

        BUILDER.comment("Terrain Detection",
                        "Controls how the track generator detects and responds to terrain features")
                .push("terrain");

        BANKING_CURVES = BUILDER
                .comment("", "Allow curves to change elevation while turning (banking curves).",
                        "When false, curves are always flat and elevation changes happen on straight segments.",
                        "When true, curves can slope up/down while turning, creating a roller coaster effect.")
                .define("bankingCurves", false);

        LATERALS = BUILDER
                .comment("", "Whether tracks can make gentle lateral shifts (slight sideways beziers).",
                        "When disabled, tracks stay on the cardinal axis and use SCurve45 diagonals",
                        "or L-shaped curves to correct accumulated drift.")
                .define("laterals", false);

        LATERAL_OFFSET = BUILDER
                .comment("", "Lateral drift correction threshold (in blocks).",
                        "When the track drifts this far off the cardinal axis, an SCurve45 diagonal",
                        "or curve correction is inserted to realign with the planned route.")
                .defineInRange("lateralOffset", 5, 2, 20);

        SLOPE_RISE = BUILDER
                .comment("", "Rise component of the maximum slope ratio (rise:run).",
                        "Combined with slopeRun to determine max track steepness.",
                        "Example: rise=1, run=2 means max slope of 1/2 (0.5).")
                .defineInRange("slopeRise", 1, 1, 5);

        SLOPE_RUN = BUILDER
                .comment("", "Run component of the maximum slope ratio (rise:run).",
                        "Combined with slopeRise to determine max track steepness.",
                        "Example: rise=1, run=2 means max slope of 1/2 (0.5).")
                .defineInRange("slopeRun", 4, 1, 20);

        MIN_CURVE_RADIUS = BUILDER
                .comment("", "Minimum curve radius in blocks.",
                        "Smaller values allow tighter turns but may cause train navigation issues.")
                .defineInRange("minCurveRadius", 15, 10, 50);

        MAX_CURVE_RADIUS = BUILDER
                .comment("", "Maximum curve radius in blocks.",
                        "Larger values create wider, smoother turns.")
                .defineInRange("maxCurveRadius", 25, 10, 100);

        BRIDGE_WATER_DISTANCE = BUILDER
                .comment("", "Minimum height above water level for bridge tracks over oceans and rivers.",
                        "Set to 0 to disable water bridging.")
                .defineInRange("bridgeWaterDistance", 5, 0, 20);

        BUILDER.pop();

        BUILDER.comment("Tunnel Settings",
                        "Controls tunnel decoration like torch placement")
                .push("tunnels");

        TUNNEL_LIGHT_SPACING = BUILDER
                .comment("Distance in blocks between light placements in tunnels.",
                        "Lights are placed on both walls at this interval.",
                        "Lighting block type is configured via biome settings data packs.")
                .defineInRange("lightSpacing", 14, 2, 64);

        BUILDER.pop();

        BUILDER.comment("Debug Settings")
                .push("debug");

        VERBOSE_LOGGING = BUILDER
                .comment("Enable verbose debug logging for track placement and chunk loading.",
                        "Warning: produces a lot of log output. Only enable when troubleshooting.")
                .define("verboseLogging", false);

        BUILDER.pop();

        BUILDER.comment("Station and Train Settings")
                .push("station_train");

        STARTING_STATION = BUILDER
                .comment("Whether to place a station at the starting track area with the starting train.",
                        "Has no effect if startingTrain is disabled.")
                .define("stationAtStart", true);

        BUILDER.pop();

        BUILDER.comment("Event Settings")
                .push("events");

        CUSTOM_EVENTS = BUILDER
                .comment("Enable placing custom event schematics along tracks.",
                        "Requires valid .nbt files in config/railways-untold/events/")
                .define("customEvents", false);

        EVENT_SEPARATION_MIN_DISTANCE = BUILDER
                .comment("", "Minimum distance in blocks between events.",
                        "After placing an event, the track must travel this far before another can appear.")
                .defineInRange("eventSeparationMinDistance", 500, 100, 5000);

        EVENT_CHANCE = BUILDER
                .comment("", "Chance (percent) of placing an event once minimum distance is met.",
                        "Checked each track segment after the minimum distance requirement.")
                .defineInRange("eventChance", 15, 1, 100);

        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();
}
