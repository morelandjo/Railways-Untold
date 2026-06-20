package com.vodmordia.railwaysuntold.worldgen.village;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Holder for StationZone, the runway/station description produced by the precomputed village layouts.
 */
public class VillageEdgeFinder {

    public static class StationZone {
        public final BlockPos stationPosition;
        public final Direction trackDirection;     // Perpendicular - which way track runs along corridor
        public final Direction approachDirection;  // Which side of village the station is on
        public final BlockPos trackStart;
        public final BlockPos trackEnd;
        public final VillageBounds villageBounds;

        public StationZone(BlockPos stationPosition, Direction trackDirection, Direction approachDirection,
                           BlockPos trackStart, BlockPos trackEnd, VillageBounds villageBounds) {
            this.stationPosition = stationPosition;
            this.trackDirection = trackDirection;
            this.approachDirection = approachDirection;
            this.trackStart = trackStart;
            this.trackEnd = trackEnd;
            this.villageBounds = villageBounds;
        }

        @Override
        public String toString() {
            return String.format("StationZone[pos=%s, dir=%s, approach=%s, track=%s->%s, bounds=%s]",
                    stationPosition, trackDirection, approachDirection, trackStart, trackEnd,
                    villageBounds != null ? villageBounds.toChatString() : "null");
        }
    }

}
