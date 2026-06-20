package com.vodmordia.railwaysuntold.worldgen.integration.create;


/**
 * Field and class name constants used for Create mod reflection access.
 */
public final class CreateReflectionFields {

    private CreateReflectionFields() {
    }

    // TrackBlock property fields
    public static final String SHAPE = "SHAPE";
    public static final String HAS_BE = "HAS_BE";

    // TrackShape enum values
    public static final String XO = "XO";
    public static final String ZO = "ZO";
    public static final String PD = "PD";
    public static final String ND = "ND";

    // GirderBlock property fields
    public static final String AXIS = "AXIS";
    public static final String TOP = "TOP";
    public static final String BOTTOM = "BOTTOM";
    public static final String X = "X";
    public static final String Z = "Z";

    // AllBlocks fields
    public static final String TRACK = "TRACK";
    public static final String METAL_GIRDER = "METAL_GIRDER";
    public static final String SMALL_BOGEY = "SMALL_BOGEY";
    public static final String LARGE_BOGEY = "LARGE_BOGEY";

    // AllBogeyStyles fields
    public static final String STANDARD = "STANDARD";

    // CatnipServices fields
    public static final String NETWORK = "NETWORK";

    // Railway mod (Steam 'n' Rails) integration
    public static final String TRACK_MATERIAL_ALL = "ALL";
    public static final String GET_BLOCK = "getBlock";
}
