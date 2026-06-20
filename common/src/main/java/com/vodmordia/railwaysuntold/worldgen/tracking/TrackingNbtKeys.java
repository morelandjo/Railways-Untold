package com.vodmordia.railwaysuntold.worldgen.tracking;


/**
 * NBT key constants for track/segment boundary serialization.
 * Used in {@link ConnectedBoundarySavedData}.
 */
public final class TrackingNbtKeys {

    private TrackingNbtKeys() {
    }

    public static final String CHUNKS = "Chunks";
    public static final String CHUNK_KEY = "ChunkKey";
    public static final String SEGMENTS = "Segments";
    public static final String START = "Start";
    public static final String END = "End";
    public static final String TYPE = "Type";
    public static final String CURVE_POSITIONS = "CurvePositions";
    public static final String HEAD_ID = "HeadId";
}
