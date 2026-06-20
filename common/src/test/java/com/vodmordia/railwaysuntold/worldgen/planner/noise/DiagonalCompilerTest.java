package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.planner.PathSegment;
import com.vodmordia.railwaysuntold.worldgen.planner.PlannedPath;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.CoarseWaypoint;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.WaypointType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Pins the planner half of the diagonal path: PrecisionRouteCompiler turns a sustained ~45° coarse run
 * (both axis deltas >= 32 and within 15% of equal, per DirectionAnalyzer.isDiagonalRun) into a
 * DIAGONAL_ENTRY -> DIAGONAL_STRAIGHT... -> DIAGONAL_EXIT chain, while a shallow off-axis run stays
 * cardinal. The placement executors that lay these segments are covered end-to-end by the
 * RailwaysUntoldGameTests diagonal drive; this is the cheap, deterministic guard on their production.
 */
class DiagonalCompilerTest {

    private static BlockPos bp(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    private static Set<PathSegment.Type> typesOf(PlannedPath path) {
        Set<PathSegment.Type> types = EnumSet.noneOf(PathSegment.Type.class);
        for (PathSegment s : path.segments) {
            types.add(s.type);
        }
        return types;
    }

    @Test
    void sustainedFortyFiveDegreeRunCompilesToADiagonalChain() {
        // 96 blocks NE (dx == dz == 96): a clean diagonal run well past the 32-block threshold, followed by
        // a short cardinal EAST run. The trailing cardinal run makes the diagonal NOT the last run, so the
        // compiler emits the diagonal chain unconditionally (the last-run path additionally requires the
        // entry+exit chain to fit, which a tail run can't always absorb).
        List<CoarseWaypoint> wps = new ArrayList<>();
        for (int i = 0; i <= 12; i++) {
            wps.add(new CoarseWaypoint(bp(i * 8, 72, i * 8), 72, WaypointType.TERRAIN_FOLLOW));
        }
        for (int j = 1; j <= 7; j++) {
            wps.add(new CoarseWaypoint(bp(96 + j * 8, 72, 96), 72, WaypointType.TERRAIN_FOLLOW));
        }
        BlockPos start = wps.get(0).position();
        BlockPos target = wps.get(wps.size() - 1).position();
        PlannedPath path = PrecisionRouteCompiler.compile(wps, start, target, Direction.EAST, false);

        assertTrue(path.valid, "diagonal run should compile to a valid path");
        Set<PathSegment.Type> types = typesOf(path);
        assertTrue(types.contains(PathSegment.Type.DIAGONAL_ENTRY), "expected a DIAGONAL_ENTRY; got " + types);
        assertTrue(types.contains(PathSegment.Type.DIAGONAL_STRAIGHT), "expected a DIAGONAL_STRAIGHT; got " + types);
        assertTrue(types.contains(PathSegment.Type.DIAGONAL_EXIT), "expected a DIAGONAL_EXIT; got " + types);
    }

    @Test
    void shallowOffAxisRunStaysCardinal() {
        // dx = 96, dz = 24 (< the 32-block diagonal threshold): a gentle drift, not a 45° run, so no
        // diagonal chain - it routes cardinal + correction instead.
        List<CoarseWaypoint> wps = new ArrayList<>();
        for (int i = 0; i <= 12; i++) {
            wps.add(new CoarseWaypoint(bp(i * 8, 72, i * 2), 72, WaypointType.TERRAIN_FOLLOW));
        }
        BlockPos start = wps.get(0).position();
        BlockPos target = wps.get(wps.size() - 1).position();
        PlannedPath path = PrecisionRouteCompiler.compile(wps, start, target, Direction.EAST, false);

        Set<PathSegment.Type> types = typesOf(path);
        assertFalse(types.contains(PathSegment.Type.DIAGONAL_STRAIGHT),
                "a shallow off-axis run must not emit diagonal segments; got " + types);
    }
}
