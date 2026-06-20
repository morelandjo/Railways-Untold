package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import com.vodmordia.railwaysuntold.worldgen.planner.PathExecutionState;
import com.vodmordia.railwaysuntold.worldgen.planner.PathExecutor;
import com.vodmordia.railwaysuntold.worldgen.planner.PathSegment;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoutePlanner.PlanResult;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainSampler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Regression for the head-1 infinite replan loop captured in production: a head sitting at y=82 under a
 * mountain spike (terrain surface 235) planning EAST. The compiled route is fine, but it opens with a
 * run of 1-block bezier segments. {@link PathExecutor} treated the first segment as already-placed
 * (its endpoint is <=1 block away in XZ, so {@code isNearSegmentEnd} fired) even though the head was at
 * that segment's START, advanced past it, and emitted the SECOND segment - whose start is one block
 * ahead of the head. The BEZIER executor's drift guard then rejected it ("planner start drift"), the
 * head halted and replanned to the identical route, and looped forever.
 *
 * The route itself is replayed from the captured record so this exercises the real segment shape.
 */
class PlannerStartDriftTest {

    private static final String BURIED_HEAD_UNDER_MOUNTAIN = """
            REPLAY v2
            start 1232 82 -40
            target 2032 82 -206
            headId dc98d5ee-ac18-4920-9724-04b4fa39bb84
            headDir EAST
            arrivalDir -
            descentHintY -1
            isFromTrackTip true
            seaLevel 63
            maxSlopeRatio 0.25
            structures 0
            tracks 0
            oceans 0
            waters 29 1265,-40 1273,-40 1281,-40 1297,-40 1305,-40 1313,-40 1321,-40 1329,-40 1337,-40 1345,-40 1353,-40 1361,-40 1377,-40 1650,-40 1658,-40 1690,-40 1698,-40 1706,-40 1714,-40 1762,-40 1770,-40 1778,-40 1802,-40 1842,-40 1850,-40 1906,-80 1912,-86 1918,-92 1923,-97
            heights 111 1232,-40,235 1241,-40,254 1249,-40,72 1257,-40,66 1265,-40,57 1273,-40,60 1281,-40,57 1289,-40,85 1297,-40,46 1305,-40,48 1313,-40,48 1321,-40,51 1329,-40,53 1337,-40,56 1345,-40,57 1353,-40,59 1361,-40,62 1369,-40,64 1377,-40,62 1385,-40,67 1393,-40,83 1401,-40,93 1409,-40,98 1417,-40,100 1425,-40,113 1433,-40,120 1441,-40,122 1449,-40,124 1457,-40,124 1465,-40,124 1473,-40,121 1481,-40,118 1489,-40,106 1497,-40,91 1505,-40,71 1513,-40,67 1521,-40,79 1529,-40,82 1537,-40,82 1545,-40,90 1553,-40,80 1561,-40,74 1562,-40,73 1570,-40,82 1578,-40,96 1586,-40,89 1594,-40,81 1602,-40,80 1610,-40,82 1618,-40,77 1626,-40,68 1634,-40,73 1642,-40,63 1650,-40,60 1658,-40,60 1666,-40,85 1674,-40,108 1682,-40,64 1690,-40,62 1698,-40,60 1706,-40,60 1714,-40,59 1722,-40,70 1730,-40,74 1738,-40,78 1746,-40,75 1754,-40,66 1762,-40,56 1770,-40,53 1778,-40,55 1786,-40,85 1794,-40,89 1802,-40,56 1810,-40,100 1818,-40,102 1826,-40,99 1834,-40,89 1842,-40,34 1850,-40,62 1858,-40,66 1866,-40,67 1872,-46,90 1877,-51,67 1883,-57,65 1889,-63,67 1895,-69,63 1900,-74,66 1906,-80,60 1912,-86,62 1918,-92,60 1923,-97,59 1929,-103,70 1935,-109,91 1940,-114,94 1945,-119,97 1946,-120,99 1952,-126,104 1958,-132,120 1963,-137,116 1969,-143,107 1975,-149,115 1980,-154,119 1986,-160,236 1992,-166,246 1998,-172,248 2003,-177,258 2009,-183,257 2015,-189,221 2021,-195,217 2026,-200,217 2032,-206,207
            """;

    /** Minimal execution state over the compiled segment list (mirrors PathExecutorTest's stub). */
    private static final class ListState implements PathExecutionState {
        private final List<PathSegment> segments;
        int index = 0;
        ListState(List<PathSegment> segments) { this.segments = segments; }
        @Override public PathSegment getCurrentSegment() {
            return index < segments.size() ? segments.get(index) : null;
        }
        @Override public void advanceToNextSegment() { index++; }
    }

    @BeforeAll
    static void setUp() {
        PlannerTestConfig.bootstrapDefaults();
    }

    @Test
    void executorDoesNotDriftWhenHeadSitsAtStartOfAShortFirstSegment() {
        ReplayRecord rec = ReplayRecord.parse(BURIED_HEAD_UNDER_MOUNTAIN);
        NoiseTerrainSampler sampler = ReplaySamplers.fromCapture(
                rec.heights, rec.oceanCoords, rec.waterCoords, rec.seaLevel);
        PlanResult result = CoarseRoutePlanner.planRouteOffThread(
                sampler, rec.start, rec.target, rec.headId, rec.headDir, rec.arrivalDir,
                rec.structures, null, rec.tracks, rec.descentHintY, null, null, List.of(), rec.isFromTrackTip);
        assertNotNull(result, "planner returned null");

        List<PathSegment> segments = result.route().getPrecisionPath().segments;
        // The route opens with a short (1-block) first segment - the trigger for the bug.
        assertEquals(rec.start, segments.get(0).getStart(), "route must start at the head");

        // Drive the executor exactly as the head does on its first tick: it is standing on the route's
        // start. The emitted decision must begin at the head, or the BEZIER drift guard halts and loops.
        ListState state = new ListState(segments);
        PlacementDecision decision = PathExecutor.executeNextSegment(
                state, rec.start, rec.headDir, null, null);

        assertNotNull(decision, "executor produced no decision for the first tick");
        assertEquals(rec.start, decision.getStart(),
                "executor must emit a decision starting at the head; a mismatch here is the "
                        + "'planner start drift (bezier)' that looped the head forever");
    }
}
