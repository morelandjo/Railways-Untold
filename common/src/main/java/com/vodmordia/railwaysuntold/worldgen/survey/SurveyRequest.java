package com.vodmordia.railwaysuntold.worldgen.survey;

import net.minecraft.world.level.chunk.ChunkStatus;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Consumer;

/**
 * A request to survey one region with a chosen set of extractors. {@code maxRequiredStatus} is the
 * strongest ChunkStatus any requested extractor needs (drives the load/wait policy). {@code onComplete}
 * fires on the server thread when the result is ready (or empty on failure); may be null to just
 * populate the persisted cache for later polling.
 */
public record SurveyRequest(
        RegionKey region,
        List<String> extractorIds,
        ChunkStatus maxRequiredStatus,
        @Nullable Consumer<SurveyResult> onComplete) {

    public SurveyRequest {
        extractorIds = List.copyOf(extractorIds);
    }
}
