package com.vodmordia.railwaysuntold.util.spatial;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Characterizes the small pure spatial helpers: {@link PositionRandom} (deterministic position-seeded RNG)
 * and {@link DirectionOffsets} (symmetric left/right unit offsets from a normal). World/config-free.
 */
class SpatialHelpersTest {

    @Test
    void positionRandomIsDeterministicForTheSameInputs() {
        BlockPos pos = new BlockPos(12, 64, -7);
        int a = PositionRandom.createWithSalt(pos, 123456789L, 1L).nextInt();
        int b = PositionRandom.createWithSalt(pos, 123456789L, 1L).nextInt();
        assertEquals(a, b, "same pos + seed + salt -> identical sequence");
    }

    @Test
    void positionRandomDivergesWhenTheSaltOrPositionChanges() {
        BlockPos pos = new BlockPos(12, 64, -7);
        int base = PositionRandom.createWithSalt(pos, 123456789L, 1L).nextInt();
        assertNotEquals(base, PositionRandom.createWithSalt(pos, 123456789L, 2L).nextInt(), "different salt");
        assertNotEquals(base, PositionRandom.createWithSalt(new BlockPos(13, 64, -7), 123456789L, 1L).nextInt(),
                "different position");
    }

    @Test
    void directionOffsetsAreANormalizedNormalAndItsNegation() {
        DirectionOffsets offsets = DirectionOffsets.fromNormal(new Vec3(0, 0, 5)); // unnormalized +Z
        // Component-wise with a delta: scale(-1) yields -0.0 on the zero axes, which Vec3.equals (Double.compare)
        // would treat as unequal to 0.0 - the geometry is what matters, not the signed zero.
        assertEquals(0.0, offsets.left().x, 1e-9);
        assertEquals(1.0, offsets.left().z, 1e-9, "left is the normalized normal (+Z)");
        assertEquals(0.0, offsets.right().x, 1e-9);
        assertEquals(-1.0, offsets.right().z, 1e-9, "right is its negation (−Z)");
        assertEquals(1.0, offsets.left().length(), 1e-9, "left is a unit vector");
    }
}
