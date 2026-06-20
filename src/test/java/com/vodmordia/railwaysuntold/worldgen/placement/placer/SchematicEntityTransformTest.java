package com.vodmordia.railwaysuntold.worldgen.placement.placer;

import com.vodmordia.railwaysuntold.util.spatial.RotationHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Gates the fractional entity-position transform added so that saved entities (minecarts, etc.) land
 * exactly where the rotated blocks do. The invariant: an entity sitting at the centre of local cell
 * (x,y,z) must transform to the centre of the same world cell that {@link RotationHelper#transformPosition}
 * maps that block to - under every rotation. Block transform is integer; entity transform is continuous;
 * a sign/pivot error in either would drift the minecart off its rail, which is precisely the regression
 * this pins.
 */
class SchematicEntityTransformTest {

    private static final Rotation[] ROTATIONS = {
            Rotation.NONE, Rotation.CLOCKWISE_90, Rotation.CLOCKWISE_180, Rotation.COUNTERCLOCKWISE_90,
    };

    @Test
    void cellCentreEntityLandsOnSameCellAsBlock() {
        // Deliberately non-cubic so width/length swaps under 90° rotations are exercised.
        Vec3i size = new Vec3i(9, 3, 5);
        BlockPos origin = new BlockPos(100, 64, -40);

        for (Rotation rotation : ROTATIONS) {
            for (int y = 0; y < size.getY(); y++) {
                for (int z = 0; z < size.getZ(); z++) {
                    for (int x = 0; x < size.getX(); x++) {
                        BlockPos blockWorld = RotationHelper.transformPosition(
                                new BlockPos(x, y, z), size, origin, rotation);
                        Vec3 entityWorld = SchematicPlacer.transformEntityPos(
                                new Vec3(x + 0.5, y, z + 0.5), size, origin, rotation);

                        String at = "rot=" + rotation + " local=(" + x + "," + y + "," + z + ")";
                        assertEquals(blockWorld.getX() + 0.5, entityWorld.x, 1.0e-9, "x " + at);
                        assertEquals(blockWorld.getY(), entityWorld.y, 1.0e-9, "y " + at);
                        assertEquals(blockWorld.getZ() + 0.5, entityWorld.z, 1.0e-9, "z " + at);
                    }
                }
            }
        }
    }
}
