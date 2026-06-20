package com.vodmordia.railwaysuntold.worldgen.village.network;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterizes {@link DelaunayPlanner#planDelaunay}, the pure 2D Bowyer-Watson triangulation. The exact
 * triangulation of cocircular inputs (e.g. a square's four corners) is ambiguous, so this pins the robust
 * structural guarantees the consumers rely on rather than an exact edge set: super-triangle scaffolding
 * never leaks, every input point is covered, edges are undirected-deduped, the max-length filter prunes,
 * and degenerate inputs collapse to nothing.
 */
class DelaunayPlannerTest {

    private static BlockPos p(int x, int z) {
        return new BlockPos(x, 0, z);
    }

    /** The set of distinct XZ keys present in the input. */
    private static Set<Long> xzKeys(List<BlockPos> pts) {
        Set<Long> keys = new HashSet<>();
        for (BlockPos b : pts) keys.add((((long) b.getX()) << 32) ^ (b.getZ() & 0xffffffffL));
        return keys;
    }

    private static long key(BlockPos b) {
        return (((long) b.getX()) << 32) ^ (b.getZ() & 0xffffffffL);
    }

    @Test
    void degenerateInputsProduceNoEdges() {
        assertEquals(List.of(), DelaunayPlanner.planDelaunay(null, 1000));
        assertEquals(List.of(), DelaunayPlanner.planDelaunay(List.of(), 1000));
        assertEquals(List.of(), DelaunayPlanner.planDelaunay(List.of(p(5, 5)), 1000));
        // Two positions that collapse to one XZ key (Y is flattened) are a single unique point -> no edge.
        assertEquals(List.of(), DelaunayPlanner.planDelaunay(
                List.of(new BlockPos(5, 10, 5), new BlockPos(5, 90, 5)), 1000));
    }

    @Test
    void twoDistinctPointsYieldOneDirectEdge() {
        // Two points form no triangle, so they take the direct-connect path: a two-village region gets a
        // single rail link between the pair.
        List<StructureConnection> edges = DelaunayPlanner.planDelaunay(List.of(p(0, 0), p(10, 0)), 1000);
        assertEquals(1, edges.size());
        StructureConnection e = edges.get(0);
        assertEquals(xzKeys(List.of(p(0, 0), p(10, 0))), Set.of(key(e.from()), key(e.to())));
    }

    @Test
    void twoPointsBeyondTheMaxLengthYieldNoEdge() {
        // The direct two-point link still honors the length filter: too-far villages stay unconnected.
        assertEquals(List.of(), DelaunayPlanner.planDelaunay(List.of(p(0, 0), p(1000, 0)), 500));
    }

    @Test
    void duplicatePointsAreDedupedByXZ() {
        // Four listed points, two sharing an XZ -> three unique points -> one triangle -> three edges.
        // (Demonstrates dedup while still landing above the three-point edge threshold.)
        List<StructureConnection> edges = DelaunayPlanner.planDelaunay(
                List.of(p(0, 0), p(0, 0), p(100, 0), p(0, 100)), 1000);
        assertEquals(3, edges.size());
    }

    @Test
    void aSingleTriangleYieldsItsThreeEdges() {
        // Non-collinear, non-degenerate: the Delaunay triangulation is the one triangle itself.
        List<StructureConnection> edges = DelaunayPlanner.planDelaunay(
                List.of(p(0, 0), p(100, 0), p(0, 100)), 1000);
        assertEquals(3, edges.size(), "a triangle has exactly three edges");
    }

    @Test
    void superTriangleVerticesNeverLeakAndEveryInputIsCovered() {
        List<BlockPos> pts = List.of(p(0, 0), p(50, 0), p(0, 50), p(50, 50), p(25, 25));
        List<StructureConnection> edges = DelaunayPlanner.planDelaunay(pts, 10_000);

        Set<Long> inputKeys = xzKeys(pts);
        Set<Long> covered = new HashSet<>();
        for (StructureConnection e : edges) {
            // No endpoint may be a super-triangle vertex - every endpoint is one of the inputs.
            assertTrue(inputKeys.contains(key(e.from())), "edge endpoint not an input point: " + e.from());
            assertTrue(inputKeys.contains(key(e.to())), "edge endpoint not an input point: " + e.to());
            covered.add(key(e.from()));
            covered.add(key(e.to()));
        }
        assertEquals(inputKeys, covered, "every input point must appear in at least one edge");
    }

    @Test
    void edgesAreUndirectedAndDeduplicated() {
        List<BlockPos> pts = List.of(p(0, 0), p(50, 0), p(0, 50), p(50, 50), p(25, 25));
        List<StructureConnection> edges = DelaunayPlanner.planDelaunay(pts, 10_000);

        Set<Long> undirected = new HashSet<>();
        for (StructureConnection e : edges) {
            long a = key(e.from()), b = key(e.to());
            long lo = Math.min(a, b), hi = Math.max(a, b);
            assertTrue(undirected.add(lo * 31 + hi), "duplicate undirected edge: " + e.from() + "->" + e.to());
        }
        assertEquals(edges.size(), undirected.size());
    }

    @Test
    void theMaxEdgeLengthFilterPrunesLongEdges() {
        // A thin triangle: one short edge (10) and two long edges (~1000).
        List<BlockPos> pts = List.of(p(0, 0), p(10, 0), p(0, 1000));
        assertEquals(3, DelaunayPlanner.planDelaunay(pts, 2000).size(), "generous limit keeps all three");
        List<StructureConnection> pruned = DelaunayPlanner.planDelaunay(pts, 500);
        assertEquals(1, pruned.size(), "a 500-block limit keeps only the short edge");
        long span = Math.abs((long) pruned.get(0).from().getX() - pruned.get(0).to().getX())
                + Math.abs((long) pruned.get(0).from().getZ() - pruned.get(0).to().getZ());
        assertTrue(span <= 500, "the surviving edge is the short one");
    }

    @Test
    void theTriangulationOverManyPointsIsConnected() {
        // With a generous max length the Delaunay graph spans all points - head targeting relies on this.
        List<BlockPos> pts = List.of(p(0, 0), p(200, 30), p(-150, 120), p(80, -200), p(300, 300), p(-90, -90));
        List<StructureConnection> edges = DelaunayPlanner.planDelaunay(pts, 100_000);

        Map<Long, List<Long>> adj = new HashMap<>();
        for (BlockPos b : pts) adj.put(key(b), new ArrayList<>());
        for (StructureConnection e : edges) {
            adj.get(key(e.from())).add(key(e.to()));
            adj.get(key(e.to())).add(key(e.from()));
        }
        Set<Long> reached = new HashSet<>();
        Deque<Long> stack = new ArrayDeque<>();
        long start = key(pts.get(0));
        stack.push(start);
        reached.add(start);
        while (!stack.isEmpty()) {
            for (long nb : adj.get(stack.pop())) {
                if (reached.add(nb)) stack.push(nb);
            }
        }
        assertEquals(xzKeys(pts).size(), reached.size(), "every village is reachable through the network");
    }
}
