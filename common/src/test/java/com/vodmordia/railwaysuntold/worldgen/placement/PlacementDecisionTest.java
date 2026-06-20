package com.vodmordia.railwaysuntold.worldgen.placement;

import com.vodmordia.railwaysuntold.util.spatial.DiagonalDirection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterizes {@link PlacementDecision}, the central value object every placement rule produces and the
 * pipeline/executors read. Pins the factory -> type + derived-field contracts (and their argument validation)
 * and the copy-on-write mutators, which the pipeline's log-context attachment relies on to preserve every
 * field. Pure (no world, no config).
 */
class PlacementDecisionTest {

    private static final BlockPos START = new BlockPos(0, 64, 0);

    @Test
    void straightCarriesTheDistanceAndRejectsNonPositiveOrNullStart() {
        PlacementDecision d = PlacementDecision.straight(START, 12);
        assertEquals(PlacementDecision.Type.STRAIGHT, d.getType());
        assertEquals(START, d.getStart());
        assertEquals(12, d.getDistance());

        assertThrows(IllegalArgumentException.class, () -> PlacementDecision.straight(START, 0));
        assertThrows(NullPointerException.class, () -> PlacementDecision.straight(null, 12));
    }

    @Test
    void bezierWithExemptionDerivesManhattanDistanceAndElevation() {
        PlacementDecision d = PlacementDecision.bezierWithExemption(START, new BlockPos(10, 70, -20), Direction.EAST);
        assertEquals(PlacementDecision.Type.BEZIER, d.getType());
        assertEquals(30, d.getDistance(), "horizontal Manhattan distance |10| + |-20|");
        assertEquals(6, d.getElevationChange(), "70 − 64");
        assertEquals(Direction.EAST, d.getDirection());
    }

    @Test
    void branchProjectsTheEndForwardAndKeepsBothDirections() {
        PlacementDecision d = PlacementDecision.branch(START, 16, Direction.EAST, Direction.SOUTH, null);
        assertEquals(PlacementDecision.Type.BRANCH, d.getType());
        assertEquals(START.relative(Direction.EAST, 16), d.getEnd(), "end is distance forward along current dir");
        assertEquals(16, d.getDistance());
        assertEquals(Direction.EAST, d.getDirection(), "the head's current direction");
        assertEquals(Direction.SOUTH, d.getBranchDirection(), "the direction the branch diverges");

        assertThrows(IllegalArgumentException.class,
                () -> PlacementDecision.branch(START, 0, Direction.EAST, Direction.SOUTH, null));
    }

    @Test
    void diagonalStraightOffsetsTheEndAlongTheDiagonalStepVector() {
        // NORTHEAST = (+1, −1); distance 10, elevation +5 -> end (10, 69, −10).
        PlacementDecision d = PlacementDecision.diagonalStraight(START, DiagonalDirection.NORTHEAST, 10, 5, null);
        assertEquals(PlacementDecision.Type.DIAGONAL_STRAIGHT, d.getType());
        assertEquals(new BlockPos(10, 69, -10), d.getEnd());
        assertEquals(10, d.getDistance());
        assertEquals(5, d.getElevationChange());
        assertSame(DiagonalDirection.NORTHEAST, d.getDiagonalDirection());
    }

    @Test
    void deferAndInvalidAndChunkDeferralCarryTheirType() {
        assertEquals(PlacementDecision.Type.DEFER, PlacementDecision.defer().getType());
        assertEquals(PlacementDecision.Type.INVALID, PlacementDecision.invalid().getType());

        Set<ChunkPos> chunks = Set.of(new ChunkPos(1, 2), new ChunkPos(3, 4));
        PlacementDecision d = PlacementDecision.deferForChunks(chunks);
        assertEquals(PlacementDecision.Type.DEFER, d.getType());
        assertEquals(chunks, d.getDeferChunks());
        assertThrows(NullPointerException.class, () -> PlacementDecision.deferForChunks(null));
    }

    @Test
    void curveIsNullSafeWhenGivenNoParameters() {
        PlacementDecision d = PlacementDecision.curve(null, null);
        assertEquals(PlacementDecision.Type.CURVE, d.getType());
        assertNull(d.getStart(), "null params -> null start, not an NPE");
        assertNull(d.getEnd());
    }

    @Test
    void scurve45RejectsANonPositiveRadiusBeforeAnyGeometry() {
        assertThrows(IllegalArgumentException.class,
                () -> PlacementDecision.scurve45(START, Direction.EAST, 0, 4, true, 0, null));
    }

    @Test
    void withLogContextReturnsACopyThatPreservesEveryFieldAndLeavesTheOriginal() {
        PlacementDecision original = PlacementDecision.branch(START, 16, Direction.EAST, Direction.SOUTH, null);
        assertNull(original.getLogContext(), "no log context to start");

        PlacementLogContext ctx = new PlacementLogContext(7, "TestRule");
        PlacementDecision copy = original.withLogContext(ctx);

        assertNotSame(original, copy, "copy-on-write: a new instance");
        assertSame(ctx, copy.getLogContext());
        assertNull(original.getLogContext(), "the original is untouched");
        // The carried fields survive the copy - the pipeline depends on this.
        assertEquals(PlacementDecision.Type.BRANCH, copy.getType());
        assertEquals(original.getEnd(), copy.getEnd());
        assertEquals(Direction.SOUTH, copy.getBranchDirection());
        assertEquals(16, copy.getDistance());
    }

    @Test
    void withBranchTargetVillageSetsTheTargetAndPreservesTheRest() {
        PlacementDecision original = PlacementDecision.branch(START, 16, Direction.EAST, Direction.SOUTH, null);
        UUID villageId = new UUID(0L, 9L);
        BlockPos center = new BlockPos(500, 64, 500);

        PlacementDecision copy = original.withBranchTargetVillage(villageId, center);
        assertNotSame(original, copy);
        assertEquals(villageId, copy.getBranchTargetVillageId());
        assertEquals(center, copy.getBranchTargetVillageCenter());
        assertEquals(PlacementDecision.Type.BRANCH, copy.getType());
        assertEquals(Direction.SOUTH, copy.getBranchDirection());
        assertNull(original.getBranchTargetVillageId(), "the original has no target");
    }
}
