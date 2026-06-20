package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces the in-game village over-drive (head 12 near -374,97,1791): a body core box from the
 * captured {@code target_village_skirt}, and the {@code DIAGONAL_STRAIGHT -408,98,1825 -> -318,98,1735}
 * arrival maneuver that cut across it.
 */
class SkirtBodyIntrusionInspectorTest {

    /** One core box of the captured village body (target_village_skirt footprint). */
    private static final List<BoundingBox> BODY = List.of(new BoundingBox(-403, 92, 1808, -388, 105, 1823));

    @Test
    void overdriveDiagonalThroughBodyIsDetected() {
        assertTrue(SkirtBodyIntrusionInspector.segmentIntrudes(
                new BlockPos(-408, 98, 1825), new BlockPos(-318, 98, 1735), BODY),
                "the diagonal that cuts across the village body should be flagged as an over-drive");
    }

    @Test
    void cleanSouthApproachStaysClear() {
        // The legitimate approach reaches the station (south of the body, z=1723) without entering it.
        assertFalse(SkirtBodyIntrusionInspector.segmentIntrudes(
                new BlockPos(-405, 96, 1739), new BlockPos(-396, 94, 1723), BODY),
                "a south approach to the station must not be flagged");
    }

    @Test
    void highViaductAboveBodyIsAllowed() {
        // Track far above the body's Y extent (beyond the Y band) is not an over-drive.
        assertFalse(SkirtBodyIntrusionInspector.segmentIntrudes(
                new BlockPos(-408, 130, 1825), new BlockPos(-318, 130, 1735), BODY),
                "a high viaduct clear of the body's Y extent must not be flagged");
    }
}
