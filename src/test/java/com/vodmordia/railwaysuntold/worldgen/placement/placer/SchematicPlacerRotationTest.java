package com.vodmordia.railwaysuntold.worldgen.placement.placer;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gates the pure rotation-selection half of station-entry alignment (PL2 part a): given the schematic's
 * built-in track direction and the head's target direction,
 * {@link SchematicPlacer#calculateRotationForAlignment} picks the {@link Rotation} that lines the
 * station's track up with the head. The full entry invariant (placed {@code trackEntryPoint == head}) is
 * covered end-to-end by the forced-village station gametest; the entry-transform geometry around it
 * resists a cheap unit fixture (private {@code SchematicValidationResult} ctor + a real {@code BlockState[]}
 * schematic), so this pins the one pure, public piece - a rotation-math regression fails fast here.
 *
 * A straight (1-D, 180°-symmetric) station track only ever needs NONE (same axis) or a single 90° turn
 * (perpendicular), so this returns exactly NONE or CLOCKWISE_90.
 */
class SchematicPlacerRotationTest {

    private static final Direction[] CARDINALS = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST,
    };

    @Test
    void sameAxisNeedsNoRotation() {
        for (Direction d : CARDINALS) {
            assertEquals(Rotation.NONE, SchematicPlacer.calculateRotationForAlignment(d, d),
                    "same direction " + d);
            assertEquals(Rotation.NONE, SchematicPlacer.calculateRotationForAlignment(d, d.getOpposite()),
                    "opposite direction " + d);
        }
    }

    @Test
    void perpendicularNeedsClockwise90() {
        assertEquals(Rotation.CLOCKWISE_90, SchematicPlacer.calculateRotationForAlignment(Direction.NORTH, Direction.EAST));
        assertEquals(Rotation.CLOCKWISE_90, SchematicPlacer.calculateRotationForAlignment(Direction.NORTH, Direction.WEST));
        assertEquals(Rotation.CLOCKWISE_90, SchematicPlacer.calculateRotationForAlignment(Direction.EAST, Direction.NORTH));
        assertEquals(Rotation.CLOCKWISE_90, SchematicPlacer.calculateRotationForAlignment(Direction.EAST, Direction.SOUTH));
    }

    @Test
    void onlyNoneAndClockwise90AreEverReturned() {
        // Exhaustive over all cardinal (schematic, target) pairs: never CW180 or CCW90.
        for (Direction s : CARDINALS) {
            for (Direction t : CARDINALS) {
                Rotation r = SchematicPlacer.calculateRotationForAlignment(s, t);
                assertTrue(r == Rotation.NONE || r == Rotation.CLOCKWISE_90, s + " -> " + t + " gave " + r);
            }
        }
    }
}
