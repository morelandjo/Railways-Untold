package com.vodmordia.railwaysuntold.worldgen.train;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Characterizes the pure NBT-side glue helpers in {@link ContraptionSuperglue}: {@code writeVec3}'s
 * 3-double list encoding, and {@code generateGlueBoxesFromNbt}'s flood-fill of the block list into one
 * tight AABB per connected component - dropping single-block components (whose averaged size is exactly
 * 1.0, below the 1.01 threshold). 1.21.1-only: the class links Create's Contraption types for its live
 * injector; the bodies under test are identical on 1.20.1 (parity by inspection).
 */
class ContraptionSuperglueTest {

    private static CompoundTag contraptionWithBlocks(BlockPos... positions) {
        CompoundTag root = new CompoundTag();
        CompoundTag blocks = new CompoundTag();
        ListTag blockList = new ListTag();
        for (BlockPos pos : positions) {
            CompoundTag entry = new CompoundTag();
            entry.putLong(ContraptionNbtKeys.POS, pos.asLong());
            blockList.add(entry);
        }
        blocks.put(ContraptionNbtKeys.BLOCK_LIST, blockList);
        root.put(ContraptionNbtKeys.BLOCKS, blocks);
        return root;
    }

    @Test
    void writeVec3EncodesThreeDoublesInOrder() {
        ListTag v = ContraptionSuperglue.writeVec3(1.5, -2.0, 3.25);
        assertEquals(3, v.size());
        assertEquals(1.5, v.getDouble(0));
        assertEquals(-2.0, v.getDouble(1));
        assertEquals(3.25, v.getDouble(2));
    }

    @Test
    void adjacentBlocksFloodFillIntoOneTightBox() {
        // (0,0,0) and (1,0,0) are face-adjacent -> one component, box (0,0,0)-(2,1,1).
        List<AABB> boxes = ContraptionSuperglue.generateGlueBoxesFromNbt(
                contraptionWithBlocks(new BlockPos(0, 0, 0), new BlockPos(1, 0, 0)));
        assertEquals(1, boxes.size());
        AABB box = boxes.get(0);
        assertEquals(0, box.minX);
        assertEquals(2, box.maxX);
        assertEquals(1, box.maxY);
        assertEquals(1, box.maxZ);
    }

    @Test
    void isolatedSingleBlockComponentsAreDroppedBelowTheSizeThreshold() {
        // Two adjacent blocks (kept) plus one isolated block far away (dropped: averaged size 1.0).
        List<AABB> boxes = ContraptionSuperglue.generateGlueBoxesFromNbt(
                contraptionWithBlocks(new BlockPos(0, 0, 0), new BlockPos(1, 0, 0), new BlockPos(20, 0, 0)));
        assertEquals(1, boxes.size());
    }

    @Test
    void noBlockListYieldsNoBoxes() {
        assertEquals(0, ContraptionSuperglue.generateGlueBoxesFromNbt(new CompoundTag()).size());
    }
}
