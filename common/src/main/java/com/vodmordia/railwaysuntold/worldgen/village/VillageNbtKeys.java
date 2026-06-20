package com.vodmordia.railwaysuntold.worldgen.village;


/**
 * NBT key constants for village targeting, station, and bounds serialization.
 */
public final class VillageNbtKeys {

    private VillageNbtKeys() {
    }

    // Village discovery keys
    public static final String VILLAGES = "Villages";
    public static final String ID = "Id";
    public static final String CENTER = "Center";
    public static final String TYPE = "Type";

    // Sub-tracker compound keys
    public static final String ASSIGNMENTS = "Assignments";
    public static final String ATTEMPTED_VILLAGES = "AttemptedVillages";
    public static final String PLACED_STATIONS = "PlacedStations";
    public static final String STATION_BLOCK_PROTECTION = "StationBlockProtection";
    public static final String NETWORK_EDGES = "NetworkEdges";

    // Network edge keys
    public static final String EDGE_FROM = "From";
    public static final String EDGE_TO = "To";

    // Assignment tracker keys
    public static final String VILLAGE_ID = "VillageId";
    public static final String HEAD_ID = "HeadId";

    // Attempted village keys
    public static final String REASON = "Reason";

    // Station bounds keys
    public static final String STATIONS = "Stations";
    public static final String MIN_CORNER = "MinCorner";
    public static final String MAX_CORNER = "MaxCorner";

}
