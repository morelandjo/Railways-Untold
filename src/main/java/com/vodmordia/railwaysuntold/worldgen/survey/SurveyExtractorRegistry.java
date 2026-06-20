package com.vodmordia.railwaysuntold.worldgen.survey;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static registry of {@link SurveyExtractor}s, populated once at mod init (mirrors TrackPlacerRegistry).
 * The survey engine resolves extractors by id from here, so it never imports a concrete extractor.
 */
public final class SurveyExtractorRegistry {

    private static final Map<String, SurveyExtractor<?>> REGISTRY = new ConcurrentHashMap<>();

    private SurveyExtractorRegistry() {
    }

    public static void register(SurveyExtractor<?> extractor) {
        REGISTRY.put(extractor.id(), extractor);
    }

    public static SurveyExtractor<?> byId(String id) {
        return REGISTRY.get(id);
    }

    public static Collection<SurveyExtractor<?>> all() {
        return REGISTRY.values();
    }

    /** Test/teardown hook. */
    public static void clear() {
        REGISTRY.clear();
    }
}
