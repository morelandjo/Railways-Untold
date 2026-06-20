package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.planner.BezierSegment;
import com.vodmordia.railwaysuntold.worldgen.planner.PathSegment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Characterizes the slope clamp at the two route-closure sites in {@link ClosureHelper}.
 * Both reconcile the tail to routeEnd with a bezier whose endY is clamped to the slope
 * limit; the run basis fed to that clamp is what this test pins. A diff is a behavior
 * change - investigate before accepting.
 *
 * trimOvershoot's residual can be perpendicular-dominant (a diagonal chain that hits the
 * target Z but misses the X), so the run basis materially changes the clamped Y there;
 * closeResidualGap's forward-dominant path gates on the cardinal forward distance before
 * clamping, so its clamp basis does not change output.
 */
class ClosureClampGoldenTest {

    private static final boolean UPDATE = Boolean.getBoolean("golden.update");
    private static final String GOLDEN_DIR = System.getProperty("golden.source.dir");

    @BeforeAll
    static void setUp() {
        PlannerTestConfig.bootstrapDefaults();
    }

    @Test
    void overshootTrimPerpendicularResidual() throws IOException {
        // Tail overshoots routeEnd along EAST and lands 30 blocks off-axis in Z, with a
        // 16-block climb to make over the closing bezier. Manhattan run (50 fwd + 30 perp
        // = 80) reads the 16 climb as slope-legal; cardinal forward run (50) does not and
        // clamps endY short.
        List<PathSegment> segments = new ArrayList<>();
        segments.add(new BezierSegment(bp(0, 72, 0), Direction.EAST, bp(100, 72, 0), Direction.EAST));
        segments.add(new BezierSegment(bp(100, 72, 0), Direction.EAST, bp(200, 88, 30), Direction.EAST));
        ClosureHelper.trimOvershoot(segments, bp(150, 88, 30), Direction.EAST);
        verify("closure-trim-overshoot", segments);
    }

    @Test
    void residualGapForwardDominant() throws IOException {
        // Forward-dominant residual (12 fwd, 4 perp) with a 2-block climb the forward-based
        // gate admits. Demonstrates the clamp returns the unchanged target Y on both bases.
        List<PathSegment> segments = new ArrayList<>();
        segments.add(new BezierSegment(bp(0, 72, 0), Direction.EAST, bp(100, 72, 0), Direction.EAST));
        ClosureHelper.closeResidualGap(segments, bp(112, 74, 4));
        verify("closure-residual-gap", segments);
    }

    // --- helpers ---

    private static BlockPos bp(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    private void verify(String scenario, List<PathSegment> segments) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("== SEGMENTS (").append(segments.size()).append(") ==\n");
        for (int i = 0; i < segments.size(); i++) {
            PathSegment s = segments.get(i);
            sb.append(String.format("SEG[%d] %s start=%s startDir=%s end=%s endDir=%s\n",
                    i, s.getClass().getSimpleName(),
                    pos(s.getStart()), s.getStartDirection(),
                    pos(s.getEndPosition()), s.getEndDirection()));
        }
        String actual = sb.toString();

        if (GOLDEN_DIR == null) {
            throw new IllegalStateException("golden.source.dir system property not set");
        }
        Path golden = Path.of(GOLDEN_DIR, scenario + ".txt");
        if (UPDATE || Files.notExists(golden)) {
            Files.createDirectories(golden.getParent());
            Files.writeString(golden, actual);
        }
        String expected = Files.readString(golden);
        assertEquals(expected, actual,
                () -> "Golden mismatch for '" + scenario + "'. If intended, re-run with "
                        + "-Dgolden.update=true and review the diff before committing.");
    }

    private static String pos(BlockPos p) {
        return p == null ? "null" : "(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")";
    }
}
