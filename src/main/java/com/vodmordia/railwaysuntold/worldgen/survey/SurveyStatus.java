package com.vodmordia.railwaysuntold.worldgen.survey;

/**
 * Lifecycle state of a single survey request as it moves through {@link SurveyManager}.
 */
public enum SurveyStatus {
    /** Just submitted; not yet checked against the persisted cache or ticketed. */
    PENDING,
    /** Chunks have been ticket-loaded; waiting for them all to reach the required ChunkStatus. */
    LOADING,
    /** All chunks resident; extractors are running this tick on the server thread. */
    EXTRACTING,
    /** Result extracted, persisted, and tickets released; callback fired. Terminal. */
    COMPLETE,
    /** Chunks never loaded in time, or extraction failed. Tickets released. Terminal. */
    FAILED
}
