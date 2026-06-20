package com.vodmordia.railwaysuntold.worldgen.placement.constraint;

import com.vodmordia.railwaysuntold.util.spatial.DiagonalDirection;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import com.vodmordia.railwaysuntold.worldgen.placement.constraint.AvoidanceStrategy.AvoidanceContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Characterizes the pure pieces of the avoidance constraint layer: the trajectory ray endpoint
 * (cardinal lookahead 64, diagonal lookahead 80, projected along the direction's step vector) and
 * {@link AvoidanceConstraintService#apply}'s DEFER passthrough gate (a defer decision is returned
 * untouched before any world or config is consulted).
 */
class AvoidanceConstraintTest {

    // Build a context via the canonical record constructor to bypass the world-bound create(); only
    // currentPos / expandDir / diagonalDir feed the trajectory math, so the rest can be null/zero.
    private static AvoidanceContext ctx(BlockPos pos, Direction expandDir, DiagonalDirection diagonalDir) {
        return new AvoidanceContext(null, pos, expandDir, diagonalDir, null, null, null, 0, 0, false);
    }

    @Test
    void cardinalTrajectoryProjects64BlocksAlongTheExpandDirection() {
        BlockPos pos = new BlockPos(100, 70, 200);

        AvoidanceContext east = ctx(pos, Direction.EAST, null);
        assertEquals(164, east.trajectoryEndX(), "EAST advances +64 in X");
        assertEquals(200, east.trajectoryEndZ(), "EAST does not move in Z");

        AvoidanceContext north = ctx(pos, Direction.NORTH, null);
        assertEquals(100, north.trajectoryEndX(), "NORTH does not move in X");
        assertEquals(136, north.trajectoryEndZ(), "NORTH advances -64 in Z");
    }

    @Test
    void diagonalTrajectoryUses80AndTakesPrecedenceOverTheCardinalDir() {
        BlockPos pos = new BlockPos(100, 70, 200);
        // NORTHEAST = (+1, -1); expandDir is still EAST but must be ignored once a diagonal is set.
        AvoidanceContext ne = ctx(pos, Direction.EAST, DiagonalDirection.NORTHEAST);
        assertEquals(180, ne.trajectoryEndX(), "diagonal advances +80 in X, not the cardinal +64");
        assertEquals(120, ne.trajectoryEndZ(), "diagonal advances -80 in Z");
    }

    @Test
    void applyReturnsADeferDecisionUntouchedWithoutConsultingTheWorld() {
        PlacementDecision defer = PlacementDecision.defer();
        // null head/pos/dir/scan/level: the DEFER gate must short-circuit before any of them is read.
        PlacementDecision result = AvoidanceConstraintService.apply(null, defer, null, null, null, null);
        assertSame(defer, result, "a DEFER decision passes through avoidance unchanged");
    }
}
