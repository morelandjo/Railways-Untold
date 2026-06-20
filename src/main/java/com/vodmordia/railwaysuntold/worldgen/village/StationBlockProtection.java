package com.vodmordia.railwaysuntold.worldgen.village;

import com.vodmordia.railwaysuntold.worldgen.village.tracking.PersistentData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.HashSet;
import java.util.Set;

/**
 * Tracks individual block positions placed by station schematics so the
 * terrain clearing system can skip them.  Positions are stored as packed
 * longs ({@link BlockPos#asLong}) for compact storage and O(1) lookup.
 */
public class StationBlockProtection implements PersistentData {

    private static final String NBT_KEY = "ProtectedPositions";

    private final Set<Long> protectedPositions = new HashSet<>();

    /** Mark a block position as station-placed and protected from clearing. */
    public void protect(BlockPos pos) {
        protectedPositions.add(pos.asLong());
    }

    /** Check whether a position is protected. */
    public boolean isProtected(BlockPos pos) {
        return protectedPositions.contains(pos.asLong());
    }

    public void clearAll() {
        protectedPositions.clear();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLongArray(NBT_KEY, protectedPositions.stream().mapToLong(Long::longValue).toArray());
        return tag;
    }

    public void load(CompoundTag tag) {
        clearAll();
        if (tag.contains(NBT_KEY)) {
            for (long packed : tag.getLongArray(NBT_KEY)) {
                protectedPositions.add(packed);
            }
        }
    }
}
