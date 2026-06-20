package com.vodmordia.railwaysuntold.worldgen.village;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.util.nbt.NbtHelper;
import com.vodmordia.railwaysuntold.worldgen.village.tracking.PersistentData;
import net.minecraft.nbt.CompoundTag;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks villages that have been attempted (station placed or confirmed non-existent).
 */
public class AttemptedVillageTracker implements PersistentData {

    private static final Logger LOGGER = LogUtils.getLogger();

    public enum AttemptReason {
        STATION_PLACED,            // Successfully reached village and placed station
        DOES_NOT_EXIST,            // Predicted village doesn't actually exist
        NO_VALID_APPROACH,         // Village exists but no valid runway could be found
        LAYOUT_PREDICTION_FAILED,  // Village biome doesn't support any village variant
        TOO_CLOSE_TO_SPAWN,        // Village is within VILLAGE_MIN_DISTANCE_FROM_SPAWN
        NO_VIABLE_STATION_POSITION // Layout confirmed but no station position works from any direction
    }

    private final Map<UUID, AttemptReason> attemptedVillages = new HashMap<>();

    public void markVillageAttempted(UUID villageId, AttemptReason reason) {
        if (villageId == null || reason == null) {
            return;
        }
        attemptedVillages.put(villageId, reason);
    }

    public boolean isVillageAttempted(UUID villageId) {
        return attemptedVillages.containsKey(villageId);
    }

    public void clearAll() {
        attemptedVillages.clear();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.put(VillageNbtKeys.ATTEMPTED_VILLAGES, NbtHelper.saveUuidToStringMap(attemptedVillages, VillageNbtKeys.VILLAGE_ID, VillageNbtKeys.REASON));
        return tag;
    }

    public void load(CompoundTag tag) {
        clearAll();
        NbtHelper.loadUuidToStringMap(tag, VillageNbtKeys.ATTEMPTED_VILLAGES, VillageNbtKeys.VILLAGE_ID, VillageNbtKeys.REASON, (villageId, reasonStr) -> {
            try {
                AttemptReason reason = AttemptReason.valueOf(reasonStr);
                attemptedVillages.put(villageId, reason);
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Skipping village {} with unknown attempt reason: {}", villageId, reasonStr);
            }
        });
    }
}
