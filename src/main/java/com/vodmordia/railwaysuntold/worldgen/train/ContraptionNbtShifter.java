package com.vodmordia.railwaysuntold.worldgen.train;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * NBT shift for Create contraption data: translates every position by (shiftX, shiftZ). The position
 * walk is shared with the rotate/mirror transforms via {@link ContraptionNbtPositions}; only the
 * {@code BoundsFront} AABB is shift-specific.
 */
public final class ContraptionNbtShifter {

    private ContraptionNbtShifter() {}

    /** Shifts every position in the contraption NBT tree by (shiftX, shiftZ). No-op when both are zero. */
    public static void shiftContraption(CompoundTag contraptionNbt, int shiftX, int shiftZ) {
        if (shiftX == 0 && shiftZ == 0) return;

        ContraptionNbtPositions.transformPoints(contraptionNbt,
                (x, y, z) -> new int[]{x + shiftX, y, z + shiftZ}, null);

        // BoundsFront AABB (6 floats: minX,minY,minZ,maxX,maxY,maxZ).
        if (contraptionNbt.contains(ContraptionNbtKeys.BOUNDS_FRONT, Tag.TAG_LIST)) {
            ListTag boundsTag = contraptionNbt.getList(ContraptionNbtKeys.BOUNDS_FRONT, Tag.TAG_FLOAT);
            if (boundsTag.size() == 6) {
                ListTag shifted = new ListTag();
                shifted.add(FloatTag.valueOf(boundsTag.getFloat(0) + shiftX));
                shifted.add(FloatTag.valueOf(boundsTag.getFloat(1)));
                shifted.add(FloatTag.valueOf(boundsTag.getFloat(2) + shiftZ));
                shifted.add(FloatTag.valueOf(boundsTag.getFloat(3) + shiftX));
                shifted.add(FloatTag.valueOf(boundsTag.getFloat(4)));
                shifted.add(FloatTag.valueOf(boundsTag.getFloat(5) + shiftZ));
                contraptionNbt.put(ContraptionNbtKeys.BOUNDS_FRONT, shifted);
            }
        }
    }
}
