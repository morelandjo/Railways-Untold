package com.vodmordia.railwaysuntold.worldgen.survey;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * The composite output of a survey: each requested extractor's {@link SurveyData}, keyed by extractor
 * id. Consumers pull the typed data they care about; absent extractors simply aren't present.
 */
public final class SurveyResult {

    private final Map<String, SurveyData> byExtractor;

    public SurveyResult(Map<String, SurveyData> byExtractor) {
        this.byExtractor = Map.copyOf(byExtractor);
    }

    public static SurveyResult empty() {
        return new SurveyResult(Collections.emptyMap());
    }

    public Map<String, SurveyData> all() {
        return byExtractor;
    }

    /** The data for {@code id} if present and of the expected type. */
    public <T extends SurveyData> Optional<T> get(String id, Class<T> type) {
        SurveyData data = byExtractor.get(id);
        return type.isInstance(data) ? Optional.of(type.cast(data)) : Optional.empty();
    }

    @Nullable
    public SurveyData raw(String id) {
        return byExtractor.get(id);
    }

    public boolean isEmpty() {
        return byExtractor.isEmpty();
    }
}
