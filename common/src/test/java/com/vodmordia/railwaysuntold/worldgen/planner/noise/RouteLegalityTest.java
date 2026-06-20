package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.worldgen.planner.CurveSegment;
import com.vodmordia.railwaysuntold.worldgen.planner.DiagonalCurveSegment;
import com.vodmordia.railwaysuntold.worldgen.planner.PathSegment;
import com.vodmordia.railwaysuntold.worldgen.planner.SCurve45Segment;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoutePlanner.PlanResult;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainSampler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Re-plans a captured production route and asserts every curve it compiles obeys the minimum curve
 * radius. Create trains cannot traverse a curve tighter than that limit, so a sub-minimum radius is
 * track that breaks train movement.
 *
 * The embedded record is the head-2 plan from seed 2144273545050704023 (start -852,72,-186) that
 * produced an illegal radius-5 curve in a short switchback near -918,-241: PrecisionRouteCompiler
 * shrinks a 90° curve's radius to fit a short run, dropping it below the minimum.
 */
class RouteLegalityTest {

    private static final String ILLEGAL_SHORT_RUN_CURVE = """
            REPLAY v2
            start -852 72 -186
            target -914 117 -262
            headId c7aebfe8-70d9-41b1-9dcf-93376d60f1a1
            headDir WEST
            arrivalDir WEST
            descentHintY 117
            isFromTrackTip true
            seaLevel 63
            maxSlopeRatio 0.25
            structures 1
            structure -53 -14 -840 76 -216 false minecraft:igloos minecraft:igloo
            tracks 0
            oceans 0
            waters 6 -878,-164 -872,-158 -869,-165 -869,-155 -865,-151 -863,-149
            heights 33 -918,-241,106 -918,-232,93 -918,-222,81 -918,-213,71 -918,-204,71 -914,-262,117 -911,-248,116 -911,-197,73 -910,-196,73 -905,-262,117 -905,-191,75 -904,-255,118 -898,-260,118 -898,-184,76 -897,-262,118 -892,-178,74 -885,-171,66 -883,-169,65 -878,-164,61 -872,-158,53 -869,-186,77 -869,-176,73 -869,-165,59 -869,-157,51 -869,-155,51 -865,-151,56 -863,-149,60 -860,-186,76 -859,-145,63 -858,-144,63 -852,-186,70 -852,-138,74 -795,-261,116
            """;

    @Test
    void shortRunDoesNotShrinkCurveRadiusBelowMinimum() {
        List<String> violations = curveRadiusViolations(ILLEGAL_SHORT_RUN_CURVE);
        assertTrue(violations.isEmpty(),
                "compiled route has curve(s) tighter than the minimum radius "
                        + RailwaysUntoldConfig.getMinCurveRadius() + ": " + violations);
    }

    /** Re-plans the record and returns one description per curve whose radius is below the minimum. */
    private static List<String> curveRadiusViolations(String record) {
        ReplayRecord rec = ReplayRecord.parse(record);
        NoiseTerrainSampler sampler = ReplaySamplers.fromCapture(
                rec.heights, rec.oceanCoords, rec.waterCoords, rec.seaLevel);
        PlanResult result = CoarseRoutePlanner.planRouteOffThread(
                sampler, rec.start, rec.target, rec.headId, rec.headDir, rec.arrivalDir,
                rec.structures, null, rec.tracks, rec.descentHintY, null, null, List.of(), rec.isFromTrackTip);

        int min = RailwaysUntoldConfig.getMinCurveRadius();
        List<String> violations = new ArrayList<>();
        for (PathSegment s : result.route().getPrecisionPath().segments) {
            Integer radius = curveRadius(s);
            if (radius != null && radius < min) {
                violations.add(s.type + " r=" + radius + " at " + s.getStart());
            }
        }
        return violations;
    }

    /** The curve radius of a turning segment, or null for non-curved segments. */
    private static Integer curveRadius(PathSegment s) {
        if (s instanceof CurveSegment c) {
            return c.radius;
        }
        if (s instanceof DiagonalCurveSegment d) {
            return d.radius;
        }
        if (s instanceof SCurve45Segment sc) {
            return sc.radius;
        }
        return null;
    }
}
