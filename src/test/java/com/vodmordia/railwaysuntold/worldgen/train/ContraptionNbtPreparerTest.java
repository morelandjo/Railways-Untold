package com.vodmordia.railwaysuntold.worldgen.train;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Characterizes the pure NBT-reading/normalizing helpers in {@link ContraptionNbtPreparer}:
 * reading the stored assembly direction (with NORTH as the default/garbage fallback) and converting
 * int-array seat positions into Create's {X,Y,Z} compound encoding. 1.21.1-only: the class links Create's
 * AbstractBogeyBlock for its palette helpers; the bodies under test are identical on 1.20.1 (parity by inspection).
 */
class ContraptionNbtPreparerTest {

    @Test
    void assemblyDirectionIsReadFromTheTag() {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("AssemblyDirection", "EAST");
        assertEquals(Direction.EAST, ContraptionNbtPreparer.extractAssemblyDirectionFromNbt(nbt));
    }

    @Test
    void missingAssemblyDirectionDefaultsToNorth() {
        assertEquals(Direction.NORTH, ContraptionNbtPreparer.extractAssemblyDirectionFromNbt(new CompoundTag()));
    }

    @Test
    void unparseableAssemblyDirectionFallsBackToNorth() {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("AssemblyDirection", "sideways");
        assertEquals(Direction.NORTH, ContraptionNbtPreparer.extractAssemblyDirectionFromNbt(nbt));
    }

    @Test
    void intArraySeatPositionsAreConvertedToXyzCompounds() {
        CompoundTag nbt = new CompoundTag();
        ListTag seats = new ListTag();
        CompoundTag seat = new CompoundTag();
        seat.putIntArray("Pos", new int[]{3, 4, 5});
        seats.add(seat);
        nbt.put("Seats", seats);

        ContraptionNbtPreparer.convertIntArrayPosToCompoundTag(nbt);

        CompoundTag converted = nbt.getList("Seats", Tag.TAG_COMPOUND).getCompound(0);
        assertEquals(3, converted.getInt("X"));
        assertEquals(4, converted.getInt("Y"));
        assertEquals(5, converted.getInt("Z"));
        assertFalse(converted.contains("Pos"), "the int-array Pos should be replaced by direct X/Y/Z");
    }
}
