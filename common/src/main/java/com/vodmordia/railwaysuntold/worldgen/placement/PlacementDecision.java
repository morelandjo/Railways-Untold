package com.vodmordia.railwaysuntold.worldgen.placement;

import com.vodmordia.railwaysuntold.util.spatial.DiagonalDirection;
import com.vodmordia.railwaysuntold.worldgen.placement.decider.CurveParameterDecider;
import com.vodmordia.railwaysuntold.worldgen.terrain.TerrainScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable result of terrain analysis indicating what type of track to place.
*/
public class PlacementDecision {

    private static final int DEFAULT_ELEVATION_CALC_DISTANCE = 30;

    public enum Type {BEZIER, STRAIGHT, CURVE, INVALID, TUNNEL, BRIDGE, BRANCH, STATION, ELEVATED_TUNNEL, DEFER, SCURVE_45, EVENT, DIAGONAL_ENTRY, DIAGONAL_STRAIGHT, DIAGONAL_EXIT}

    private final Type type;
    private final BlockPos start;
    private final BlockPos end;
    private final int distance;
    private final int elevationChange;
    private final CurveParameterDecider.CurveParameters curveParams;
    private final boolean isEmergencyCurve;
    private final Direction direction;
    private final TerrainScanner.TerrainScan scan;
    private final Direction branchDirection;
    private final UUID branchTargetVillageId;
    private final BlockPos branchTargetVillageCenter;
    private final int scurve45Radius;
    private final int scurve45DiagonalLength;
    private final boolean scurve45ShiftLeft;
    private final DiagonalDirection diagonalDirection;
    private final Direction exitCardinalDirection;
    private final PlacementLogContext logContext;
    private final Set<ChunkPos> deferChunks;

    public Type getType() {
        return type;
    }

    public BlockPos getStart() {
        return start;
    }

    public BlockPos getEnd() {
        return end;
    }

    public int getDistance() {
        return distance;
    }

    public int getElevationChange() {
        return elevationChange;
    }

    public CurveParameterDecider.CurveParameters getCurveParams() {
        return curveParams;
    }

    public boolean isEmergencyCurve() {
        return isEmergencyCurve;
    }

    public Direction getDirection() {
        return direction;
    }

    public TerrainScanner.TerrainScan getScan() {
        return scan;
    }

    public Direction getBranchDirection() {
        return branchDirection;
    }

    public UUID getBranchTargetVillageId() {
        return branchTargetVillageId;
    }

    public BlockPos getBranchTargetVillageCenter() {
        return branchTargetVillageCenter;
    }

    public int getScurve45Radius() {
        return scurve45Radius;
    }

    public int getScurve45DiagonalLength() {
        return scurve45DiagonalLength;
    }

    public boolean isScurve45ShiftLeft() {
        return scurve45ShiftLeft;
    }

    public DiagonalDirection getDiagonalDirection() {
        return diagonalDirection;
    }

    public Direction getExitCardinalDirection() {
        return exitCardinalDirection;
    }

    public PlacementLogContext getLogContext() {
        return logContext;
    }

    public Set<ChunkPos> getDeferChunks() {
        return deferChunks;
    }

    public static class Builder {
        private Type type;
        private BlockPos start;
        private BlockPos end;
        private int distance;
        private int elevationChange;
        private CurveParameterDecider.CurveParameters curveParams;
        private boolean isEmergencyCurve;
        private Direction direction;
        private TerrainScanner.TerrainScan scan;
        private Direction branchDirection;
        private UUID branchTargetVillageId;
        private BlockPos branchTargetVillageCenter;
        private int scurve45Radius;
        private int scurve45DiagonalLength;
        private boolean scurve45ShiftLeft;
        private DiagonalDirection diagonalDirection;
        private Direction exitCardinalDirection;
        private PlacementLogContext logContext;
        private Set<ChunkPos> deferChunks;

        private Builder(Type type) {
            this.type = Objects.requireNonNull(type, "type cannot be null");
        }

        public Builder start(BlockPos start) {
            this.start = start;
            return this;
        }

        public Builder end(BlockPos end) {
            this.end = end;
            return this;
        }

        public Builder distance(int distance) {
            this.distance = distance;
            return this;
        }

        public Builder elevationChange(int elevationChange) {
            this.elevationChange = elevationChange;
            return this;
        }

        public Builder curveParams(CurveParameterDecider.CurveParameters curveParams) {
            this.curveParams = curveParams;
            return this;
        }

        public Builder emergencyCurve(boolean isEmergencyCurve) {
            this.isEmergencyCurve = isEmergencyCurve;
            return this;
        }

        public Builder direction(Direction direction) {
            this.direction = direction;
            return this;
        }

        public Builder scan(TerrainScanner.TerrainScan scan) {
            this.scan = scan;
            return this;
        }

        public Builder branchDirection(Direction branchDirection) {
            this.branchDirection = branchDirection;
            return this;
        }

        public Builder branchTargetVillage(UUID villageId, BlockPos villageCenter) {
            this.branchTargetVillageId = villageId;
            this.branchTargetVillageCenter = villageCenter;
            return this;
        }

        public Builder scurve45Params(int radius, int diagonalLength, boolean shiftLeft) {
            this.scurve45Radius = radius;
            this.scurve45DiagonalLength = diagonalLength;
            this.scurve45ShiftLeft = shiftLeft;
            return this;
        }

        public Builder diagonalDirection(DiagonalDirection diagonalDirection) {
            this.diagonalDirection = diagonalDirection;
            return this;
        }

        public Builder exitCardinalDirection(Direction exitCardinalDirection) {
            this.exitCardinalDirection = exitCardinalDirection;
            return this;
        }

        public Builder logContext(PlacementLogContext logContext) {
            this.logContext = logContext;
            return this;
        }

        public Builder deferChunks(Set<ChunkPos> deferChunks) {
            this.deferChunks = deferChunks;
            return this;
        }

        public PlacementDecision build() {
            return new PlacementDecision(this);
        }
    }

    private PlacementDecision(Builder b) {
        this.type = b.type;
        this.start = b.start;
        this.end = b.end;
        this.distance = b.distance;
        this.elevationChange = b.elevationChange;
        this.curveParams = b.curveParams;
        this.isEmergencyCurve = b.isEmergencyCurve;
        this.direction = b.direction;
        this.scan = b.scan;
        this.branchDirection = b.branchDirection;
        this.branchTargetVillageId = b.branchTargetVillageId;
        this.branchTargetVillageCenter = b.branchTargetVillageCenter;
        this.scurve45Radius = b.scurve45Radius;
        this.scurve45DiagonalLength = b.scurve45DiagonalLength;
        this.scurve45ShiftLeft = b.scurve45ShiftLeft;
        this.diagonalDirection = b.diagonalDirection;
        this.exitCardinalDirection = b.exitCardinalDirection;
        this.logContext = b.logContext;
        this.deferChunks = b.deferChunks;
    }

    /**
     * Creates a bezier for station approaches with auto-computed distance and elevation.
     */
    public static PlacementDecision bezierWithExemption(BlockPos start, BlockPos end, Direction startDirection) {
        Objects.requireNonNull(start, "start position cannot be null");
        Objects.requireNonNull(end, "end position cannot be null");
        Objects.requireNonNull(startDirection, "direction cannot be null");
        int horizDist = Math.abs(end.getX() - start.getX()) + Math.abs(end.getZ() - start.getZ());
        int elevation = end.getY() - start.getY();
        return new Builder(Type.BEZIER)
                .start(start)
                .end(end)
                .distance(horizDist)
                .elevationChange(elevation)
                .direction(startDirection)
                .build();
    }

    public static PlacementDecision straight(BlockPos start, int distance) {
        Objects.requireNonNull(start, "start position cannot be null for straight");
        if (distance <= 0) {
            throw new IllegalArgumentException("distance must be positive for straight track");
        }
        return new Builder(Type.STRAIGHT)
                .start(start)
                .distance(distance)
                .build();
    }

    public static PlacementDecision curve(CurveParameterDecider.CurveParameters params, TerrainScanner.TerrainScan scan) {
        return new Builder(Type.CURVE)
                .curveParams(params)
                .start(params != null ? params.start : null)
                .end(params != null ? params.end : null)
                .scan(scan)
                .build();
    }

    public static PlacementDecision invalid() {
        return new Builder(Type.INVALID).build();
    }

    public static PlacementDecision tunnel(BlockPos start, Direction direction, TerrainScanner.TerrainScan scan) {
        Objects.requireNonNull(start, "start position cannot be null for tunnel");
        Objects.requireNonNull(direction, "direction cannot be null for tunnel");
        return new Builder(Type.TUNNEL)
                .start(start)
                .direction(direction)
                .scan(scan)
                .build();
    }

    public static PlacementDecision bridge(BlockPos start, BlockPos end, Direction direction, TerrainScanner.TerrainScan scan) {
        Objects.requireNonNull(start, "start position cannot be null for bridge");
        Objects.requireNonNull(direction, "direction cannot be null for bridge");
        return new Builder(Type.BRIDGE)
                .start(start)
                .end(end)
                .direction(direction)
                .scan(scan)
                .build();
    }

    public static PlacementDecision branch(BlockPos start, int distance, Direction currentDirection, Direction branchDirection, TerrainScanner.TerrainScan scan) {
        Objects.requireNonNull(start, "start position cannot be null for branch");
        Objects.requireNonNull(currentDirection, "current direction cannot be null for branch");
        Objects.requireNonNull(branchDirection, "branch direction cannot be null for branch");
        if (distance <= 0) {
            throw new IllegalArgumentException("distance must be positive for branch");
        }
        return new Builder(Type.BRANCH)
                .start(start)
                .end(start.relative(currentDirection, distance))
                .distance(distance)
                .direction(currentDirection)
                .branchDirection(branchDirection)
                .scan(scan)
                .build();
    }

    public static PlacementDecision elevatedTunnel(BlockPos start, BlockPos end, int distance, int elevationChange,
                                                   Direction direction, TerrainScanner.TerrainScan scan) {
        Objects.requireNonNull(start, "start position cannot be null for elevated tunnel");
        Objects.requireNonNull(end, "end position cannot be null for elevated tunnel");
        Objects.requireNonNull(direction, "direction cannot be null for elevated tunnel");
        return new Builder(Type.ELEVATED_TUNNEL)
                .start(start)
                .end(end)
                .distance(distance)
                .elevationChange(elevationChange)
                .direction(direction)
                .scan(scan)
                .build();
    }

    public static PlacementDecision event(BlockPos start, Direction direction) {
        Objects.requireNonNull(start, "start position cannot be null for event");
        Objects.requireNonNull(direction, "direction cannot be null for event");
        return new Builder(Type.EVENT)
                .start(start)
                .direction(direction)
                .build();
    }

    public static PlacementDecision defer() {
        return new Builder(Type.DEFER).build();
    }

    public static PlacementDecision deferForChunks(Set<ChunkPos> chunks) {
        Objects.requireNonNull(chunks, "chunks cannot be null for deferForChunks");
        return new Builder(Type.DEFER)
                .deferChunks(chunks)
                .build();
    }

    public static PlacementDecision scurve45(BlockPos start, Direction direction,
                                             int radius, int diagonalLength,
                                             boolean shiftLeft, int elevationChange,
                                             TerrainScanner.TerrainScan scan) {
        Objects.requireNonNull(start, "start position cannot be null for scurve45");
        Objects.requireNonNull(direction, "direction cannot be null for scurve45");
        if (radius <= 0) {
            throw new IllegalArgumentException("radius must be positive for scurve45");
        }
        BlockPos end = com.vodmordia.railwaysuntold.util.placement.SCurve45Geometry.calculateEndpoint(
                start, direction, shiftLeft, radius, diagonalLength, elevationChange);
        return new Builder(Type.SCURVE_45)
                .start(start)
                .end(end)
                .direction(direction)
                .scurve45Params(radius, diagonalLength, shiftLeft)
                .elevationChange(elevationChange)
                .scan(scan)
                .build();
    }

    public static PlacementDecision diagonalEntry(BlockPos start, Direction cardinalDir,
                                                    DiagonalDirection diagonal, int radius,
                                                    int elevationChange, TerrainScanner.TerrainScan scan) {
        Objects.requireNonNull(start, "start position cannot be null for diagonal entry");
        Objects.requireNonNull(cardinalDir, "cardinal direction cannot be null for diagonal entry");
        Objects.requireNonNull(diagonal, "diagonal direction cannot be null for diagonal entry");
        boolean shiftLeft = diagonal.isLeftTurnFrom(cardinalDir);
        BlockPos end = com.vodmordia.railwaysuntold.util.placement.SCurve45Geometry.calculateFirstCurveEnd(
                start, cardinalDir, shiftLeft, radius, elevationChange);
        return new Builder(Type.DIAGONAL_ENTRY)
                .start(start)
                .end(end)
                .direction(cardinalDir)
                .diagonalDirection(diagonal)
                .scurve45Params(radius, 0, shiftLeft)
                .elevationChange(elevationChange)
                .scan(scan)
                .build();
    }

    public static PlacementDecision diagonalStraight(BlockPos start, DiagonalDirection diagonal,
                                                      int distance, int elevationChange,
                                                      TerrainScanner.TerrainScan scan) {
        Objects.requireNonNull(start, "start position cannot be null for diagonal straight");
        Objects.requireNonNull(diagonal, "diagonal direction cannot be null for diagonal straight");
        BlockPos end = start.offset(
                diagonal.getStepX() * distance, elevationChange, diagonal.getStepZ() * distance);
        return new Builder(Type.DIAGONAL_STRAIGHT)
                .start(start)
                .end(end)
                .distance(distance)
                .diagonalDirection(diagonal)
                .elevationChange(elevationChange)
                .scan(scan)
                .build();
    }

    public static PlacementDecision diagonalExit(BlockPos start, DiagonalDirection diagonal,
                                                   Direction exitCardinal, int radius,
                                                   int elevationChange, TerrainScanner.TerrainScan scan) {
        Objects.requireNonNull(start, "start position cannot be null for diagonal exit");
        Objects.requireNonNull(diagonal, "diagonal direction cannot be null for diagonal exit");
        Objects.requireNonNull(exitCardinal, "exit cardinal cannot be null for diagonal exit");
        // A diagonal exit is geometrically a single 45° curve from diagonal to cardinal.
        // The lateral shift is toward the side the diagonal came from.
        boolean shiftLeft = diagonal.isLeftTurnFrom(exitCardinal);
        BlockPos end = com.vodmordia.railwaysuntold.util.placement.SCurve45Geometry.calculateFirstCurveEnd(
                start, exitCardinal, shiftLeft, radius, elevationChange);
        return new Builder(Type.DIAGONAL_EXIT)
                .start(start)
                .end(end)
                .direction(exitCardinal)
                .diagonalDirection(diagonal)
                .exitCardinalDirection(exitCardinal)
                .scurve45Params(radius, 0, shiftLeft)
                .elevationChange(elevationChange)
                .scan(scan)
                .build();
    }

    /**
     * Creates a copy of this decision with the specified log context.
     *
     * @param context The logging context
     * @return A new decision with the log context set
     */
    public PlacementDecision withLogContext(PlacementLogContext context) {
        return new Builder(this.type)
                .start(this.start)
                .end(this.end)
                .distance(this.distance)
                .elevationChange(this.elevationChange)
                .curveParams(this.curveParams)
                .emergencyCurve(this.isEmergencyCurve)
                .direction(this.direction)
                .scan(this.scan)
                .branchDirection(this.branchDirection)
                .branchTargetVillage(this.branchTargetVillageId, this.branchTargetVillageCenter)
                .scurve45Params(this.scurve45Radius, this.scurve45DiagonalLength, this.scurve45ShiftLeft)
                .diagonalDirection(this.diagonalDirection)
                .exitCardinalDirection(this.exitCardinalDirection)
                .deferChunks(this.deferChunks)
                .logContext(context)
                .build();
    }

    /**
     * Creates a copy of this decision with branch target village information.
     *
     * @param villageId     The village ID
     * @param villageCenter The village center position
     * @return A new decision with the village target set
     */
    public PlacementDecision withBranchTargetVillage(UUID villageId, BlockPos villageCenter) {
        return new Builder(this.type)
                .start(this.start)
                .end(this.end)
                .distance(this.distance)
                .elevationChange(this.elevationChange)
                .curveParams(this.curveParams)
                .emergencyCurve(this.isEmergencyCurve)
                .direction(this.direction)
                .scan(this.scan)
                .branchDirection(this.branchDirection)
                .branchTargetVillage(villageId, villageCenter)
                .scurve45Params(this.scurve45Radius, this.scurve45DiagonalLength, this.scurve45ShiftLeft)
                .diagonalDirection(this.diagonalDirection)
                .exitCardinalDirection(this.exitCardinalDirection)
                .deferChunks(this.deferChunks)
                .logContext(this.logContext)
                .build();
    }

}
