package com.vodmordia.railwaysuntold.worldgen.branching;

import com.vodmordia.railwaysuntold.worldgen.terrain.BranchTerrainAnalyzer.BranchTerrainScore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterizes the pure decision core of {@link BranchDirectionSelector#selectDirection}. The method's
 * world-dependent inputs (parent head, head manager, level, village tracker) are all {@code @Nullable};
 * with them null it reduces to: a side is "viable" when its terrain score is &gt; 0, the higher-scoring
 * viable side wins, both-viable resolves by a deterministic position+seed weighted pick, and both-poor
 * returns empty unless {@code allowPoorTerrain} forces the higher (or a deterministic pick on a tie).
 */
class BranchDirectionSelectorTest {

    private static final long SEED = 42L;

    /** left = WEST, right = EAST; world-dependent params null so only the scores drive the choice. */
    private static Optional<Direction> select(int left, int right, boolean allowPoor) {
        return BranchDirectionSelector.selectDirection(
                BlockPos.ZERO, SEED, Direction.WEST, Direction.EAST,
                new BranchTerrainScore(left), new BranchTerrainScore(right), allowPoor,
                null, null, null, null);
    }

    @Test
    void onlyTheViableSideIsChosen() {
        assertEquals(Direction.WEST, select(5, 0, false).orElseThrow());
        assertEquals(Direction.EAST, select(0, 5, false).orElseThrow());
    }

    @Test
    void scoreOfZeroIsNotViable() {
        // Viability is score > 0, so a 0/0 pair is "both poor" - empty unless forced.
        assertFalse(select(0, 0, false).isPresent());
    }

    @Test
    void bothPoorAndNotForcedReturnsEmpty() {
        assertTrue(select(-2, -5, false).isEmpty());
    }

    @Test
    void bothPoorButForcedPicksTheHigher() {
        assertEquals(Direction.WEST, select(-1, -5, true).orElseThrow());
        assertEquals(Direction.EAST, select(-9, -3, true).orElseThrow());
    }

    @Test
    void bothViableResolvesDeterministicallyForAGivenPositionAndSeed() {
        Optional<Direction> a = select(5, 5, false);
        Optional<Direction> b = select(5, 5, false);
        assertTrue(a.isPresent());
        assertEquals(a.orElseThrow(), b.orElseThrow());
    }
}
