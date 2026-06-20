package com.vodmordia.railwaysuntold.worldgen.survey;

import com.vodmordia.railwaysuntold.worldgen.survey.persist.StoredSurvey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure contract tests for the survey core: region footprint, result typing, and the NBT round-trip
 * through the extractor registry. No ServerLevel needed (runs on both branches' common test set).
 */
class SurveyPersistenceTest {

    /** A trivial extractor + data so the round-trip exercises the registry-driven fromNbt path. */
    private record FakeData(int value) implements SurveyData {
        @Override
        public CompoundTag toNbt() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("v", value);
            return tag;
        }
    }

    private static final class FakeExtractor implements SurveyExtractor<FakeData> {
        @Override public String id() { return "fake"; }
        @Override public ChunkStatus requiredStatus() { return ChunkStatus.STRUCTURE_STARTS; }
        @Override public FakeData extract(SurveyContext ctx) { return new FakeData(0); }
        @Override public FakeData fromNbt(CompoundTag tag) {
            return new FakeData(tag.getInt("v"));
        }
    }

    @AfterEach
    void cleanup() {
        SurveyExtractorRegistry.clear();
    }

    @Test
    void regionFootprintIsSquareOfChunks() {
        RegionKey key = RegionKey.around(new ChunkPos(10, 20), 1);
        assertEquals(9, key.chunks().size(), "radius 1 => 3x3 chunks");
        assertTrue(key.chunks().contains(new ChunkPos(10, 20)), "includes the anchor");
        assertTrue(key.chunks().contains(new ChunkPos(11, 21)), "includes the corner");
        assertFalse(key.chunks().contains(new ChunkPos(12, 20)), "excludes outside the radius");
    }

    @Test
    void surveyResultTypingIsSafe() {
        SurveyResult result = new SurveyResult(Map.of("fake", new FakeData(7)));
        assertEquals(7, result.get("fake", FakeData.class).orElseThrow().value());
        assertTrue(result.get("missing", FakeData.class).isEmpty(), "absent id => empty");
    }

    @Test
    void storedSurveyRoundTripsThroughRegistry() {
        SurveyExtractorRegistry.register(new FakeExtractor());

        RegionKey key = RegionKey.around(new ChunkPos(-52, -20), 2);
        StoredSurvey stored = new StoredSurvey(key, new SurveyResult(Map.of("fake", new FakeData(42))));

        CompoundTag tag = stored.toNbt();
        StoredSurvey parsed = StoredSurvey.fromNbt(tag);

        assertEquals(key.id(), parsed.region().id());
        assertEquals(2, parsed.region().radius());
        assertEquals(42, parsed.result().get("fake", FakeData.class).orElseThrow().value());
    }

    @Test
    void unregisteredExtractorEntryIsSkippedOnLoad() {
        // Write with the extractor registered, then load with it gone - that entry is dropped, no crash.
        SurveyExtractorRegistry.register(new FakeExtractor());
        CompoundTag tag = new StoredSurvey(
                RegionKey.around(new ChunkPos(0, 0), 0),
                new SurveyResult(Map.of("fake", new FakeData(1)))).toNbt();
        SurveyExtractorRegistry.clear();

        StoredSurvey parsed = StoredSurvey.fromNbt(tag);
        assertTrue(parsed.result().isEmpty(), "data for an unregistered extractor is skipped");
    }
}
