package com.vodmordia.railwaysuntold.worldgen.placement.support;

import com.vodmordia.railwaysuntold.util.block.BlockTypeUtil;
import com.vodmordia.railwaysuntold.util.block.ChunkSafeBlockAccess;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.CreateTrackUtil;
import com.vodmordia.railwaysuntold.worldgen.village.StationBlockProtection;
import com.vodmordia.railwaysuntold.worldgen.village.VillageTargetingSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Places terrain fill blocks (default: gravel) under tracks that are 1-3 blocks above ground.
 * Covers the zone between ground level and bridge decking threshold.
 *
 */
public class TerrainFillPlacer {

    /**
     * Places terrain fill under a straight track position.
     */
    public static void placeTerrainFill(ServerLevel level, BlockPos trackPos, Vec3 tangent) {
        placeTerrainFillAtY(level, trackPos.getX(), trackPos.getY(), trackPos.getZ(), tangent);
    }

    /**
     * Places terrain fill under a bezier curve position.
     */
    public static void placeTerrainFill(ServerLevel level, Vec3 worldPos, Vec3 tangent) {
        // On slopes the track model dips below the nominal Y position. Offset by the
        // slope magnitude so that positions near a Y integer boundary floor to the lower
        // block, preventing gravel from clipping through the track at transitions.
        double slopeAbs = Math.abs(tangent.y);
        double absX = Math.abs(tangent.x);
        double absZ = Math.abs(tangent.z);
        double maxHoriz = Math.max(absX, absZ);
        // Inclined 45° diagonals: Create's rail ties extend down into the block below the
        // rail surface, and on a shallow 1-Y-over-many-blocks slope they clip where the
        // rail sits in the upper portion of its block.
        boolean isInclinedDiagonal = slopeAbs > 0.005 && maxHoriz > 0.1
                && Math.min(absX, absZ) / maxHoriz > 0.5;
        // Inclined cardinal bridges: the tie bottoms are at rail_surface_y - 3/16, so
        // whenever the rail surface sits in the lower 3/16 of its block the ties dip
        // into the block below and a fill top at (trackY-1) clips them.
        double yFrac = worldPos.y - Math.floor(worldPos.y);
        boolean tiesDipIntoBlockBelow = slopeAbs > 0.005 && yFrac < 0.1875;
        double correction;
        if (isInclinedDiagonal || tiesDipIntoBlockBelow) {
            correction = Math.max(slopeAbs * 1.5, 1.0);
        } else {
            correction = slopeAbs > 0.01 ? Math.max(slopeAbs * 1.5, 0.15) : 0.0;
        }
        double effectiveY = worldPos.y - correction;
        placeTerrainFillAtY(level, Mth.floor(worldPos.x), Mth.floor(effectiveY),
                Mth.floor(worldPos.z), tangent);
    }

    /**
     * Places terrain fill with gap interpolation from a previous position.
     */
    public static void placeTerrainFillWithGaps(ServerLevel level,
                                                 int prevX, int prevY, int prevZ, Vec3 prevTangent,
                                                 int currX, int currY, int currZ, Vec3 currTangent) {
        int dx = currX - prevX;
        int dy = currY - prevY;
        int dz = currZ - prevZ;
        int manhattan = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
        if (manhattan <= 1) return;

        for (int i = 1; i < manhattan; i++) {
            double t = (double) i / manhattan;
            int gapX = prevX + Math.round((float) dx * i / manhattan);
            // Use floor for Y to conservatively round down
            int gapY = prevY + Mth.floor((float) dy * i / manhattan);
            int gapZ = prevZ + Math.round((float) dz * i / manhattan);
            Vec3 gapTangent = prevTangent.scale(1.0 - t).add(currTangent.scale(t));
            placeTerrainFillAtY(level, gapX, gapY, gapZ, gapTangent);
        }
    }

    private static void placeTerrainFillAtY(ServerLevel level, int x, int trackY, int z, Vec3 tangent) {
        BlockPos biomePos = new BlockPos(x, trackY, z);
        BlockState topBlock = SupportConstants.getTerrainFillBlock(level, biomePos);
        BlockState baseBlock = SupportConstants.getTerrainFillBaseBlock(level, biomePos);
        if (topBlock == null && baseBlock == null) return;

        int fillTopY = trackY - 1;
        int groundY = findGroundY(level, x, fillTopY, z);
        if (groundY < 0) {
            SupportDecisionLogger.capture(level, new BlockPos(x, trackY, z),
                    SupportProfileRecord.Decision.NONE, SupportProfileRecord.Reason.NO_FLOOR);
            return;
        }

        // Only fill if there's a gap but it's below bridge decking threshold.
        int gap = fillTopY - groundY;
        if (gap <= 0) {
            return;
        }
        int bridgeThreshold = SupportConstants.getBridgeElevationThreshold();
        int gapFromDeckingLevel = (trackY - 2) - groundY;
        if (bridgeThreshold > 0 && gapFromDeckingLevel > bridgeThreshold) {
            SupportDecisionLogger.capture(level, new BlockPos(x, trackY, z),
                    SupportProfileRecord.Decision.NONE, SupportProfileRecord.Reason.DEEP_GAP);
            return;
        }

        Vec3 perp = new Vec3(-tangent.z, 0, tangent.x).normalize();

        // Compute positions at different widths - widens with each layer down
        // Top=1.0, second=2.0, rest=3.0
        List<int[]> topPositions = computePositions(x, z, perp, 1.0);
        List<int[]> narrowPositions = computePositions(x, z, perp, 2.0);
        List<int[]> widePositions = computePositions(x, z, perp, 3.0);

        // Top two layers use the top fill block (gravel), everything below uses base block (cobblestone)
        int topLayerMinY = fillTopY - 1; // trackY-1 and trackY-2 are the top two layers

        StationBlockProtection protection = VillageTargetingSavedData.get(level).getStationBlockProtection();

        // Place bottom-up for gravity block support
        for (int y = groundY + 1; y <= fillTopY; y++) {
            boolean isTopLayer = y >= topLayerMinY;
            List<int[]> positions;
            if (y == fillTopY) {
                positions = topPositions;
            } else if (y == fillTopY - 1) {
                positions = narrowPositions;
            } else {
                positions = widePositions;
            }
            BlockState block = isTopLayer ? topBlock : baseBlock;
            if (block == null) continue;
            for (int[] pos : positions) {
                placeFillBlock(level, block, pos[0], y, pos[1], protection);
            }
        }

        SupportDecisionLogger.capture(level, new BlockPos(x, trackY, z),
                SupportProfileRecord.Decision.TERRAIN_FILL);
    }

    /**
     * Finds ground Y by scanning down from startY.
     */
    private static int findGroundY(ServerLevel level, int x, int startY, int z) {
        BlockPos.MutableBlockPos check = new BlockPos.MutableBlockPos(x, startY, z);
        int minY = level.getMinBuildHeight();
        int maxScan = 64;

        for (int i = 0; i < maxScan && check.getY() >= minY; i++) {
            BlockState state = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, check);
            if (state == null) return -1;
            if (!BlockTypeUtil.isAirOrLiquid(state) && !state.canBeReplaced() && !BlockTypeUtil.isTreeOrWood(state)) {
                // Skip solid at startY - it's in the clearing corridor and will be removed.
                if (check.getY() == startY) {
                    check.setY(check.getY() - 1);
                    continue;
                }
                return check.getY();
            }
            check.setY(check.getY() - 1);
        }
        return -1;
    }

    /**
     * Computes unique block positions along the perpendicular direction.
     * @param halfWidth 2.0 for 5-wide, 3.0 for 7-wide
     */
    private static List<int[]> computePositions(int centerX, int centerZ, Vec3 perp, double halfWidth) {
        List<int[]> positions = new ArrayList<>();
        int prevX = Integer.MIN_VALUE, prevZ = Integer.MIN_VALUE;

        // Scale range for diagonal perpendiculars (same approach as BridgeSchematicPlacer)
        double maxComponent = Math.max(Math.abs(perp.x), Math.abs(perp.z));
        double range = maxComponent > 0.01 ? halfWidth / maxComponent : halfWidth;

        for (double offset = -range; offset <= range + 0.01; offset += 0.5) {
            int bx = centerX + Mth.floor(perp.x * offset + 0.5);
            int bz = centerZ + Mth.floor(perp.z * offset + 0.5);
            if (bx != prevX || bz != prevZ) {
                positions.add(new int[]{bx, bz});
                prevX = bx;
                prevZ = bz;
            }
        }

        // Fill diagonal staircase gaps
        List<int[]> filled = new ArrayList<>();
        for (int i = 0; i < positions.size(); i++) {
            filled.add(positions.get(i));
            if (i + 1 < positions.size()) {
                int[] cur = positions.get(i);
                int[] next = positions.get(i + 1);
                int dx = next[0] - cur[0];
                int dz = next[1] - cur[1];
                if (Math.abs(dx) + Math.abs(dz) > 1) {
                    double distStepX = Math.abs((cur[0] + dx - centerX) * perp.z - (cur[1] - centerZ) * perp.x);
                    double distStepZ = Math.abs((cur[0] - centerX) * perp.z - (cur[1] + dz - centerZ) * perp.x);
                    if (distStepX <= distStepZ) {
                        filled.add(new int[]{cur[0] + dx, cur[1]});
                    } else {
                        filled.add(new int[]{cur[0], cur[1] + dz});
                    }
                }
            }
        }

        return filled;
    }

    private static void placeFillBlock(ServerLevel level, BlockState fillBlock, int x, int y, int z,
                                       StationBlockProtection protection) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState existing = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, pos);
        if (existing == null) return;

        // Don't overwrite track blocks
        if (CreateTrackUtil.isTrackBlock(existing)) {
            return;
        }

        // Force-place through natural terrain - clearing is deferred and would remove
        // these blocks later, leaving gaps.
        ChunkSafeBlockAccess.setBlockStateNonBlocking(level, pos, fillBlock, true);
        protection.protect(pos);
    }

    private TerrainFillPlacer() {}
}
