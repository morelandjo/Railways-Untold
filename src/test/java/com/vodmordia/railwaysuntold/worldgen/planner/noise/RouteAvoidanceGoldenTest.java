package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.planner.noise.RouteObstacleAvoider.ExistingTrackCluster;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseStructureScanner.PredictedStructure;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Characterization (golden) test for RouteObstacleAvoider.buildPathWithAvoidance - the 2D
 * detour-corner planner that bends the route around structures and other heads' track before
 * terrain sampling. It is pure given synthetic structures/clusters, a stub terrain sampler and
 * level=null (the off-thread plan path already calls it with level=null), so the returned
 * corner path is exercised directly here. Multi-obstacle scenarios exercise the per-obstacle
 * detour decisions; snapshot the corner sequence so any behavior change is a reviewed diff.
 */
class RouteAvoidanceGoldenTest {

    private static final boolean UPDATE = Boolean.getBoolean("golden.update");
    private static final String GOLDEN_DIR = System.getProperty("golden.source.dir");

    private static final UUID OTHER_HEAD = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final BlockPos START = new BlockPos(0, 72, 0);
    private static final BlockPos TARGET = new BlockPos(384, 72, 0);
    private static final int FLAT_Y = 72;

    @BeforeAll
    static void setUp() {
        PlannerTestConfig.bootstrapDefaults();
    }

    @Test
    void avoidancePaths() throws IOException {
        StringBuilder sb = new StringBuilder();

        // Baseline: nothing to avoid -> straight start->target.
        sb.append(avoidCase("no obstacles", List.of(), List.of()));

        // Single structure straddling the corridor -> one detour.
        sb.append(avoidCase("single structure on axis (x=150)",
                List.of(structure(150, 0, 24)), List.of()));

        // Two separate structures on the corridor. The second detour's decision is the
        // thing OA1 changes (currently measured from the first detour's exit corner).
        sb.append(avoidCase("two structures on axis (x=110, x=250)",
                List.of(structure(110, 0, 24), structure(250, 0, 24)), List.of()));

        // Structure first, then a perpendicular crossing track - exercises the two detour
        // loops sharing the running `current`.
        sb.append(avoidCase("structure (x=110) then crossing track (x=250)",
                List.of(structure(110, 0, 24)),
                List.of(track(240, -60, 260, 60))));

        // Structure far off to the side -> beyond the perpendicular gate, no detour.
        sb.append(avoidCase("structure off to the side (x=200, z=200)",
                List.of(structure(200, 200, 24)), List.of()));

        // First structure on-corridor (pushes the route to one side), second structure
        // off-axis. Whether the second detours to the same or opposite side depends on
        // whether the decision is anchored at the corridor or the prior detour corner -
        // the discriminating case for corridor-relative detour decisions.
        sb.append(avoidCase("two structures, second off-axis (x=110 z=0, x=250 z=-20)",
                List.of(structure(110, 0, 24), structure(250, -20, 24)), List.of()));

        verify("route-avoidance-path", sb.toString());
    }

    @Test
    void footprintAwareAvoidancePaths() throws IOException {
        StringBuilder sb = new StringBuilder();

        // A compact village on the corridor. Without a footprint it is avoided as the default
        // bounding-radius circle; with its real (small) footprint the detour is sized to the actual
        // building rectangle, so it hugs closer than the circle.
        BlockPos center = new BlockPos(192, FLAT_Y, 0);
        PredictedStructure noFootprint = village(center, null);
        PredictedStructure compact = village(center, List.of(
                new BoundingBox(180, 64, -12, 204, 80, 12)));
        sb.append(avoidCase("compact village as radius circle", List.of(noFootprint), List.of()));
        sb.append(avoidCase("compact village as real footprint", List.of(compact), List.of()));

        // A long-thin village laid along the corridor, offset to one side. The circle (sized to the long
        // axis) forces a detour; the footprint, only a few blocks deep on the perpendicular, does not.
        BlockPos wideCenter = new BlockPos(192, FLAT_Y, 30);
        PredictedStructure wideNoFootprint = village(wideCenter, null);
        PredictedStructure wide = village(wideCenter, List.of(
                new BoundingBox(150, 64, 22, 234, 80, 38)));
        sb.append(avoidCase("long-thin village as radius circle", List.of(wideNoFootprint), List.of()));
        sb.append(avoidCase("long-thin village as real footprint", List.of(wide), List.of()));

        verify("route-avoidance-footprint", sb.toString());
    }

    // --- helpers ---

    private static PredictedStructure village(BlockPos center, List<BoundingBox> footprint) {
        return new PredictedStructure(
                new ChunkPos(center), center, "minecraft:villages",
                List.of("minecraft:village"), true, footprint);
    }

    private static PredictedStructure structure(int x, int z, int radius) {
        return new PredictedStructure(
                new ChunkPos(x >> 4, z >> 4),
                new BlockPos(x, FLAT_Y, z),
                "avoidance_zone:" + radius,
                List.of(),
                false);
    }

    private static ExistingTrackCluster track(int minX, int minZ, int maxX, int maxZ) {
        return new ExistingTrackCluster(OTHER_HEAD,
                new BlockPos(minX, FLAT_Y, minZ),
                new BlockPos(maxX, FLAT_Y, maxZ));
    }

    private static String avoidCase(String label, List<PredictedStructure> structures,
                                    List<ExistingTrackCluster> clusters) {
        NoiseTerrainSampler sampler = StubTerrainSampler.flat(FLAT_Y);
        List<BlockPos> path = RouteObstacleAvoider.buildPathWithAvoidance(
                sampler, START, TARGET, structures, null, clusters);

        StringBuilder sb = new StringBuilder();
        sb.append("== ").append(label).append(" (").append(path.size()).append(") ==\n");
        for (BlockPos p : path) {
            sb.append(String.format("  (%d,%d,%d)%n", p.getX(), p.getY(), p.getZ()));
        }
        return sb.toString();
    }

    private void verify(String scenario, String actual) throws IOException {
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
}
