package com.vodmordia.railwaysuntold.worldgen.survey;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.ChunkStatus;

/**
 * A pluggable producer of one kind of ground-truth fact from a surveyed region (structure footprints,
 * terrain profile, coastline, ...). Register implementations once via {@link SurveyExtractorRegistry};
 * the survey engine knows nothing about concrete extractors, so new consumers add a new extractor + a
 * new {@link SurveyData} type without touching the core.
 *
 * @param <T> the data type this extractor produces
 */
public interface SurveyExtractor<T extends SurveyData> {

    /** Stable id; the key for this extractor's data in {@link SurveyResult} and in persisted NBT. */
    String id();

    /**
     * Minimum status every region chunk must reach before {@link #extract} may run. Structure geometry
     * is final at {@code STRUCTURE_STARTS} (cheap); block-level terrain/water needs {@code FULL}.
     */
    ChunkStatus requiredStatus();

    /** Reads the region (server thread, all chunks at {@link #requiredStatus}) and produces the data. */
    T extract(SurveyContext ctx);

    /** Reconstructs persisted data from NBT produced by {@link SurveyData#toNbt}. */
    T fromNbt(CompoundTag tag);
}
