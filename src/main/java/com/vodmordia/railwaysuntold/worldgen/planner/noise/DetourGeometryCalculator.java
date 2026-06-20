package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainSampler;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure geometry for routing around a single obstacle: given the corridor (origin, running corner, and
 * octagonal travel direction) and the obstacle's center/radius/clearance offset, produces the 2D detour
 * corners (optional pre-approach, then approach, bypass, return). The only terrain dependence is reading
 * base heights to pick the shorter-climb side; everything else is coordinate math. No world.
 */
final class DetourGeometryCalculator {

    private DetourGeometryCalculator() {
    }

    static List<BlockPos> createDetourWaypoints(
            NoiseTerrainSampler sampler, BlockPos start, BlockPos current,
            int centerX, int centerZ, int obstacleRadius, int offset,
            double dirX, double dirZ) {

        // Left perpendicular of travel direction (90° counterclockwise).
        double[] leftPerp = RouteGeometry.perpendicular(dirX, dirZ, 1.0);
        double leftPerpX = leftPerp[0];
        double leftPerpZ = leftPerp[1];
        double rightPerpX = -leftPerpX;
        double rightPerpZ = -leftPerpZ;

        int leftSampleX = centerX + (int)(leftPerpX * offset);
        int leftSampleZ = centerZ + (int)(leftPerpZ * offset);
        int rightSampleX = centerX + (int)(rightPerpX * offset);
        int rightSampleZ = centerZ + (int)(rightPerpZ * offset);

        // Side choice is corridor-relative (anchored at the corridor origin), independent
        // of any prior detour, so successive obstacles aren't pushed to the same side.
        int baselineY = sampler.getBaseHeight(start.getX(), start.getZ());
        int leftHeight = sampler.getBaseHeight(leftSampleX, leftSampleZ);
        int rightHeight = sampler.getBaseHeight(rightSampleX, rightSampleZ);

        int leftDelta = Math.abs(leftHeight - baselineY);
        int rightDelta = Math.abs(rightHeight - baselineY);

        // Signed perpendicular offset of the obstacle from the corridor line, measured
        // using the LEFT perpendicular as the positive axis. Positive = obstacle is to
        // the LEFT of travel direction, negative = RIGHT. Detour to the opposite side
        // to minimize total lateral travel (obstacle is already closer to one side).
        double obstacleLeftOffset = (centerX - start.getX()) * leftPerpX + (centerZ - start.getZ()) * leftPerpZ;

        // If obstacle is on the left, prefer detouring right (short side).
        // If obstacle is on the right, prefer detouring left.
        int preferRight = obstacleLeftOffset > 0 ? 1 : 0;
        int preferLeft = obstacleLeftOffset < 0 ? 1 : 0;

        int leftScore = leftDelta - 10 * preferLeft;
        int rightScore = rightDelta - 10 * preferRight;

        boolean goLeft = leftScore <= rightScore;
        double chosenPerpX = goLeft ? leftPerpX : rightPerpX;
        double chosenPerpZ = goLeft ? leftPerpZ : rightPerpZ;

        // dirX/dirZ already arrive octagonal (snapped in buildPathWithAvoidance), and the
        // perpendicular of an octagonal unit vector is itself octagonal - so no re-snap is
        // needed for the detour waypoints to align with the compiler's cardinal/diagonal runs.
        double[] snappedDir = {dirX, dirZ};
        double[] snappedPerp = {chosenPerpX, chosenPerpZ};

        int approachX = centerX - (int)(snappedDir[0] * obstacleRadius) + (int)(snappedPerp[0] * offset);
        int approachZ = centerZ - (int)(snappedDir[1] * obstacleRadius) + (int)(snappedPerp[1] * offset);
        int bypassX = centerX + (int)(snappedPerp[0] * offset);
        int bypassZ = centerZ + (int)(snappedPerp[1] * offset);
        int returnX = centerX + (int)(snappedDir[0] * obstacleRadius) + (int)(snappedPerp[0] * offset);
        int returnZ = centerZ + (int)(snappedDir[1] * obstacleRadius) + (int)(snappedPerp[1] * offset);

        // Pre-approach: project current onto the cluster's leading-edge perpendicular
        // line, keeping current's perp position.
        double forwardToApproach = (approachX - current.getX()) * snappedDir[0]
                + (approachZ - current.getZ()) * snappedDir[1];
        // Detour corners are a 2D plan: RouteTerrainClassifier re-samples every waypoint's Y
        // downstream, so the corner Y here is a placeholder (the corridor-origin Y). Only the
        // left/right side-choice samples above read terrain height.
        int placeholderY = start.getY();
        List<BlockPos> detour = new ArrayList<>();
        if (forwardToApproach > 0) {
            int preApproachX = current.getX() + (int)(snappedDir[0] * forwardToApproach);
            int preApproachZ = current.getZ() + (int)(snappedDir[1] * forwardToApproach);
            // Skip if preApproach collapses onto current (no forward distance) or
            // onto approach (no perpendicular sidestep needed - current is already
            // on the offset axis).
            boolean collapsesOntoCurrent = preApproachX == current.getX() && preApproachZ == current.getZ();
            boolean collapsesOntoApproach = preApproachX == approachX && preApproachZ == approachZ;
            if (!collapsesOntoCurrent && !collapsesOntoApproach) {
                detour.add(new BlockPos(preApproachX, placeholderY, preApproachZ));
            }
        }
        detour.add(new BlockPos(approachX, placeholderY, approachZ));
        detour.add(new BlockPos(bypassX, placeholderY, bypassZ));
        detour.add(new BlockPos(returnX, placeholderY, returnZ));
        return detour;
    }
}
