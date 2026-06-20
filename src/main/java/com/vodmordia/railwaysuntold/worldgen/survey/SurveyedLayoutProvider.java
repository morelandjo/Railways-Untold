package com.vodmordia.railwaysuntold.worldgen.survey;

import com.vodmordia.railwaysuntold.worldgen.survey.extractor.StructureExtractor;
import com.vodmordia.railwaysuntold.worldgen.survey.extractor.SurveyedStructureLayout;
import com.vodmordia.railwaysuntold.worldgen.survey.persist.StoredSurvey;
import com.vodmordia.railwaysuntold.worldgen.survey.persist.SurveySavedData;
import com.vodmordia.railwaysuntold.worldgen.village.PredictedVillageLayout;
import com.vodmordia.railwaysuntold.worldgen.village.VillageLayoutPredictor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.List;
import java.util.Optional;

/**
 * The single precedence point for village layout: returns the surveyed ground-truth layout if one has
 * been persisted, else falls back to the cheap {@code structure.generate} prediction. Consumers call
 * {@link #resolveLayout} instead of {@link VillageLayoutPredictor#predict} directly, and
 * {@link #requestSurvey} to lazily kick off a (one-time, permanent) survey so the next pass is exact.
 */
public final class SurveyedLayoutProvider {

    /** Chunk radius surveyed around a village's center chunk (7x7 covers typical village sprawl). */
    private static final int VILLAGE_REGION_RADIUS = 3;

    private SurveyedLayoutProvider() {
    }

    private static RegionKey villageRegion(ChunkPos villageChunk) {
        return RegionKey.around(villageChunk, VILLAGE_REGION_RADIUS);
    }

    /** Survey layout if present, else the prediction (may be null if the prediction itself fails). */
    public static PredictedVillageLayout resolveLayout(ServerLevel level, ChunkPos villageChunk) {
        Optional<StoredSurvey> stored = SurveySavedData.get(level).find(villageRegion(villageChunk));
        if (stored.isPresent()) {
            Optional<SurveyedStructureLayout> layout =
                    stored.get().result().get(StructureExtractor.ID, SurveyedStructureLayout.class);
            if (layout.isPresent()) {
                return layout.get().asPredictedLayout();
            }
        }
        return VillageLayoutPredictor.predict(level, villageChunk);
    }

    /** Lazily fire a one-time survey of the village region (no-op if already surveyed or in flight). */
    public static void requestSurvey(ServerLevel level, ChunkPos villageChunk) {
        RegionKey key = villageRegion(villageChunk);
        if (SurveySavedData.get(level).contains(key) || SurveyManager.isInFlight(key)) {
            return;
        }
        SurveyManager.request(level, new SurveyRequest(
                key, List.of(StructureExtractor.ID), ChunkStatus.FULL, null));
    }
}
