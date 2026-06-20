package com.vodmordia.railwaysuntold.worldgen.placement.placer;

import com.vodmordia.railwaysuntold.blocks.core.DeadEndBlock;
import com.vodmordia.railwaysuntold.blocks.core.StartBlock;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.CreateTrackUtil;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates that a schematic contains track markers on two opposite edges,
 * or track on one edge and a dead end/start marker on the opposite edge.
 *
 * Requirements for normal events:
 * 1. Schematic must contain at least two Create track blocks
 * 2. Track blocks must exist on two opposite edges (entry and exit)
 * 3. Edge track blocks must share the same perpendicular offset and Y level
 * 4. Track between edges is optional - the schematic can have broken/missing track
 *
 * Requirements for dead-end events:
 * 1. Track block(s) on one edge, dead end block(s) on the opposite edge
 * 2. Track and dead end blocks must share the same perpendicular offset and Y level
 */
public class SchematicValidator {

    /**
     * Result of schematic validation.
     */
    public static class SchematicValidationResult {
        public final boolean valid;
        public final boolean isDeadEnd;          // true if one edge has a dead end marker instead of track
        public final boolean hasStart;           // true if one edge has a start marker that spawns a new head
        @Nullable
        public final Direction startDirection;   // facing direction of the start block (in schematic space, before rotation)
        public final Direction trackDirection;   // NORTH/SOUTH (Z-axis) or EAST/WEST (X-axis)
        public final int trackY;                 // Y level of track in schematic
        public final int trackLength;            // Length of track segment
        public final int trackPerpOffset;        // Perpendicular offset from edge (for X-axis: Z offset, for Z-axis: X offset)
        public final String errorMessage;

        private SchematicValidationResult(boolean valid, boolean isDeadEnd, boolean hasStart,
                                          @Nullable Direction startDirection, Direction trackDirection, int trackY,
                                          int trackLength, int trackPerpOffset, String errorMessage) {
            this.valid = valid;
            this.isDeadEnd = isDeadEnd;
            this.hasStart = hasStart;
            this.startDirection = startDirection;
            this.trackDirection = trackDirection;
            this.trackY = trackY;
            this.trackLength = trackLength;
            this.trackPerpOffset = trackPerpOffset;
            this.errorMessage = errorMessage;
        }

        public static SchematicValidationResult success(Direction trackDirection, int trackY,
                                                        int trackLength, int trackPerpOffset) {
            return new SchematicValidationResult(true, false, false, null, trackDirection, trackY, trackLength, trackPerpOffset, null);
        }

        public static SchematicValidationResult successDeadEnd(Direction trackDirection, int trackY,
                                                                int trackLength, int trackPerpOffset) {
            return new SchematicValidationResult(true, true, false, null, trackDirection, trackY, trackLength, trackPerpOffset, null);
        }

        public static SchematicValidationResult successStart(Direction trackDirection, int trackY,
                                                              int trackLength, int trackPerpOffset,
                                                              Direction startDirection) {
            return new SchematicValidationResult(true, false, true, startDirection, trackDirection, trackY, trackLength, trackPerpOffset, null);
        }

        public static SchematicValidationResult failure(String errorMessage) {
            return new SchematicValidationResult(false, false, false, null, null, 0, 0, 0, errorMessage);
        }

        @Override
        public String toString() {
            if (valid) {
                String extra = isDeadEnd ? ", deadEnd" : hasStart ? ", start=" + startDirection : "";
                return String.format("Valid[dir=%s, y=%d, length=%d, perpOffset=%d%s]",
                        trackDirection, trackY, trackLength, trackPerpOffset, extra);
            } else {
                return String.format("Invalid[%s]", errorMessage);
            }
        }
    }

    private record TrackPosition(int x, int y, int z) {
    }

    private record StartPosition(int x, int y, int z, Direction facing) {
    }

    public static SchematicValidationResult validate(NbtSchematicLoader.LoadedSchematic schematic) {
        if (schematic == null) {
            return SchematicValidationResult.failure("Schematic is null");
        }

        List<TrackPosition> trackPositions = findAllTrackBlocks(schematic);
        List<TrackPosition> deadEndPositions = findAllDeadEndBlocks(schematic);
        List<StartPosition> startPositions = findAllStartBlocks(schematic);

        List<TrackPosition> markerPositions = new ArrayList<>(deadEndPositions);
        for (StartPosition sp : startPositions) {
            markerPositions.add(new TrackPosition(sp.x, sp.y, sp.z));
        }

        if (trackPositions.isEmpty()) {
            return SchematicValidationResult.failure("No track blocks found in schematic");
        }

        int trackY = trackPositions.get(0).y;
        for (TrackPosition pos : trackPositions) {
            if (pos.y != trackY) {
                return SchematicValidationResult.failure(
                        String.format("Track blocks at different Y levels (%d and %d) - track must be flat", trackY, pos.y));
            }
        }
        for (TrackPosition pos : deadEndPositions) {
            if (pos.y != trackY) {
                return SchematicValidationResult.failure(
                        String.format("Dead end block at Y=%d does not match track Y=%d", pos.y, trackY));
            }
        }
        for (StartPosition pos : startPositions) {
            if (pos.y != trackY) {
                return SchematicValidationResult.failure(
                        String.format("Start block at Y=%d does not match track Y=%d", pos.y, trackY));
            }
        }

        TrackAlignmentResult alignment = determineTrackAlignment(trackPositions, markerPositions);
        if (alignment.errorMessage != null) {
            return SchematicValidationResult.failure(alignment.errorMessage);
        }

        if (alignment.runsAlongZAxis) {
            return validateZAxisTrack(trackPositions, deadEndPositions, startPositions, schematic.getLength(), trackY);
        } else {
            return validateXAxisTrack(trackPositions, deadEndPositions, startPositions, schematic.getWidth(), trackY);
        }
    }

    private record TrackAlignmentResult(boolean runsAlongZAxis, String errorMessage) {}

    private static TrackAlignmentResult determineTrackAlignment(List<TrackPosition> trackPositions,
                                                                 List<TrackPosition> markerPositions) {
        List<TrackPosition> allPositions = new ArrayList<>(trackPositions);
        allPositions.addAll(markerPositions);

        boolean hasMatchingX = false;
        boolean hasMatchingZ = false;

        for (int i = 0; i < allPositions.size(); i++) {
            for (int j = i + 1; j < allPositions.size(); j++) {
                if (allPositions.get(i).x == allPositions.get(j).x) hasMatchingX = true;
                if (allPositions.get(i).z == allPositions.get(j).z) hasMatchingZ = true;
            }
        }

        if (!hasMatchingX && !hasMatchingZ) {
            return new TrackAlignmentResult(false, "No track/dead-end blocks share a coordinate - entry and exit must be on the same perpendicular line");
        }

        if (allPositions.size() < 2) {
            return new TrackAlignmentResult(false, "Need at least two marker blocks (track and/or dead end) on opposite edges");
        }

        return new TrackAlignmentResult(hasMatchingX, null);
    }

    private static SchematicValidationResult validateZAxisTrack(List<TrackPosition> trackPositions,
                                                                 List<TrackPosition> deadEndPositions,
                                                                 List<StartPosition> startPositions,
                                                                 int length, int trackY) {
        List<TrackPosition> trackStartEdge = trackPositions.stream().filter(p -> p.z == 0).toList();
        List<TrackPosition> trackEndEdge = trackPositions.stream().filter(p -> p.z == length - 1).toList();

        if (!trackStartEdge.isEmpty() && !trackEndEdge.isEmpty()) {
            for (TrackPosition start : trackStartEdge) {
                for (TrackPosition end : trackEndEdge) {
                    if (start.x == end.x) {
                        return SchematicValidationResult.success(Direction.SOUTH, trackY, length, start.x);
                    }
                }
            }
            return SchematicValidationResult.failure("No matching perpendicular offset (X) between start and end edge track blocks");
        }

        List<TrackPosition> deadEndStartEdge = deadEndPositions.stream().filter(p -> p.z == 0).toList();
        List<TrackPosition> deadEndEndEdge = deadEndPositions.stream().filter(p -> p.z == length - 1).toList();

        if (!trackStartEdge.isEmpty() && !deadEndEndEdge.isEmpty()) {
            for (TrackPosition track : trackStartEdge) {
                for (TrackPosition de : deadEndEndEdge) {
                    if (track.x == de.x) {
                        return SchematicValidationResult.successDeadEnd(Direction.SOUTH, trackY, length, track.x);
                    }
                }
            }
        }
        if (!trackEndEdge.isEmpty() && !deadEndStartEdge.isEmpty()) {
            for (TrackPosition track : trackEndEdge) {
                for (TrackPosition de : deadEndStartEdge) {
                    if (track.x == de.x) {
                        return SchematicValidationResult.successDeadEnd(Direction.SOUTH, trackY, length, track.x);
                    }
                }
            }
        }

        List<StartPosition> startStartEdge = startPositions.stream().filter(p -> p.z == 0).toList();
        List<StartPosition> startEndEdge = startPositions.stream().filter(p -> p.z == length - 1).toList();

        if (!trackStartEdge.isEmpty() && !startEndEdge.isEmpty()) {
            for (TrackPosition track : trackStartEdge) {
                for (StartPosition sp : startEndEdge) {
                    if (track.x == sp.x) {
                        return SchematicValidationResult.successStart(Direction.SOUTH, trackY, length, track.x, sp.facing);
                    }
                }
            }
        }
        if (!trackEndEdge.isEmpty() && !startStartEdge.isEmpty()) {
            for (TrackPosition track : trackEndEdge) {
                for (StartPosition sp : startStartEdge) {
                    if (track.x == sp.x) {
                        return SchematicValidationResult.successStart(Direction.SOUTH, trackY, length, track.x, sp.facing);
                    }
                }
            }
        }

        return SchematicValidationResult.failure(
                String.format("Track must have blocks on both Z edges (z=0 and z=%d), or track on one edge and a dead end/start on the other", length - 1));
    }

    private static SchematicValidationResult validateXAxisTrack(List<TrackPosition> trackPositions,
                                                                 List<TrackPosition> deadEndPositions,
                                                                 List<StartPosition> startPositions,
                                                                 int width, int trackY) {
        List<TrackPosition> trackStartEdge = trackPositions.stream().filter(p -> p.x == 0).toList();
        List<TrackPosition> trackEndEdge = trackPositions.stream().filter(p -> p.x == width - 1).toList();

        if (!trackStartEdge.isEmpty() && !trackEndEdge.isEmpty()) {
            for (TrackPosition start : trackStartEdge) {
                for (TrackPosition end : trackEndEdge) {
                    if (start.z == end.z) {
                        return SchematicValidationResult.success(Direction.EAST, trackY, width, start.z);
                    }
                }
            }
            return SchematicValidationResult.failure("No matching perpendicular offset (Z) between start and end edge track blocks");
        }

        List<TrackPosition> deadEndStartEdge = deadEndPositions.stream().filter(p -> p.x == 0).toList();
        List<TrackPosition> deadEndEndEdge = deadEndPositions.stream().filter(p -> p.x == width - 1).toList();

        if (!trackStartEdge.isEmpty() && !deadEndEndEdge.isEmpty()) {
            for (TrackPosition track : trackStartEdge) {
                for (TrackPosition de : deadEndEndEdge) {
                    if (track.z == de.z) {
                        return SchematicValidationResult.successDeadEnd(Direction.EAST, trackY, width, track.z);
                    }
                }
            }
        }
        if (!trackEndEdge.isEmpty() && !deadEndStartEdge.isEmpty()) {
            for (TrackPosition track : trackEndEdge) {
                for (TrackPosition de : deadEndStartEdge) {
                    if (track.z == de.z) {
                        return SchematicValidationResult.successDeadEnd(Direction.EAST, trackY, width, track.z);
                    }
                }
            }
        }

        List<StartPosition> startStartEdge = startPositions.stream().filter(p -> p.x == 0).toList();
        List<StartPosition> startEndEdge = startPositions.stream().filter(p -> p.x == width - 1).toList();

        if (!trackStartEdge.isEmpty() && !startEndEdge.isEmpty()) {
            for (TrackPosition track : trackStartEdge) {
                for (StartPosition sp : startEndEdge) {
                    if (track.z == sp.z) {
                        return SchematicValidationResult.successStart(Direction.EAST, trackY, width, track.z, sp.facing);
                    }
                }
            }
        }
        if (!trackEndEdge.isEmpty() && !startStartEdge.isEmpty()) {
            for (TrackPosition track : trackEndEdge) {
                for (StartPosition sp : startStartEdge) {
                    if (track.z == sp.z) {
                        return SchematicValidationResult.successStart(Direction.EAST, trackY, width, track.z, sp.facing);
                    }
                }
            }
        }

        return SchematicValidationResult.failure(
                String.format("Track must have blocks on both X edges (x=0 and x=%d), or track on one edge and a dead end/start on the other", width - 1));
    }

    private static List<TrackPosition> findAllTrackBlocks(NbtSchematicLoader.LoadedSchematic schematic) {
        List<TrackPosition> tracks = new ArrayList<>();
        for (int y = 0; y < schematic.getHeight(); y++) {
            for (int z = 0; z < schematic.getLength(); z++) {
                for (int x = 0; x < schematic.getWidth(); x++) {
                    BlockState state = schematic.getBlock(x, y, z);
                    if (CreateTrackUtil.isTrackBlock(state)) {
                        tracks.add(new TrackPosition(x, y, z));
                    }
                }
            }
        }
        return tracks;
    }

    private static List<StartPosition> findAllStartBlocks(NbtSchematicLoader.LoadedSchematic schematic) {
        List<StartPosition> starts = new ArrayList<>();
        for (int y = 0; y < schematic.getHeight(); y++) {
            for (int z = 0; z < schematic.getLength(); z++) {
                for (int x = 0; x < schematic.getWidth(); x++) {
                    BlockState state = schematic.getBlock(x, y, z);
                    if (state.getBlock() instanceof StartBlock) {
                        Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
                        starts.add(new StartPosition(x, y, z, facing));
                    }
                }
            }
        }
        return starts;
    }

    private static List<TrackPosition> findAllDeadEndBlocks(NbtSchematicLoader.LoadedSchematic schematic) {
        List<TrackPosition> deadEnds = new ArrayList<>();
        for (int y = 0; y < schematic.getHeight(); y++) {
            for (int z = 0; z < schematic.getLength(); z++) {
                for (int x = 0; x < schematic.getWidth(); x++) {
                    BlockState state = schematic.getBlock(x, y, z);
                    if (state.getBlock() instanceof DeadEndBlock) {
                        deadEnds.add(new TrackPosition(x, y, z));
                    }
                }
            }
        }
        return deadEnds;
    }

}
