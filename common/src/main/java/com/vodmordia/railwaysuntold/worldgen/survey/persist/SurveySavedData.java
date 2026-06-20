package com.vodmordia.railwaysuntold.worldgen.survey.persist;

import com.vodmordia.railwaysuntold.worldgen.survey.RegionKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Per-level permanent cache of completed surveys, keyed by {@link RegionKey#id()}. Survives the
 * surveyed region's chunks being released and the server restarting, so a region is surveyed at most
 * once. Same SavedData/computeIfAbsent pattern as VillageTargetingSavedData.
 */
public class SurveySavedData extends SavedData {

    private static final String DATA_NAME = "railwaysuntold_survey";

    private final Map<String, StoredSurvey> byRegion = new HashMap<>();

    public static SurveySavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                SurveySavedData::load,
                SurveySavedData::new,
                DATA_NAME);
    }

    public Optional<StoredSurvey> find(RegionKey key) {
        return Optional.ofNullable(byRegion.get(key.id()));
    }

    public boolean contains(RegionKey key) {
        return byRegion.containsKey(key.id());
    }

    public void store(StoredSurvey survey) {
        byRegion.put(survey.region().id(), survey);
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (StoredSurvey survey : byRegion.values()) {
            list.add(survey.toNbt());
        }
        tag.put(SurveyNbtKeys.SURVEYS, list);
        return tag;
    }

    public static SurveySavedData load(CompoundTag tag) {
        SurveySavedData data = new SurveySavedData();
        if (tag.contains(SurveyNbtKeys.SURVEYS, Tag.TAG_LIST)) {
            ListTag list = tag.getList(SurveyNbtKeys.SURVEYS, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                StoredSurvey survey = StoredSurvey.fromNbt(list.getCompound(i));
                data.byRegion.put(survey.region().id(), survey);
            }
        }
        return data;
    }
}
