package com.vodmordia.railwaysuntold.worldgen.village;

import com.vodmordia.railwaysuntold.util.nbt.NbtHelper;
import com.vodmordia.railwaysuntold.util.spatial.DirectionUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.*;

/**
 * Tracks which expansion heads have been assigned to which villages.
 */
public class VillageAssignmentTracker {

    private static final String HEAD_ANGLES = "HeadAngles";
    private static final String ANGLE = "Angle";
    private static final String IS_ORIGINAL = "IsOriginal";

    // Map: VillageId -> HeadId
    private final Map<UUID, UUID> villageToHead = new HashMap<>();

    // Map: HeadId -> VillageId
    private final Map<UUID, UUID> headToVillage = new HashMap<>();

    // Map: HeadId -> angle from spawn (0-360)
    private final Map<UUID, Double> headToAngle = new HashMap<>();

    // Tracks which heads are original (non-branch) heads
    private final Set<UUID> originalHeadIds = new HashSet<>();

    /**
     * Assign a village to a head. Returns false (no state change) if the village is
     * already assigned to any head, or if the head is already bound to a different
     * village. Callers that legitimately need to re-bind a head must call
     * unassignHead first.
     *
     * @return true if assignment succeeded, false otherwise
     */
    public boolean assignVillage(UUID villageId, UUID headId) {
        if (villageId == null || headId == null) {
            return false;
        }

        if (villageToHead.containsKey(villageId)) {
            return false;
        }

        if (headToVillage.containsKey(headId)) {
            return false;
        }

        villageToHead.put(villageId, headId);
        headToVillage.put(headId, villageId);

        return true;
    }

    /**
     * Re-bind a head to a village, releasing the head's previous village binding (if
     * any) atomically. Fails (no state change) only if the new village is already
     * assigned to a different head - rebinding a head from its own existing village
     * is a no-op success.
     *
     * @return true if the binding succeeded, false if the village belongs to another head
     */
    public boolean reassignVillage(UUID villageId, UUID headId) {
        if (villageId == null || headId == null) {
            return false;
        }

        UUID existingAssignee = villageToHead.get(villageId);
        if (existingAssignee != null && !existingAssignee.equals(headId)) {
            return false;
        }

        UUID previousVillage = headToVillage.get(headId);
        if (previousVillage != null && !previousVillage.equals(villageId)) {
            villageToHead.remove(previousVillage);
        }

        villageToHead.put(villageId, headId);
        headToVillage.put(headId, villageId);

        return true;
    }

    /**
     * Remove all assignments for a specific head (when head is completed/removed)
     */
    public void unassignHead(UUID headId) {
        if (headId == null) {
            return;
        }

        UUID villageId = headToVillage.remove(headId);
        if (villageId != null) {
            villageToHead.remove(villageId);
        }
        headToAngle.remove(headId);
    }

    /**
     * Register the angular sector for a head's village target.
     */
    public void registerHeadAngle(UUID headId, double angle, boolean isOriginal) {
        headToAngle.put(headId, angle);
        if (isOriginal) {
            originalHeadIds.add(headId);
        }
    }

    /**
     * Check if a candidate angle is too close to any other head's registered angle.
     */
    public boolean isAngleTooClose(double candidateAngle, UUID excludeHeadId, double minSeparation) {
        for (Map.Entry<UUID, Double> entry : headToAngle.entrySet()) {
            if (entry.getKey().equals(excludeHeadId)) {
                continue;
            }
            if (DirectionUtil.angularDistance(candidateAngle, entry.getValue()) < minSeparation) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a candidate angle is too close, using reduced separation for branch heads.
     * Branch heads use a smaller separation angle since they naturally diverge from their parent.
     *
     * @param candidateAngle       The angle to check (0-360)
     * @param excludeHeadId        Head to exclude from comparison
     * @param originalSeparation   Minimum angular separation for original heads
     * @param branchSeparation     Minimum angular separation for branch heads (typically smaller)
     * @param isBranch             Whether the candidate head is a branch
     * @return true if the angle is too close to another head
     */
    public boolean isAngleTooClose(double candidateAngle, UUID excludeHeadId,
                                    double originalSeparation, double branchSeparation,
                                    boolean isBranch) {
        double separation = isBranch ? branchSeparation : originalSeparation;
        return isAngleTooClose(candidateAngle, excludeHeadId, separation);
    }

    /**
     * Check if a village is already assigned to a head
     */
    public boolean isVillageAssigned(UUID villageId) {
        return villageToHead.containsKey(villageId);
    }

    /**
     * Clear all assignments
     */
    public void clearAll() {
        villageToHead.clear();
        headToVillage.clear();
        headToAngle.clear();
        originalHeadIds.clear();
    }

    /**
     * Save assignments to NBT
     */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.put(VillageNbtKeys.ASSIGNMENTS, NbtHelper.saveUuidToUuidMap(villageToHead, VillageNbtKeys.VILLAGE_ID, VillageNbtKeys.HEAD_ID));

        // Save head angles
        ListTag angleList = new ListTag();
        for (Map.Entry<UUID, Double> entry : headToAngle.entrySet()) {
            CompoundTag angleTag = new CompoundTag();
            angleTag.putUUID(VillageNbtKeys.HEAD_ID, entry.getKey());
            angleTag.putDouble(ANGLE, entry.getValue());
            angleTag.putBoolean(IS_ORIGINAL, originalHeadIds.contains(entry.getKey()));
            angleList.add(angleTag);
        }
        tag.put(HEAD_ANGLES, angleList);

        return tag;
    }

    /**
     * Load assignments from NBT
     */
    public void load(CompoundTag tag) {
        clearAll();
        NbtHelper.loadUuidToUuidMap(tag, VillageNbtKeys.ASSIGNMENTS, VillageNbtKeys.VILLAGE_ID, VillageNbtKeys.HEAD_ID, (villageId, headId) -> {
            villageToHead.put(villageId, headId);
            headToVillage.put(headId, villageId);
        });

        // Load head angles
        if (tag.contains(HEAD_ANGLES, Tag.TAG_LIST)) {
            ListTag angleList = tag.getList(HEAD_ANGLES, Tag.TAG_COMPOUND);
            for (int i = 0; i < angleList.size(); i++) {
                CompoundTag angleTag = angleList.getCompound(i);
                if (angleTag.hasUUID(VillageNbtKeys.HEAD_ID)) {
                    UUID headId = angleTag.getUUID(VillageNbtKeys.HEAD_ID);
                    double angle = angleTag.getDouble(ANGLE);
                    headToAngle.put(headId, angle);
                    if (angleTag.getBoolean(IS_ORIGINAL)) {
                        originalHeadIds.add(headId);
                    }
                }
            }
        }
    }
}
