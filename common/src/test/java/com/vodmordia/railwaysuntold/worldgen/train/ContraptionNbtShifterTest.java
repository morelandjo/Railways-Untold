package com.vodmordia.railwaysuntold.worldgen.train;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Characterizes {@link ContraptionNbtShifter#shiftContraption} - the pure NBT pass that translates every
 * position in a Create contraption by (x,z). Covers the distinct position encodings it has to handle
 * (a block-list long {@code Pos}, an actor int-array {@code Pos}, the 6-float {@code BoundsFront}) and the
 * two load-bearing invariants: it touches X and Z but never Y, and a shift followed by the inverse shift
 * is the identity.
 */
class ContraptionNbtShifterTest {

    /** A contraption NBT exercising each position encoding the shifter walks. */
    private static CompoundTag sampleContraption() {
        CompoundTag root = new CompoundTag();

        CompoundTag blocks = new CompoundTag();
        ListTag blockList = new ListTag();
        CompoundTag block = new CompoundTag();
        block.putLong(ContraptionNbtKeys.POS, BlockPos.ZERO.asLong());
        blockList.add(block);
        blocks.put(ContraptionNbtKeys.BLOCK_LIST, blockList);
        root.put(ContraptionNbtKeys.BLOCKS, blocks);

        ListTag actors = new ListTag();
        CompoundTag actor = new CompoundTag();
        actor.putIntArray("Pos", new int[]{2, 7, 4});
        actors.add(actor);
        root.put(ContraptionNbtKeys.ACTORS, actors);

        ListTag bounds = new ListTag();
        for (float f : new float[]{0, 0, 0, 1, 2, 1}) {
            bounds.add(FloatTag.valueOf(f));
        }
        root.put(ContraptionNbtKeys.BOUNDS_FRONT, bounds);

        return root;
    }

    private static long blockPosLong(CompoundTag root) {
        return root.getCompound(ContraptionNbtKeys.BLOCKS)
                .getList(ContraptionNbtKeys.BLOCK_LIST, Tag.TAG_COMPOUND)
                .getCompound(0).getLong(ContraptionNbtKeys.POS);
    }

    private static int[] actorPos(CompoundTag root) {
        return root.getList(ContraptionNbtKeys.ACTORS, Tag.TAG_COMPOUND).getCompound(0).getIntArray("Pos");
    }

    @Test
    void shiftByZeroIsANoOp() {
        CompoundTag c = sampleContraption();
        ContraptionNbtShifter.shiftContraption(c, 0, 0);
        assertEquals(BlockPos.ZERO.asLong(), blockPosLong(c));
        assertArrayEquals(new int[]{2, 7, 4}, actorPos(c));
    }

    @Test
    void shiftMovesXAndZAcrossEveryEncodingButLeavesYUntouched() {
        CompoundTag c = sampleContraption();
        ContraptionNbtShifter.shiftContraption(c, 5, 3);

        // block-list long Pos: (0,0,0) -> (5,0,3)
        assertEquals(new BlockPos(5, 0, 3), BlockPos.of(blockPosLong(c)));
        // actor int-array Pos: (2,7,4) -> (7,7,7), Y (index 1) untouched
        assertArrayEquals(new int[]{7, 7, 7}, actorPos(c));
        // BoundsFront 6-float AABB: x at 0/3, z at 2/5, y at 1/4 untouched
        ListTag b = c.getList(ContraptionNbtKeys.BOUNDS_FRONT, Tag.TAG_FLOAT);
        assertEquals(5f, b.getFloat(0));
        assertEquals(0f, b.getFloat(1));
        assertEquals(3f, b.getFloat(2));
        assertEquals(6f, b.getFloat(3));
        assertEquals(2f, b.getFloat(4));
        assertEquals(4f, b.getFloat(5));
    }

    @Test
    void shiftThenInverseShiftIsTheIdentity() {
        CompoundTag c = sampleContraption();
        ContraptionNbtShifter.shiftContraption(c, 12, -7);
        ContraptionNbtShifter.shiftContraption(c, -12, 7);
        assertEquals(BlockPos.ZERO.asLong(), blockPosLong(c));
        assertArrayEquals(new int[]{2, 7, 4}, actorPos(c));
    }
}
