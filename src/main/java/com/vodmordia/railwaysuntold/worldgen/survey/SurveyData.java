package com.vodmordia.railwaysuntold.worldgen.survey;

import com.vodmordia.railwaysuntold.worldgen.village.tracking.PersistentData;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/**
 * Ground-truth data produced by one {@link SurveyExtractor} for one surveyed region - exact structure
 * footprints, terrain profiles, coastline boundaries, etc. Persisted with the rest of a survey result
 * so it survives the region's chunks being released and the server restarting. Each implementation is
 * paired with an extractor whose {@link SurveyExtractor#id()} keys it in the {@link SurveyResult} and
 * its NBT.
 */
public interface SurveyData extends PersistentData {

    /** Serializes this data to NBT. The paired extractor's fromNbt reverses it. */
    CompoundTag toNbt(HolderLookup.Provider provider);
}
