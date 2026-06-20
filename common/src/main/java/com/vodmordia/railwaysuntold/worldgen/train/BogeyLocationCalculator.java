package com.vodmordia.railwaysuntold.worldgen.train;

import com.vodmordia.railwaysuntold.util.spatial.DirectionUtil;
import com.vodmordia.railwaysuntold.worldgen.train.TrainValidationData.BogeyInfo;
import net.minecraft.core.Direction;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Computes each bogey's integer offset along the assembly axis from its schematic-relative position,
 * the per-carriage layout, and the inter-carriage spacings. Offsets are accumulated carriage by
 * carriage and normalized so the first bogey sits at 0. Pure layout math (no world).
 */
public final class BogeyLocationCalculator {

    private BogeyLocationCalculator() {
    }

    @Nullable
    public static int[] computeBogeyLocations(List<BogeyInfo> allBogeys, Direction assemblyDirection,
                                                List<Integer> bogeysPerCarriage,
                                                List<Integer> interCarriageSpacings) {
        if (allBogeys.isEmpty()) {
            return null;
        }

        int[] locations = new int[allBogeys.size()];
        int bogeyIdx = 0;
        int carriageOffset = 0; // Accumulated offset from inter-carriage spacings

        for (int c = 0; c < bogeysPerCarriage.size(); c++) {
            int count = bogeysPerCarriage.get(c);

            // Find the min position within this carriage to compute intra-carriage offsets
            for (int b = 0; b < count; b++) {
                int signedPos = DirectionUtil.getSignedPositionAlongAxis(
                        allBogeys.get(bogeyIdx + b).relativePos, assemblyDirection);
                locations[bogeyIdx + b] = carriageOffset + signedPos;
            }

            // Advance offset for next carriage: last bogey position + inter-carriage spacing
            if (c < interCarriageSpacings.size()) {
                int lastBogeyInCarriage = locations[bogeyIdx + count - 1];
                carriageOffset = lastBogeyInCarriage + interCarriageSpacings.get(c);
            }

            bogeyIdx += count;
        }

        // Normalize so first bogey = 0
        int minLocation = locations[0];
        for (int loc : locations) {
            minLocation = Math.min(minLocation, loc);
        }
        for (int i = 0; i < locations.length; i++) {
            locations[i] -= minLocation;
        }

        return locations;
    }
}
