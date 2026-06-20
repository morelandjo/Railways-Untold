package com.vodmordia.railwaysuntold.worldgen.train;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.function.Consumer;

/**
 * The single tree-walk over every (x,y,z) position in a Create contraption NBT. The shift / rotate /
 * mirror transforms all consume this one traversal, supplying only the per-point operation - so the
 * knowledge of WHERE positions live (block list, seats, actors, interactors, conductor seats, sound
 * sources, glue endpoints) and in WHICH encoding (long {@code Pos}, int-array {@code Pos}, compound
 * {@code {X,Y,Z}} or {@code {x,y,z}}) lives in exactly one place, so every transform walks the tree
 * the same way rather than maintaining its own copy.
 *
 * Every op preserves Y, so only X/Z are written back for the int/compound encodings (and the full
 * value for a long {@code Pos}), matching the original per-transform passes exactly. Two things stay with
 * the callers: AABB bounds ({@code BoundsFront}, glue min/max) are not points; and superglue {@code From}/
 * {@code To} endpoints are encoded inconsistently per transform (int vs double, compound vs list), so
 * they are left to each transform until that is reconciled.
 */
public final class ContraptionNbtPositions {

    /** Transforms one integer point. Must preserve Y. Returns {@code {x, y, z}}. */
    @FunctionalInterface
    public interface PointOp {
        int[] apply(int x, int y, int z);
    }

    private ContraptionNbtPositions() {}

    /**
     * Applies {@code point} to every position in the contraption. {@code blockEntryHook}, when non-null,
     * runs once per block-list entry for transform-specific block-entity data (copycat material etc.).
     */
    public static void transformPoints(CompoundTag contraptionNbt, PointOp point,
                                       @Nullable Consumer<CompoundTag> blockEntryHook) {
        if (contraptionNbt.contains(ContraptionNbtKeys.BLOCKS, Tag.TAG_COMPOUND)) {
            CompoundTag blocks = contraptionNbt.getCompound(ContraptionNbtKeys.BLOCKS);
            if (blocks.contains(ContraptionNbtKeys.BLOCK_LIST, Tag.TAG_LIST)) {
                ListTag blockList = blocks.getList(ContraptionNbtKeys.BLOCK_LIST, Tag.TAG_COMPOUND);
                for (int i = 0; i < blockList.size(); i++) {
                    CompoundTag entry = blockList.getCompound(i);
                    transformLongPos(entry, ContraptionNbtKeys.POS, point);
                    if (blockEntryHook != null) {
                        blockEntryHook.accept(entry);
                    }
                }
            }
        }

        if (contraptionNbt.contains(ContraptionNbtKeys.SEATS, Tag.TAG_LIST)) {
            transformPosList(contraptionNbt, ContraptionNbtKeys.SEATS, point);
        }

        for (String key : new String[]{ContraptionNbtKeys.ACTORS, ContraptionNbtKeys.INTERACTORS,
                ContraptionNbtKeys.CONDUCTOR_SEATS}) {
            if (contraptionNbt.contains(key, Tag.TAG_LIST)) {
                transformPosField(contraptionNbt.getList(key, Tag.TAG_COMPOUND), point);
            }
        }

        if (contraptionNbt.contains("SoundQueue", Tag.TAG_COMPOUND)) {
            CompoundTag soundQueue = contraptionNbt.getCompound("SoundQueue");
            if (soundQueue.contains("Sources", Tag.TAG_LIST)) {
                transformPosField(soundQueue.getList("Sources", Tag.TAG_COMPOUND), point);
            }
        }
    }

    /** A list whose entries are either compound {X,Y,Z}/{x,y,z} or int-array [x,y,z] (e.g. Seats). */
    private static void transformPosList(CompoundTag parent, String listKey, PointOp point) {
        ListTag compoundList = parent.getList(listKey, Tag.TAG_COMPOUND);
        if (!compoundList.isEmpty()) {
            for (int i = 0; i < compoundList.size(); i++) {
                transformCompoundXZ(compoundList.getCompound(i), point);
            }
            return;
        }
        ListTag intArrayList = parent.getList(listKey, Tag.TAG_INT_ARRAY);
        for (int i = 0; i < intArrayList.size(); i++) {
            transformIntArrayXZ(intArrayList.getIntArray(i), point);
        }
    }

    /** A list whose entries carry a {@code Pos} field as int-array, compound, or long. */
    private static void transformPosField(ListTag list, PointOp point) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (entry.contains(ContraptionNbtKeys.POS, Tag.TAG_INT_ARRAY)) {
                transformIntArrayXZ(entry.getIntArray(ContraptionNbtKeys.POS), point);
            } else if (entry.contains(ContraptionNbtKeys.POS, Tag.TAG_COMPOUND)) {
                transformCompoundXZ(entry.getCompound(ContraptionNbtKeys.POS), point);
            } else if (entry.contains(ContraptionNbtKeys.POS, Tag.TAG_LONG)) {
                transformLongPos(entry, ContraptionNbtKeys.POS, point);
            }
        }
    }

    private static void transformLongPos(CompoundTag holder, String key, PointOp point) {
        if (!holder.contains(key, Tag.TAG_LONG)) {
            return;
        }
        BlockPos pos = BlockPos.of(holder.getLong(key));
        int[] p = point.apply(pos.getX(), pos.getY(), pos.getZ());
        holder.putLong(key, new BlockPos(p[0], p[1], p[2]).asLong());
    }

    private static void transformCompoundXZ(CompoundTag pos, PointOp point) {
        if (pos.contains("X")) {
            int[] p = point.apply(pos.getInt("X"), pos.getInt("Y"), pos.getInt("Z"));
            pos.putInt("X", p[0]);
            pos.putInt("Z", p[2]);
        } else if (pos.contains("x")) {
            int[] p = point.apply(pos.getInt("x"), pos.getInt("y"), pos.getInt("z"));
            pos.putInt("x", p[0]);
            pos.putInt("z", p[2]);
        }
    }

    private static void transformIntArrayXZ(int[] arr, PointOp point) {
        if (arr.length >= 3) {
            int[] p = point.apply(arr[0], arr[1], arr[2]);
            arr[0] = p[0];
            arr[2] = p[2];
        }
    }
}
