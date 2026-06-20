package com.vodmordia.railwaysuntold.worldgen.train;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterizes {@link ContraptionNbtMirror#mirrorContraptionAlongAxis} - the pure reflection of a Create
 * contraption across the assembly direction's axis: positions negate on that axis (Y untouched), and a
 * facing palette property on that axis flips to its opposite. The load-bearing invariant is that
 * mirroring twice along the same axis is the identity.
 *
 * Since the mirror now shares {@link ContraptionNbtPositions} with the shifter/rotator, it handles
 * every {@code Pos} encoding (int-array, compound, long) and the sound queue - the earlier gap where the
 * mirror left int-array/compound positions and sound sources untouched is fixed (see the last two tests).
 */
class ContraptionNbtMirrorTest {

    /** A contraption with a block (long Pos at 3,0,5), an actor (long Pos at 2,7,4), and a facing=east palette. */
    private static CompoundTag sample() {
        CompoundTag root = new CompoundTag();

        CompoundTag blocks = new CompoundTag();
        ListTag blockList = new ListTag();
        CompoundTag block = new CompoundTag();
        block.putLong(ContraptionNbtKeys.POS, new BlockPos(3, 0, 5).asLong());
        blockList.add(block);
        blocks.put(ContraptionNbtKeys.BLOCK_LIST, blockList);

        ListTag palette = new ListTag();
        CompoundTag pEntry = new CompoundTag();
        CompoundTag props = new CompoundTag();
        props.putString("facing", "east");
        pEntry.put("Properties", props);
        palette.add(pEntry);
        blocks.put(ContraptionNbtKeys.PALETTE, palette);
        root.put(ContraptionNbtKeys.BLOCKS, blocks);

        ListTag actors = new ListTag();
        CompoundTag actor = new CompoundTag();
        actor.putLong("Pos", new BlockPos(2, 7, 4).asLong());
        actors.add(actor);
        root.put(ContraptionNbtKeys.ACTORS, actors);

        return root;
    }

    private static BlockPos blockPos(CompoundTag root) {
        return BlockPos.of(root.getCompound(ContraptionNbtKeys.BLOCKS)
                .getList(ContraptionNbtKeys.BLOCK_LIST, Tag.TAG_COMPOUND)
                .getCompound(0).getLong(ContraptionNbtKeys.POS));
    }

    private static BlockPos actorPos(CompoundTag root) {
        return BlockPos.of(root.getList(ContraptionNbtKeys.ACTORS, Tag.TAG_COMPOUND).getCompound(0).getLong("Pos"));
    }

    private static String facing(CompoundTag root) {
        return root.getCompound(ContraptionNbtKeys.BLOCKS)
                .getList(ContraptionNbtKeys.PALETTE, Tag.TAG_COMPOUND)
                .getCompound(0).getCompound("Properties").getString("facing");
    }

    @Test
    void mirrorAlongXNegatesXAndFlipsOnAxisFacing() {
        CompoundTag c = sample();
        ContraptionNbtMirror.mirrorContraptionAlongAxis(c, Direction.EAST); // assembly dir EAST -> axis X
        assertEquals(new BlockPos(-3, 0, 5), blockPos(c));
        assertEquals(new BlockPos(-2, 7, 4), actorPos(c));
        assertEquals("west", facing(c)); // east is on the X axis -> flips to west
    }

    @Test
    void mirrorAlongZNegatesZAndLeavesOffAxisFacing() {
        CompoundTag c = sample();
        ContraptionNbtMirror.mirrorContraptionAlongAxis(c, Direction.NORTH); // assembly dir NORTH -> axis Z
        assertEquals(new BlockPos(3, 0, -5), blockPos(c));
        assertEquals(new BlockPos(2, 7, -4), actorPos(c));
        assertEquals("east", facing(c)); // east is on the X axis, untouched by a Z mirror
    }

    @Test
    void mirroringTwiceAlongTheSameAxisIsTheIdentity() {
        CompoundTag c = sample();
        ContraptionNbtMirror.mirrorContraptionAlongAxis(c, Direction.EAST);
        ContraptionNbtMirror.mirrorContraptionAlongAxis(c, Direction.EAST);
        assertEquals(new BlockPos(3, 0, 5), blockPos(c));
        assertEquals(new BlockPos(2, 7, 4), actorPos(c));
        assertEquals("east", facing(c));
    }

    @Test
    void intArrayActorPosIsMirrored() {
        // Previously a gap (the mirror handled only long Pos); now mirrored like the shifter/rotator.
        CompoundTag root = new CompoundTag();
        ListTag actors = new ListTag();
        CompoundTag actor = new CompoundTag();
        actor.putIntArray("Pos", new int[]{2, 7, 4});
        actors.add(actor);
        root.put(ContraptionNbtKeys.ACTORS, actors);

        ContraptionNbtMirror.mirrorContraptionAlongAxis(root, Direction.EAST); // axis X

        int[] pos = root.getList(ContraptionNbtKeys.ACTORS, Tag.TAG_COMPOUND).getCompound(0).getIntArray("Pos");
        assertArrayEquals(new int[]{-2, 7, 4}, pos, "int-array actor Pos now mirrors on the X axis");
    }

    @Test
    void soundSourcesAndIntArraySeatsAreNowMirrored() {
        // Two more encodings the standalone mirror missed and the shared traversal now covers.
        CompoundTag root = new CompoundTag();

        ListTag seats = new ListTag();
        seats.add(new net.minecraft.nbt.IntArrayTag(new int[]{5, 0, 9}));
        root.put(ContraptionNbtKeys.SEATS, seats);

        CompoundTag soundQueue = new CompoundTag();
        ListTag sources = new ListTag();
        CompoundTag source = new CompoundTag();
        source.putLong(ContraptionNbtKeys.POS, new BlockPos(6, 0, 0).asLong());
        sources.add(source);
        soundQueue.put("Sources", sources);
        root.put("SoundQueue", soundQueue);

        ContraptionNbtMirror.mirrorContraptionAlongAxis(root, Direction.EAST); // axis X

        assertArrayEquals(new int[]{-5, 0, 9},
                root.getList(ContraptionNbtKeys.SEATS, Tag.TAG_INT_ARRAY).getIntArray(0));
        assertEquals(new BlockPos(-6, 0, 0), BlockPos.of(
                root.getCompound("SoundQueue").getList("Sources", Tag.TAG_COMPOUND).getCompound(0).getLong(ContraptionNbtKeys.POS)));
    }

    // --- palette directional-property mirroring (offset / connections / octants / handedness) ---

    private static CompoundTag withPaletteProps(CompoundTag props) {
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

    private static CompoundTag paletteProps(CompoundTag root) {
        return root.getCompound(ContraptionNbtKeys.BLOCKS)
                .getList(ContraptionNbtKeys.PALETTE, Tag.TAG_COMPOUND)
                .getCompound(0).getCompound("Properties");
    }

    @Test
    void mirrorAlongXFlipsOffsetConnectionsOctantsAndHandedness() {
        CompoundTag props = new CompoundTag();
        props.putString("offset", "east");        // on the X axis -> west
        props.putString("east", "true");
        props.putString("west", "false");          // east <-> west swap
        props.putString("top_northeast", "TNE");
        props.putString("top_northwest", "TNW");   // NE <-> NW on X
        props.putString("bottom_left", "L");
        props.putString("bottom_right", "R");      // handedness always reverses
        props.putString("top_left", "TL");
        props.putString("top_right", "TR");

        CompoundTag c = withPaletteProps(props);
        ContraptionNbtMirror.mirrorContraptionAlongAxis(c, Direction.EAST); // axis X
        CompoundTag p = paletteProps(c);

        assertEquals("west", p.getString("offset"), "offset east -> west on X mirror");
        assertEquals("false", p.getString("east"), "east <-> west connection swap");
        assertEquals("true", p.getString("west"));
        assertEquals("TNW", p.getString("top_northeast"), "octant NE <-> NW on X");
        assertEquals("TNE", p.getString("top_northwest"));
        assertEquals("R", p.getString("bottom_left"), "handedness reverses: L <-> R");
        assertEquals("L", p.getString("bottom_right"));
        assertEquals("TR", p.getString("top_left"));
    }

    @Test
    void mirrorFlipsBogeyIsForwards() {
        CompoundTag root = new CompoundTag();
        CompoundTag blocks = new CompoundTag();
        ListTag blockList = new ListTag();
        CompoundTag block = new CompoundTag();
        block.putLong(ContraptionNbtKeys.POS, BlockPos.ZERO.asLong());
        CompoundTag data = new CompoundTag();
        CompoundTag bogey = new CompoundTag();
        bogey.putBoolean("IsForwards", true);
        data.put(ContraptionNbtKeys.BOGEY_DATA, bogey);
        block.put(ContraptionNbtKeys.DATA, data);
        blockList.add(block);
        blocks.put(ContraptionNbtKeys.BLOCK_LIST, blockList);
        root.put(ContraptionNbtKeys.BLOCKS, blocks);

        ContraptionNbtMirror.mirrorContraptionAlongAxis(root, Direction.EAST);

        boolean forwards = root.getCompound(ContraptionNbtKeys.BLOCKS)
                .getList(ContraptionNbtKeys.BLOCK_LIST, Tag.TAG_COMPOUND).getCompound(0)
                .getCompound(ContraptionNbtKeys.DATA).getCompound(ContraptionNbtKeys.BOGEY_DATA)
                .getBoolean("IsForwards");
        assertFalse(forwards, "mirror reverses the bogey's facing -> IsForwards flips");
    }
}
