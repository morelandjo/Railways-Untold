package com.vodmordia.railwaysuntold.worldgen.train;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Characterizes {@link ContraptionNbtRotator#rotateContraptionNbt} - the pure N×90° clockwise rotation of
 * a Create contraption's positions, AABB, and palette facing. The load-bearing invariant is that four
 * single-step rotations return the contraption to its starting state (positions, BoundsFront, and the
 * facing palette property all round-trip), which also pins {@link ContraptionNbtRotator#cwStepsToRotation}.
 */
class ContraptionNbtRotatorTest {

    /** A contraption with a block (long Pos), an actor (int-array Pos), a facing palette entry, and BoundsFront. */
    private static CompoundTag sample() {
        CompoundTag root = new CompoundTag();

        CompoundTag blocks = new CompoundTag();
        ListTag blockList = new ListTag();
        CompoundTag block = new CompoundTag();
        block.putLong(ContraptionNbtKeys.POS, new BlockPos(1, 0, 0).asLong());
        blockList.add(block);
        blocks.put(ContraptionNbtKeys.BLOCK_LIST, blockList);

        ListTag palette = new ListTag();
        CompoundTag pEntry = new CompoundTag();
        CompoundTag props = new CompoundTag();
        props.putString("facing", "north");
        pEntry.put("Properties", props);
        palette.add(pEntry);
        blocks.put(ContraptionNbtKeys.PALETTE, palette);
        root.put(ContraptionNbtKeys.BLOCKS, blocks);

        ListTag actors = new ListTag();
        CompoundTag actor = new CompoundTag();
        actor.putIntArray("Pos", new int[]{2, 7, 3});
        actors.add(actor);
        root.put(ContraptionNbtKeys.ACTORS, actors);

        ListTag bounds = new ListTag();
        for (float f : new float[]{0, 0, 0, 2, 1, 4}) {
            bounds.add(FloatTag.valueOf(f));
        }
        root.put(ContraptionNbtKeys.BOUNDS_FRONT, bounds);

        return root;
    }

    private static BlockPos blockPos(CompoundTag root) {
        return BlockPos.of(root.getCompound(ContraptionNbtKeys.BLOCKS)
                .getList(ContraptionNbtKeys.BLOCK_LIST, Tag.TAG_COMPOUND)
                .getCompound(0).getLong(ContraptionNbtKeys.POS));
    }

    private static int[] actorPos(CompoundTag root) {
        return root.getList(ContraptionNbtKeys.ACTORS, Tag.TAG_COMPOUND).getCompound(0).getIntArray("Pos");
    }

    private static String facing(CompoundTag root) {
        return root.getCompound(ContraptionNbtKeys.BLOCKS)
                .getList(ContraptionNbtKeys.PALETTE, Tag.TAG_COMPOUND)
                .getCompound(0).getCompound("Properties").getString("facing");
    }

    @Test
    void oneStepRotatesPositionsClockwiseAboutY() {
        CompoundTag c = sample();
        ContraptionNbtRotator.rotateContraptionNbt(c, 1);
        // block (1,0,0) -> (0,0,1)
        assertEquals(new BlockPos(0, 0, 1), blockPos(c));
        // actor (2,7,3): x=2,z=3 -> (-3,7,2), Y untouched
        assertArrayEquals(new int[]{-3, 7, 2}, actorPos(c));
    }

    @Test
    void fourStepsReturnEverythingToStart() {
        CompoundTag c = sample();
        for (int i = 0; i < 4; i++) {
            ContraptionNbtRotator.rotateContraptionNbt(c, 1);
        }
        assertEquals(new BlockPos(1, 0, 0), blockPos(c));
        assertArrayEquals(new int[]{2, 7, 3}, actorPos(c));
        assertEquals("north", facing(c));
        ListTag b = c.getList(ContraptionNbtKeys.BOUNDS_FRONT, Tag.TAG_FLOAT);
        assertEquals(0f, b.getFloat(0));
        assertEquals(2f, b.getFloat(3));
        assertEquals(4f, b.getFloat(5));
    }

    @Test
    void zeroStepsIsANoOp() {
        CompoundTag c = sample();
        ContraptionNbtRotator.rotateContraptionNbt(c, 0);
        assertEquals(new BlockPos(1, 0, 0), blockPos(c));
        assertEquals("north", facing(c));
    }

    @Test
    void cwStepsToRotationMapsAndWraps() {
        assertEquals(Rotation.NONE, ContraptionNbtRotator.cwStepsToRotation(0));
        assertEquals(Rotation.CLOCKWISE_90, ContraptionNbtRotator.cwStepsToRotation(1));
        assertEquals(Rotation.CLOCKWISE_180, ContraptionNbtRotator.cwStepsToRotation(2));
        assertEquals(Rotation.COUNTERCLOCKWISE_90, ContraptionNbtRotator.cwStepsToRotation(3));
        assertEquals(Rotation.NONE, ContraptionNbtRotator.cwStepsToRotation(4));
    }

    // --- palette property rotation (facing/offset/axis/rotation/connections/octants) ---

    private static CompoundTag contraptionWithProps(CompoundTag props) {
        CompoundTag root = new CompoundTag();
        CompoundTag blocks = new CompoundTag();
        ListTag palette = new ListTag();
        CompoundTag entry = new CompoundTag();
        entry.put("Properties", props);
        palette.add(entry);
        blocks.put(ContraptionNbtKeys.PALETTE, palette);
        root.put(ContraptionNbtKeys.BLOCKS, blocks);
        return root;
    }

    private static CompoundTag propsOf(CompoundTag root) {
        return root.getCompound(ContraptionNbtKeys.BLOCKS)
                .getList(ContraptionNbtKeys.PALETTE, Tag.TAG_COMPOUND)
                .getCompound(0).getCompound("Properties");
    }

    @Test
    void oneStepRotatesAllPaletteDirectionalProperties() {
        CompoundTag props = new CompoundTag();
        props.putString("facing", "north");
        props.putString("offset", "north");   // copycat_half_panel-style offset
        props.putString("axis", "x");
        props.putString("rotation", "0");      // sign/banner 0-15 compass
        // connection booleans (panes/walls/fences): a full cardinal set cycles cleanly
        props.putString("north", "true");
        props.putString("east", "false");
        props.putString("south", "false");
        props.putString("west", "false");
        props.putString("top_northeast", "true"); // copycat_byte octant

        CompoundTag c = contraptionWithProps(props);
        ContraptionNbtRotator.rotateContraptionNbt(c, 1);
        CompoundTag p = propsOf(c);

        assertEquals("east", p.getString("facing"), "facing N -> E");
        assertEquals("east", p.getString("offset"), "offset N -> E");
        assertEquals("z", p.getString("axis"), "axis x -> z on a 90deg step");
        assertEquals("4", p.getString("rotation"), "rotation +4 of 16 per CW step");
        // The cardinal connection set cycles north->east: east now holds north's old 'true'.
        assertEquals("true", p.getString("east"), "connection N(true) -> E");
        assertEquals("false", p.getString("north"), "connection W(false) -> N");
        // The octant cycles northeast->southeast (old keys are not cleared, the new one is set).
        assertEquals("true", p.getString("top_southeast"), "octant NE -> SE");
    }

    @Test
    void axisOnlySwapsOnOddStepsAndFacingRotatesTwiceFor180() {
        CompoundTag props = new CompoundTag();
        props.putString("facing", "north");
        props.putString("axis", "x");
        CompoundTag c = contraptionWithProps(props);

        ContraptionNbtRotator.rotateContraptionNbt(c, 2);
        CompoundTag p = propsOf(c);
        assertEquals("south", p.getString("facing"), "facing N -> S over 180deg");
        assertEquals("x", p.getString("axis"), "axis is unchanged on an even (180deg) step");
    }

    // --- remapMultiStateFaceKeys (multi-state copycat material_data face keys) ---

    @Test
    void remapMultiStateFaceKeysCyclesCardinalAndOctantKeysAndLeavesNonDirectional() {
        CompoundTag md = new CompoundTag();
        CompoundTag north = new CompoundTag(); north.putString("m", "N"); md.put("north", north);
        CompoundTag east = new CompoundTag(); east.putString("m", "E"); md.put("east", east);
        CompoundTag tne = new CompoundTag(); tne.putString("m", "TNE"); md.put("top_northeast", tne);
        CompoundTag up = new CompoundTag(); up.putString("m", "UP"); md.put("up", up);

        ContraptionNbtRotator.remapMultiStateFaceKeys(md, 1);

        assertEquals("N", md.getCompound("east").getString("m"), "north face -> east");
        assertEquals("E", md.getCompound("south").getString("m"), "east face -> south");
        assertEquals("TNE", md.getCompound("top_southeast").getString("m"), "top_northeast -> top_southeast");
        assertEquals("UP", md.getCompound("up").getString("m"), "non-directional 'up' key is untouched");
        // The source faces moved away and were not overwritten by another face, so they must be gone -
        // a partial map (no south/west) must not leave the old north/top_northeast keys behind.
        assertFalse(md.contains("north"), "stale 'north' key removed after rotation");
        assertFalse(md.contains("top_northeast"), "stale 'top_northeast' key removed after rotation");
    }

    @Test
    void remapMultiStateFaceKeysOnSingleFaceLeavesNoStaleKey() {
        // A multi-state copycat with only one face specified is the case that exposed the bug:
        // putting the rotated key without removing the source left both keys populated.
        CompoundTag md = new CompoundTag();
        CompoundTag north = new CompoundTag(); north.putString("m", "N"); md.put("north", north);

        ContraptionNbtRotator.remapMultiStateFaceKeys(md, 1);

        assertEquals("N", md.getCompound("east").getString("m"), "the single north face rotates to east");
        assertFalse(md.contains("north"), "no stale 'north' key remains");
        assertEquals(1, md.getAllKeys().size(), "exactly one face key after rotating a single face");
    }
}
