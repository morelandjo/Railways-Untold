package com.vodmordia.railwaysuntold.worldgen.survey.persist;

import com.vodmordia.railwaysuntold.worldgen.survey.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;

import java.util.HashMap;
import java.util.Map;

/**
 * A persisted survey: the region it covers and the extractor results for it. Survey results are
 * deterministic per seed and never re-run, so this is permanent once written.
 */
public record StoredSurvey(RegionKey region, SurveyResult result) {

    public CompoundTag toNbt(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(SurveyNbtKeys.ANCHOR_X, region.anchor().x);
        tag.putInt(SurveyNbtKeys.ANCHOR_Z, region.anchor().z);
        tag.putInt(SurveyNbtKeys.RADIUS, region.radius());

        ListTag extractors = new ListTag();
        for (Map.Entry<String, SurveyData> e : result.all().entrySet()) {
            CompoundTag ex = new CompoundTag();
            ex.putString(SurveyNbtKeys.EXTRACTOR_ID, e.getKey());
            ex.put(SurveyNbtKeys.DATA, e.getValue().toNbt(provider));
            extractors.add(ex);
        }
        tag.put(SurveyNbtKeys.EXTRACTORS, extractors);
        return tag;
    }

    /** Rebuilds a StoredSurvey, skipping any extractor entry whose extractor is no longer registered. */
    public static StoredSurvey fromNbt(CompoundTag tag, HolderLookup.Provider provider) {
        RegionKey region = new RegionKey(
                new ChunkPos(tag.getInt(SurveyNbtKeys.ANCHOR_X), tag.getInt(SurveyNbtKeys.ANCHOR_Z)),
                tag.getInt(SurveyNbtKeys.RADIUS));

        Map<String, SurveyData> byExtractor = new HashMap<>();
        ListTag extractors = tag.getList(SurveyNbtKeys.EXTRACTORS, Tag.TAG_COMPOUND);
        for (int i = 0; i < extractors.size(); i++) {
            CompoundTag ex = extractors.getCompound(i);
            String id = ex.getString(SurveyNbtKeys.EXTRACTOR_ID);
            SurveyExtractor<?> extractor = SurveyExtractorRegistry.byId(id);
            if (extractor == null) {
                continue;
            }
            SurveyData data = extractor.fromNbt(ex.getCompound(SurveyNbtKeys.DATA), provider);
            if (data != null) {
                byExtractor.put(id, data);
            }
        }
        return new StoredSurvey(region, new SurveyResult(byExtractor));
    }
}
