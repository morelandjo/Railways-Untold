package com.vodmordia.railwaysuntold.config;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import me.shedaniel.cloth.clothconfig.shadowed.blue.endless.jankson.Comment;

@Config(name = "railways-untold/config")
public class ModConfig implements ConfigData {

    @ConfigEntry.Category("track_clearing")
    @ConfigEntry.BoundedDiscrete(min = 1, max = 16)
    @Comment("Number of blocks to clear horizontally on each side of the track")
    public long horizontalExpansion = 3;

    @ConfigEntry.Category("track_clearing")
    @ConfigEntry.BoundedDiscrete(min = 2, max = 32)
    @Comment("Number of blocks to clear vertically above the track")
    public long verticalExpansion = 6;

    @ConfigEntry.Category("world_generation")
    @Comment("Comma-separated list of chunk generator types where track generation is allowed. Uses the registry name (e.g. 'minecraft:noise', 'minecraft:flat'). You can omit the namespace for vanilla types (e.g. 'noise' matches 'minecraft:noise'). 'default' and 'noise' are treated as synonyms (both match the standard overworld). Modded generators can be added by their full ID (e.g. 'bigglobe:scripted'). Set to empty string to disable generation in all world types.")
    public String generateInWorldTypes = "minecraft:noise, minecraft:flat";

    @ConfigEntry.Category("features")
    @Comment("Whether to place a starting train at world generation")
    public boolean startingTrain = true;

    @ConfigEntry.Category("features")
    @Comment("Whether track branches (junctions) are enabled")
    public boolean branchesEnabled = true;

    @ConfigEntry.Category("features")
    @Comment("Whether branches can create additional branches (recursive branching). Only applies if branchesEnabled is true.")
    public boolean recursiveBranchingEnabled = true;

    @ConfigEntry.Category("features")
    @ConfigEntry.BoundedDiscrete(min = 0, max = 10)
    @Comment("Maximum depth for recursive branching. 0 = unlimited (could affect performance). Only applies when recursiveBranchingEnabled is true.")
    public long maxBranchDepth = 3;

    @ConfigEntry.Category("features")
    @ConfigEntry.BoundedDiscrete(min = 0, max = 100)
    @Comment("Maximum simultaneous active track heads. 0 = unlimited (could affect performance).")
    public long maxActiveHeads = 15;

    @ConfigEntry.Category("features")
    @ConfigEntry.BoundedDiscrete(min = 50, max = 1000)
    @Comment("Minimum distance in blocks between consecutive branches from the same track head. After spawning a branch, the parent head must travel this distance before branching again.")
    public long minBranchSpacing = 150;

    @ConfigEntry.Category("features")
    @Comment("Whether structure targeting is enabled. When true, tracks will path toward structures defined in biome settings data packs. When false, all structure targeting is disabled regardless of data pack settings.")
    public boolean structureTargeting = true;

    @ConfigEntry.Category("features")
    @Comment("Whether tracks should avoid obstacles by turning. When disabled, tracks will go straight through all terrain (tunneling through mountains, descending into ravines) without ever turning.")
    public boolean allAvoidance = true;


    @ConfigEntry.Category("features")
    @Comment("Enable side-by-side dual track mode. Two parallel tracks with opposite travel directions. Separation = horizontalExpansion * 2 (minimum 5 blocks). This feature is early alpha and buggy.")
    public boolean sideBySideTracks = false;

    @ConfigEntry.Category("features")
    @Comment("Whether a head running parallel and adjacent to another line should merge into it (curving across when touching, or steering over from up to ~70 blocks away) instead of running side by side.")
    public boolean parallelMergeEnabled = true;

    @ConfigEntry.Category("features")
    @Comment("Enable rail sidings (passing loops). When enabled, long straight sections of track will occasionally generate a parallel siding that diverges and reconnects.")
    public boolean sidingsEnabled = true;

    @ConfigEntry.Category("features")
    @Comment("Minimum number of consecutive straight segments before a siding can be placed. Each segment is approximately 16 blocks long.")
    public int sidingMinStraightSegments = 6;


    @ConfigEntry.Category("pillars")
    @ConfigEntry.BoundedDiscrete(min = 0, max = 32)
    @Comment("Minimum height above ground to use bridge decking. When the track is this many blocks above ground, bridge decking and pillar schematics are placed instead of terrain fill. Set to 0 to only use bridge decking over water.")
    public long bridgeElevationThreshold = 4;

    @ConfigEntry.Category("terrain")
    @Comment("Allow curves to change elevation while turning (banking curves). When false, curves are always flat and elevation changes happen on straight segments. When true, curves can slope up/down while turning, creating a roller coaster effect.")
    public boolean bankingCurves = false;

    @ConfigEntry.Category("terrain")
    @Comment("Whether tracks can make gentle lateral shifts (slight sideways beziers). When disabled, tracks stay on the cardinal axis and use SCurve45 diagonals or L-shaped curves to correct accumulated drift.")
    public boolean laterals = false;

    @ConfigEntry.Category("terrain")
    @ConfigEntry.BoundedDiscrete(min = 2, max = 20)
    @Comment("Lateral drift correction threshold (in blocks). When the track drifts this far off the cardinal axis, an SCurve45 diagonal or curve correction is inserted to realign with the planned route.")
    public long lateralOffset = 5;

    @ConfigEntry.Category("terrain")
    @ConfigEntry.BoundedDiscrete(min = 1, max = 5)
    @Comment("Rise component of the maximum slope ratio (rise:run). Combined with slopeRun to determine max track steepness. Example: rise=1, run=2 means max slope of 1/2 (0.5).")
    public long slopeRise = 1;

    @ConfigEntry.Category("terrain")
    @ConfigEntry.BoundedDiscrete(min = 1, max = 20)
    @Comment("Run component of the maximum slope ratio (rise:run). Combined with slopeRise to determine max track steepness. Example: rise=1, run=2 means max slope of 1/2 (0.5).")
    public long slopeRun = 4;

    @ConfigEntry.Category("terrain")
    @ConfigEntry.BoundedDiscrete(min = 10, max = 50)
    @Comment("Minimum curve radius in blocks. Smaller values allow tighter turns but may cause train navigation issues.")
    public long minCurveRadius = 15;

    @ConfigEntry.Category("terrain")
    @ConfigEntry.BoundedDiscrete(min = 10, max = 100)
    @Comment("Maximum curve radius in blocks. Larger values create wider, smoother turns.")
    public long maxCurveRadius = 25;

    @ConfigEntry.Category("terrain")
    @ConfigEntry.BoundedDiscrete(min = 0, max = 20)
    @Comment("Minimum height above water level for bridge tracks over oceans and rivers. Set to 0 to disable water bridging.")
    public long bridgeWaterDistance = 5;

    @ConfigEntry.Category("tunnels")
    @ConfigEntry.BoundedDiscrete(min = 2, max = 64)
    @Comment("Distance in blocks between light placements in tunnels")
    public long lightSpacing = 14;


    @ConfigEntry.Category("custom_nbt")
    @Comment("Whether to place a station at the starting track area with the starting train. Has no effect if startingTrain is disabled. Custom trains and stations are now configured via data packs.")
    public boolean stationAtStart = true;

    @ConfigEntry.Category("track_material")
    @Comment("Track width/gauge. Values: standard, wide, narrow. Non-standard values require Steam 'n' Rails. Phantom tracks only support standard gauge.")
    public String trackGauge = "standard";

    @ConfigEntry.Category("events")
    @Comment("Enable placing custom event schematics along generated tracks. Place .nbt files in config/railways-untold/events/")
    public boolean customEvents = false;

    @ConfigEntry.Category("events")
    @ConfigEntry.BoundedDiscrete(min = 100, max = 5000)
    @Comment("Minimum distance in blocks between consecutive events along a track")
    public long eventSeparationMinDistance = 500;

    @ConfigEntry.Category("events")
    @ConfigEntry.BoundedDiscrete(min = 1, max = 100)
    @Comment("Chance (percent) of placing an event when eligible (after min distance)")
    public long eventChance = 15;

    @ConfigEntry.Category("debug")
    @Comment("Enable verbose debug logging for track placement and chunk loading.")
    public boolean verboseLogging = false;
}
