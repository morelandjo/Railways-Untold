package com.vodmordia.railwaysuntold.worldgen.placement.companion;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.spatial.DirectionUtil;
import com.vodmordia.railwaysuntold.worldgen.head.TrackExpansionHead;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import com.vodmordia.railwaysuntold.worldgen.placement.decider.CurveParameterDecider;
import com.vodmordia.railwaysuntold.worldgen.terrain.TerrainScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

/**
 * Validates that a placement decision is compatible with companion track placement.
 * Applied as a post-constraint during planning so that both the primary and companion
 * tracks are guaranteed to be valid before committing to a decision.
 *
 */
public final class CompanionValidationService {

    private static final Logger LOGGER = LogUtils.getLogger();

    private CompanionValidationService() {
    }

    /**
     * Validates and potentially adjusts a decision for companion compatibility.
     * Only active when side-by-side mode is enabled.
     *
     * @param head      The expansion head (used to determine companion side)
     * @param decision  The planned decision
     * @param start     Current position
     * @param direction Current travel direction
     * @param scan      Terrain scan
     * @param level     Server level
     * @return The original or adjusted decision
     */
    public static PlacementDecision apply(TrackExpansionHead head, PlacementDecision decision,
                                           BlockPos start,
                                           TerrainScanner.TerrainScan scan, ServerLevel level) {
        if (!RailwaysUntoldConfig.isSideBySideEnabled()) {
            return decision;
        }

        return switch (decision.getType()) {
            case CURVE -> validateCurve(head, decision, start, scan, level);
            case SCURVE_45 -> validateSCurve45(head, decision);
            default -> decision;
        };
    }

    /**
     * Validates a curve decision for companion compatibility.
     *
     * If the curve turns toward the companion side and the companion radius would be
     * too small, tries these adjustments in order:
     * 1. Widen the primary radius so companion radius meets minimum
     * 2. Flip the turn direction (companion becomes outer track instead)
     * 3. Give up - return original decision (companion will be skipped at placement time)
     */
    private static PlacementDecision validateCurve(TrackExpansionHead head, PlacementDecision decision,
                                                    BlockPos start,
                                                    TerrainScanner.TerrainScan scan, ServerLevel level) {
        CurveParameterDecider.CurveParameters params = decision.getCurveParams();
        if (params == null) {
            return decision;
        }

        int separation = CompanionTrackPlacer.getCompanionSeparation();
        int minRadius = RailwaysUntoldConfig.getMinCurveRadius();
        int maxRadius = RailwaysUntoldConfig.getMaxCurveRadius();

        if (!curveTurnsTowardCompanion(head, params.trackDirection, params.turnLeft)) {
            // Turning away from companion - companion gets wider radius, always valid
            return decision;
        }

        // Turning toward companion - companion radius = primary radius - separation
        int companionRadius = params.radius - separation;
        if (companionRadius >= minRadius) {
            // Companion radius is fine
            return decision;
        }

        // Companion radius too small. Try adjustment 1: widen the primary radius.
        int requiredPrimaryRadius = minRadius + separation;
        if (requiredPrimaryRadius <= maxRadius) {

            CurveParameterDecider.CurveParameters widened = CurveParameterDecider.createWithRadius(
                    level, start, params.trackDirection, requiredPrimaryRadius,
                    params.turnLeft, params.elevationChange, true);

            if (widened != null) {
                return PlacementDecision.curve(widened, scan);
            }
        }

        // Adjustment 2: flip the turn direction so companion is on the outside.
        boolean flippedTurnLeft = !params.turnLeft;

        CurveParameterDecider.CurveParameters flipped = CurveParameterDecider.createWithRadius(
                level, start, params.trackDirection, params.radius,
                flippedTurnLeft, params.elevationChange, true);

        if (flipped != null) {
            // Verify the flipped curve doesn't have terrain issues
            if (CurveParameterDecider.validateCurve(level, flipped, true)) {
                return PlacementDecision.curve(flipped, scan);
            }
        }

        // No adjustment possible - let the original through, companion will be skipped
        LOGGER.warn("[COMPANION-VALIDATE] Head {}: cannot adjust curve for companion, companion will be skipped",
                head.getHeadNumber());
        return decision;
    }

    /**
     * Validates an S-curve decision for companion compatibility.
     * S-curves have two 45-degree curves - if either turns toward the companion
     * with too small a radius, we can try widening.
     */
    private static PlacementDecision validateSCurve45(TrackExpansionHead head, PlacementDecision decision) {
        int separation = CompanionTrackPlacer.getCompanionSeparation();
        int minRadius = RailwaysUntoldConfig.getMinCurveRadius();
        int radius = decision.getScurve45Radius();

        // S-curves shift laterally. If shifting toward the companion side,
        // the first curve turns toward companion (inner) and needs enough radius.
        boolean shiftTowardCompanion = shiftTurnsTowardCompanion(head, decision);

        if (!shiftTowardCompanion) {
            return decision;
        }

        int companionRadius = radius - separation;
        if (companionRadius >= minRadius) {
            return decision;
        }

        // S-curve radius can't easily be adjusted without changing the geometry entirely.
        // Log a warning - the companion will be skipped for this segment.
        LOGGER.warn("[COMPANION-VALIDATE] Head {}: S-curve radius {} too small for companion (need {}), companion will be skipped",
                head.getHeadNumber(), companionRadius, minRadius);
        return decision;
    }

    private static boolean curveTurnsTowardCompanion(TrackExpansionHead head, Direction travelDir, boolean turnLeft) {
        boolean onLeft = CompanionTrackPlacer.isCompanionOnLeft(head.getInitialDirection());
        Direction companionDir = onLeft ? DirectionUtil.getLeftDirection(travelDir)
                : DirectionUtil.getRightDirection(travelDir);
        Direction turnDir = turnLeft ? DirectionUtil.getLeftDirection(travelDir)
                : DirectionUtil.getRightDirection(travelDir);
        return turnDir == companionDir;
    }

    private static boolean shiftTurnsTowardCompanion(TrackExpansionHead head, PlacementDecision decision) {
        Direction dir = decision.getDirection();
        if (dir == null) {
            return false;
        }
        boolean onLeft = CompanionTrackPlacer.isCompanionOnLeft(head.getInitialDirection());
        // S-curve shiftLeft=true means the track shifts to the left. The first 45-degree
        // curve turns left, which goes toward the companion if companion is on the left.
        return decision.isScurve45ShiftLeft() == onLeft;
    }
}
