package com.vodmordia.railwaysuntold.worldgen.placement.handler;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.worldgen.head.TrackExpansionHead;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.BezierSegmentExtractor;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.BezierSegmentExtractor.BezierSegmentData;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementConstants;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.BezierConnectionPlacer;
import com.vodmordia.railwaysuntold.worldgen.placement.support.BridgeSchematicPlacer;
import com.vodmordia.railwaysuntold.worldgen.placement.support.TerrainFillPlacer;
import com.vodmordia.railwaysuntold.worldgen.placement.support.WaterBridgeDetector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for all track support placement logic.
 * Automatically detects water below track and uses bridge schematics (decking + pillars)
 * instead of regular ground supports when track is over water.
 */
public class SupportPlacementService {

    /**
     * Places ground supports along a straight track section.
     */
    public static void placeSupportsAlongStraight(PlacementContext ctx, BlockPos startPos,
                                                  Direction direction, int distance) {
        placeSupportsAlongStraight(ctx, startPos, direction, distance, null, null);
    }

    /**
     * Places ground supports along a straight track section near a branch junction.
     * The branch side of the decking uses base blocks instead of end walls so the
     * branch child's decking connects smoothly.
     */
    public static void placeSupportsAlongStraight(PlacementContext ctx, BlockPos startPos,
                                                  Direction direction, int distance,
                                                  @Nullable BlockPos junctionPos,
                                                  @Nullable Direction branchDir) {
        Vec3 straightTangent = Vec3.atLowerCornerOf(direction.getNormal());
        BridgeSchematicPlacer bridgePlacer = new BridgeSchematicPlacer();
        bridgePlacer.setRailingCounter(ctx.head().getBridgePillarCounter());
        if (junctionPos != null && branchDir != null) {
            bridgePlacer.addJunctionZone(junctionPos, 3, direction, branchDir);
            bridgePlacer.addBranchSideZone(junctionPos, direction, branchDir, distance);
            bridgePlacer.addPillarExclusion(junctionPos, JUNCTION_PILLAR_CLEARANCE_RADIUS);
        }
        applyRecentBranchJunction(ctx, bridgePlacer, junctionPos);
        boolean usedBridge = false;

        // Buffer bridge positions and gaps so we can enforce a minimum bridge run length.
        // Only commit to bridge decking when we have MIN_BRIDGE_RUN_LENGTH qualifying positions.
        List<BlockPos> bridgeRun = new ArrayList<>();
        List<BlockPos> pendingGap = new ArrayList<>();
        int bridgeQualifyingCount = 0;

        // Interpolate Y across the run when the head has advanced to a different elevation.
        int startY = startPos.getY();
        int endY = startY;
        if (distance > 0) {
            BlockPos headEnd = ctx.head().getPosition();
            BlockPos expectedEnd = startPos.relative(direction, distance);
            if (headEnd.getX() == expectedEnd.getX() && headEnd.getZ() == expectedEnd.getZ()) {
                endY = headEnd.getY();
            }
        }
        int dy = endY - startY;

        for (int blockOffset = 0; blockOffset < distance; blockOffset++) {
            BlockPos trackBlockPos = startPos.relative(direction, blockOffset);
            if (dy != 0) {
                int interpY = startY + (int) Math.round((double) dy * blockOffset / distance);
                trackBlockPos = new BlockPos(trackBlockPos.getX(), interpY, trackBlockPos.getZ());
            }

            if (shouldUseBridgeDecking(ctx.level(), trackBlockPos)) {
                // Absorb any buffered gap positions into the bridge run
                // (includes leading gaps from before first bridge position)
                if (!pendingGap.isEmpty()) {
                    bridgeRun.addAll(pendingGap);
                }
                pendingGap.clear();
                bridgeRun.add(trackBlockPos);
                bridgeQualifyingCount++;
            } else if (pendingGap.size() < BRIDGE_GAP_ABSORB_LIMIT) {
                // Buffer this non-bridge position for potential gap absorption
                pendingGap.add(trackBlockPos);
            } else {
                // Gap too long or no prior bridge - flush bridge run + gap
                if (flushStraightBridgeRun(ctx, bridgeRun, bridgeQualifyingCount, pendingGap, straightTangent, bridgePlacer)) {
                    usedBridge = true;
                }
                bridgeRun.clear();
                bridgeQualifyingCount = 0;
                pendingGap.clear();
                placeSupportIfNeeded(ctx, trackBlockPos, straightTangent, bridgePlacer);
            }
        }

        // Flush any remaining bridge run + gap at end of segment
        if (flushStraightBridgeRun(ctx, bridgeRun, bridgeQualifyingCount, pendingGap, straightTangent, bridgePlacer)) {
            usedBridge = true;
        }

        if (usedBridge) {
            placeCollectedPiers(ctx, bridgePlacer);
            bridgePlacer.updateConnections(ctx.level());
        }
    }

    /** Places the piers the placer collected at railing-pattern boundaries during decking. */
    private static void placeCollectedPiers(PlacementContext ctx, BridgeSchematicPlacer bridgePlacer) {
        for (Vec3[] pier : bridgePlacer.getPillarBoundaries()) {
            bridgePlacer.placePillarAt(ctx.level(), pier[0], pier[1]);
        }
    }

    /**
     * Places ground supports along a bezier curve using pre-extracted segments.
     */
    public static void placeSupportsAlongBezier(PlacementContext ctx,
                                                BezierConnectionPlacer.BezierConnectionResult bezierResult,
                                                BlockPos startPos) {
        placeSupportsForSegments(ctx, bezierResult.segments, startPos, ctx.head().getPosition(), null, null, null, null);
    }

    /**
     * Places ground supports along a bezier curve with an explicit endpoint for gap-filling.
     */
    public static void placeSupportsAlongBezier(PlacementContext ctx,
                                                BezierConnectionPlacer.BezierConnectionResult bezierResult,
                                                BlockPos startPos, BlockPos endPos) {
        placeSupportsForSegments(ctx, bezierResult.segments, startPos, endPos, null, null, null, null);
    }

    /**
     * Places ground supports along a bezier curve with an explicit endpoint and parent track
     * exclusion zone. The parent track corridor around the junction is added to the track
     * footprint so branch curve decking doesn't overlap the parent track.
     */
    public static void placeSupportsAlongBezier(PlacementContext ctx,
                                                BezierConnectionPlacer.BezierConnectionResult bezierResult,
                                                BlockPos startPos, BlockPos endPos,
                                                BlockPos parentExclPos, Direction parentExclDir) {
        placeSupportsForSegments(ctx, bezierResult.segments, startPos, endPos, parentExclPos, parentExclDir, null, null);
    }

    /**
     * Places ground supports along a bezier curve near a branch junction.
     * The branch side of the decking uses base blocks instead of end walls so the
     * branch child's decking connects smoothly.
     */
    public static void placeSupportsAlongBezierNearJunction(PlacementContext ctx,
                                                BezierConnectionPlacer.BezierConnectionResult bezierResult,
                                                BlockPos startPos, BlockPos endPos,
                                                BlockPos junctionPos, Direction branchDir) {
        placeSupportsForSegments(ctx, bezierResult.segments, startPos, endPos, null, null, junctionPos, branchDir);
    }

    /**
     * Fills the deck "gore" - the wedge between the parent track and a diverging branch - so the fork
     * is solid deck rather than an open notch. Call after the branch curve's decking is placed; no-op
     * on ground-level forks (only fills genuine holes). See {@link BridgeSchematicPlacer#fillJunctionGore}.
     */
    public static void fillBranchGore(PlacementContext ctx,
                                      BezierConnectionPlacer.BezierConnectionResult curve,
                                      BlockPos curveStart, Direction parentDir, Direction branchDir) {
        if (curve == null || curve.segments == null || curve.segments.isEmpty()) {
            return;
        }
        BridgeSchematicPlacer placer = new BridgeSchematicPlacer();
        placer.buildTrackFootprint(curve.segments, Vec3.atLowerCornerOf(curveStart));
        placer.fillJunctionGore(ctx.level(), curveStart, parentDir, branchDir);
        placer.updateConnections(ctx.level());
    }

    /**
     * Places ground supports along a raw bezier connection (Object from SCurve45TrackPlacer).
     */
    public static void placeSupportsAlongRawBezier(PlacementContext ctx,
                                                    Object bezierConnection,
                                                    BlockPos startPos) {
        placeSupportsAlongRawBezier(ctx, bezierConnection, startPos, null);
    }

    /**
     * As above, but with the merge point where the bezier joins existing track. When non-null, railing
     * tops are suppressed radially and pillars kept clear there, so the convergence has no clashing rails.
     */
    public static void placeSupportsAlongRawBezier(PlacementContext ctx,
                                                    Object bezierConnection,
                                                    BlockPos startPos, @Nullable BlockPos mergePoint) {
        List<BezierSegmentData> segments = BezierSegmentExtractor.extractSegments(bezierConnection);
        placeSupportsForSegments(ctx, segments, startPos, ctx.head().getPosition(), null, null, mergePoint, null);
    }

    /**
     * Builds bridge decking and supports under a MERGE connection (a junction terminate or parallel
     * converge). The merge path joins two existing track endpoints with a raw Create bezier via
     * {@code CreateTrackConnector.connectTracks} and never ran the support/bridge pass that normal
     * placement does, so a merge spanning a valley left the rail floating in the air. This reuses the
     * same segment-driven support placement (bridge decking over voids/water, pillars elsewhere) with a
     * minimal context - only {@code level}/{@code head}/{@code expandDir} are read along this path.
     */
    public static void placeMergeSupports(ServerLevel level, TrackExpansionHead head, BlockPos tip,
                                          Object bezierConnection, BlockPos mergePoint) {
        if (bezierConnection == null || head == null) {
            return;
        }
        PlacementContext ctx = new PlacementContext(
                level, head, null, null, tip, head.getDirection(),
                new RailwaysUntoldConfig(), null, null, null, null, null, null);
        placeSupportsAlongRawBezier(ctx, bezierConnection, tip, mergePoint);
    }

    private static void placeSupportsForSegments(PlacementContext ctx,
                                                  List<BezierSegmentData> segments,
                                                  BlockPos startPos, BlockPos endPos,
                                                  @Nullable BlockPos parentExclPos, @Nullable Direction parentExclDir,
                                                  @Nullable BlockPos junctionPos, @Nullable Direction branchDir) {
        if (segments.isEmpty()) {
            return;
        }

        Vec3 origin = Vec3.atLowerCornerOf(startPos);
        BridgeSchematicPlacer bridgePlacer = new BridgeSchematicPlacer();
        bridgePlacer.setRailingCounter(ctx.head().getBridgePillarCounter());
        // Resolve biome settings up front so addStraightTrackExclusion uses the configured half-width.
        bridgePlacer.init(ctx.level(), startPos);
        bridgePlacer.buildTrackFootprint(segments, origin);
        if (parentExclPos != null && parentExclDir != null) {
            bridgePlacer.addStraightTrackExclusion(parentExclPos, parentExclDir, PlacementConstants.STANDARD_SEGMENT_LENGTH);
            bridgePlacer.addPillarExclusion(parentExclPos, JUNCTION_PILLAR_CLEARANCE_RADIUS);
        }
        if (junctionPos != null) {
            if (branchDir != null) {
                bridgePlacer.addJunctionZone(junctionPos, 3, ctx.expandDir(), branchDir);
                bridgePlacer.addBranchSideZone(junctionPos, ctx.expandDir(), branchDir, PlacementConstants.STANDARD_SEGMENT_LENGTH);
            } else {
                // Merge: two decks converge from an unknown relative direction - suppress railing tops radially.
                bridgePlacer.addJunctionZoneRadial(junctionPos, 3);
            }
            bridgePlacer.addPillarExclusion(junctionPos, JUNCTION_PILLAR_CLEARANCE_RADIUS);
        }
        // Only consume the parent head's recent-branch junction on parent continuations.
        if (parentExclPos == null) {
            applyRecentBranchJunction(ctx, bridgePlacer, junctionPos);
        }

        Vec3 fallbackTangent = Vec3.atLowerCornerOf(ctx.expandDir().getNormal());
        Vec3 firstDerivative = segments.get(0).derivative();
        Vec3 startTangent = firstDerivative != null ? firstDerivative : fallbackTangent;
        boolean startIsBridge = shouldUseBridgeDecking(ctx.level(), startPos);
        if (startIsBridge) {
            ctx.head().getBridgePillarCounter().checkAndUpdate(ctx.level(), startPos, startTangent);
        }

        // When the start block is a bridge, defer its decking until the bridge run meets
        // MIN_BRIDGE_RUN_LENGTH; otherwise drop terrain fill immediately and seed the cursor.
        GroundFillCursor fillCursor = startIsBridge
                ? new GroundFillCursor(Integer.MIN_VALUE, startPos.getY(), startPos.getZ(), null)
                : new GroundFillCursor(startPos.getX(), startPos.getY(), startPos.getZ(), startTangent);
        if (!startIsBridge) {
            TerrainFillPlacer.placeTerrainFill(ctx.level(), Vec3.atLowerCornerOf(startPos), startTangent);
        }

        List<BezierSegmentData> pendingGap = new ArrayList<>();
        List<BezierSegmentData> bridgeRun = new ArrayList<>();
        List<Vec3[]> pillarPositions = new ArrayList<>(); // [worldPos, derivative]
        List<Vec3[]> currentRunPillars = new ArrayList<>();
        boolean usedBridge = false;

        for (BezierSegmentData segment : segments) {
            Vec3 worldPos = segment.position().add(origin);
            BlockPos trackPos = BlockPos.containing(worldPos);

            Vec3 derivative = segment.derivative();
            Vec3 normal = segment.normal();
            if (derivative == null) {
                derivative = fallbackTangent;
            }
            if (normal == null) {
                normal = derivative.cross(new Vec3(0, 1, 0)).normalize();
            }

            if (shouldUseBridgeDecking(ctx.level(), trackPos)) {
                processBridgeSegment(ctx, segment, worldPos, derivative, origin,
                        pendingGap, bridgeRun, currentRunPillars, fillCursor);
            } else if (pendingGap.size() < BRIDGE_GAP_ABSORB_LIMIT) {
                pendingGap.add(segment);
            } else {
                // Gap too long - flush the bridge run (if any) and place this segment as ground.
                if (!bridgeRun.isEmpty()) {
                    if (flushBridgeRun(ctx, bridgePlacer, bridgeRun, pendingGap,
                            currentRunPillars, pillarPositions, origin,
                            startIsBridge, startPos, startTangent)) {
                        usedBridge = true;
                    }
                    startIsBridge = false;
                }
                flushSegmentsAsGround(ctx, pendingGap, origin);
                pendingGap.clear();
                placeGroundSegmentWithTerrainFill(ctx, worldPos, trackPos, derivative, normal, fillCursor);
            }
        }

        // Flush final bridge run with minimum length check.
        if (!bridgeRun.isEmpty()) {
            if (flushBridgeRun(ctx, bridgePlacer, bridgeRun, pendingGap,
                    currentRunPillars, pillarPositions, origin,
                    startIsBridge, startPos, startTangent)) {
                usedBridge = true;
            }
        } else if (startIsBridge) {
            // Start block was a bridge but no bridge run formed - fall back to terrain fill.
            TerrainFillPlacer.placeTerrainFill(ctx.level(), Vec3.atLowerCornerOf(startPos), startTangent);
        }

        flushSegmentsAsGround(ctx, pendingGap, origin);

        // Piers are placed after decking, at the railing-pattern boundaries collected by the placer.
        placeCollectedPiers(ctx, bridgePlacer);

        finalizeEndpoint(ctx, bridgePlacer, usedBridge, endPos, origin, segments);
    }

    /**
     * Places support at a track position. Returns true if bridge schematics were used.
     */
    private static boolean placeSupportIfNeeded(PlacementContext ctx, BlockPos trackPos,
                                                Vec3 tangent, BridgeSchematicPlacer bridgePlacer) {
        if (shouldUseBridgeDecking(ctx.level(), trackPos)) {
            bridgePlacer.placeDeckingAt(ctx.level(), trackPos, tangent);
            return true;
        } else {
            TerrainFillPlacer.placeTerrainFill(ctx.level(), trackPos, tangent);
            return false;
        }
    }

    /**
     * Places bridge-specific supports (decking + pillars) along a bezier curve.
     */
    public static void placeBridgeSupportsAlongBezier(PlacementContext ctx,
                                                       BezierConnectionPlacer.BezierConnectionResult bezierResult,
                                                       BlockPos startPos) {
        if (bezierResult.segments.isEmpty()) {
            return;
        }

        Vec3 origin = Vec3.atLowerCornerOf(startPos);
        Vec3 fallbackTangent = Vec3.atLowerCornerOf(ctx.expandDir().getNormal());
        BridgeSchematicPlacer bridgePlacer = new BridgeSchematicPlacer();
        bridgePlacer.setRailingCounter(ctx.head().getBridgePillarCounter());
        bridgePlacer.buildTrackFootprint(bezierResult.segments, origin);

        // Use the first segment's derivative for start decking tangent
        Vec3 firstDerivative = bezierResult.segments.get(0).derivative();
        Vec3 startTangent = firstDerivative != null ? firstDerivative : fallbackTangent;
        bridgePlacer.placeDeckingAt(ctx.level(), startPos, startTangent);

        // Batch all segments for curve-aware decking placement
        bridgePlacer.placeDeckingAlongCurve(ctx.level(), bezierResult.segments, origin);

        // Place decking at the endpoint
        BlockPos endPos = ctx.head().getPosition();
        Vec3 lastDerivative = bezierResult.segments.get(bezierResult.segments.size() - 1).derivative();
        Vec3 endTangent = lastDerivative != null ? lastDerivative : fallbackTangent;
        if (!endPos.equals(startPos)) {
            bridgePlacer.placeDeckingAt(ctx.level(), endPos, endTangent);
        }

        // Piers are placed after all decking, at the railing-pattern boundaries collected by the placer.
        placeCollectedPiers(ctx, bridgePlacer);

        bridgePlacer.updateConnections(ctx.level());
    }

    /**
     * Maximum number of non-bridge segments to buffer when waiting for bridge to resume.
     * Short ground patches within this limit are absorbed into the bridge zone.
     */
    private static final int BRIDGE_GAP_ABSORB_LIMIT = 8;

    /**
     * Minimum number of bridge-qualifying positions required before committing to
     * bridge decking. Runs shorter than this are placed as terrain fill instead.
     */
    private static final int MIN_BRIDGE_RUN_LENGTH = 4;

    /** Radius (XZ) around a junction where pillars/piers are suppressed to keep them out of the fork. */
    public static final int JUNCTION_PILLAR_CLEARANCE_RADIUS = 6;

    /**
     * Flushes a buffered straight bridge run. If the run has enough bridge-qualifying
     * positions, places bridge decking for all positions (including absorbed gaps).
     * Otherwise, falls back to terrain fill for the entire run.
     * Returns true if bridge decking was placed.
     */
    private static boolean flushStraightBridgeRun(PlacementContext ctx, List<BlockPos> bridgeRun,
                                                   int bridgeQualifying, List<BlockPos> pendingGap,
                                                   Vec3 tangent, BridgeSchematicPlacer bridgePlacer) {
        if (bridgeRun.isEmpty()) {
            // Flush any orphaned gap as ground
            for (BlockPos gapPos : pendingGap) {
                placeSupportIfNeeded(ctx, gapPos, tangent, bridgePlacer);
            }
            return false;
        }

        // A run shorter than the minimum normally demotes to terrain fill - but if it sits over a DEEP
        // gap, terrain fill bails (DEEP_GAP) and leaves a hole. Such a run (e.g. a short straight between
        // two diagonal spans over water) must be bridged regardless of length.
        boolean overDeepGap = false;
        for (BlockPos pos : bridgeRun) {
            if (WaterBridgeDetector.isDeepGap(ctx.level(), pos)) { overDeepGap = true; break; }
        }
        if (bridgeQualifying >= MIN_BRIDGE_RUN_LENGTH || overDeepGap) {
            // Commit as bridge decking
            boolean placed = false;
            for (BlockPos pos : bridgeRun) {
                bridgePlacer.placeDeckingAt(ctx.level(), pos, tangent);
                placed = true;
            }
            // Absorb trailing gap into bridge decking to prevent terrain fill
            // from clipping through bridge at the transition
            for (BlockPos gapPos : pendingGap) {
                bridgePlacer.placeDeckingAt(ctx.level(), gapPos, tangent);
            }

            return placed;
        } else {
            // Too short - flush entire run + gap as terrain fill
            for (BlockPos pos : bridgeRun) {
                TerrainFillPlacer.placeTerrainFill(ctx.level(), pos, tangent);
                ctx.head().getBridgePillarCounter().checkAndUpdate(ctx.level(), pos, tangent);
            }
            for (BlockPos gapPos : pendingGap) {
                placeSupportIfNeeded(ctx, gapPos, tangent, bridgePlacer);
            }
            return false;
        }
    }

    /**
     * Handles a bridge-qualifying segment: absorbs any pending gap into the run, appends
     * the segment, tracks a pillar if the counter fires, and resets the ground-fill cursor
     * so the next ground segment can't bridge-fill across the gap we just entered.
     */
    private static void processBridgeSegment(PlacementContext ctx, BezierSegmentData segment,
                                              Vec3 worldPos, Vec3 derivative, Vec3 origin,
                                              List<BezierSegmentData> pendingGap,
                                              List<BezierSegmentData> bridgeRun,
                                              List<Vec3[]> currentRunPillars,
                                              GroundFillCursor fillCursor) {
        fillCursor.reset();

        if (!pendingGap.isEmpty()) {
            for (BezierSegmentData gapSeg : pendingGap) {
                Vec3 gapWorld = gapSeg.position().add(origin);
                Vec3 gapDeriv = gapSeg.derivative();
                if (gapDeriv == null) gapDeriv = derivative;
                ctx.head().getBridgePillarCounter().checkAndUpdate(ctx.level(), gapWorld, gapDeriv);
                bridgeRun.add(gapSeg);
            }
            pendingGap.clear();
        }

        // Advance the counter before decking obscures the air below.
        boolean needsPillar = ctx.head().getBridgePillarCounter().checkAndUpdate(ctx.level(), worldPos, derivative);
        bridgeRun.add(segment);
        if (needsPillar) {
            currentRunPillars.add(new Vec3[]{worldPos, derivative});
        }
    }

    /**
     * Flushes a non-empty bridge run. If the run (including a deferred start block) meets
     * MIN_BRIDGE_RUN_LENGTH, absorbs the trailing pendingGap and places decking; otherwise
     * falls back to terrain fill. Always clears bridgeRun, pendingGap, and currentRunPillars.
     * Returns true iff decking was placed.
     */
    private static boolean flushBridgeRun(PlacementContext ctx, BridgeSchematicPlacer bridgePlacer,
                                           List<BezierSegmentData> bridgeRun,
                                           List<BezierSegmentData> pendingGap,
                                           List<Vec3[]> currentRunPillars,
                                           List<Vec3[]> pillarPositions,
                                           Vec3 origin,
                                           boolean startIsBridge, BlockPos startPos, Vec3 startTangent) {
        int bridgeCount = bridgeRun.size() + (startIsBridge ? 1 : 0);
        // A sub-minimum run over a DEEP gap must still bridge - terrain fill would bail and leave a hole.
        boolean overDeepGap = startIsBridge && WaterBridgeDetector.isDeepGap(ctx.level(), startPos);
        for (int i = 0; !overDeepGap && i < bridgeRun.size(); i++) {
            if (WaterBridgeDetector.isDeepGap(ctx.level(),
                    BlockPos.containing(bridgeRun.get(i).position().add(origin)))) {
                overDeepGap = true;
            }
        }
        boolean placed;
        if (bridgeCount >= MIN_BRIDGE_RUN_LENGTH || overDeepGap) {
            // Absorb trailing gap so terrain fill can't clip through the bridge at the transition.
            bridgeRun.addAll(pendingGap);
            pendingGap.clear();
            if (startIsBridge) {
                bridgePlacer.placeDeckingAt(ctx.level(), startPos, startTangent);
            }
            bridgePlacer.placeDeckingAlongCurve(ctx.level(), bridgeRun, origin);
            pillarPositions.addAll(currentRunPillars);
            placed = true;
        } else {
            if (startIsBridge) {
                TerrainFillPlacer.placeTerrainFill(ctx.level(), Vec3.atLowerCornerOf(startPos), startTangent);
            }
            flushSegmentsAsGround(ctx, bridgeRun, origin);
            placed = false;
        }
        bridgeRun.clear();
        currentRunPillars.clear();
        return placed;
    }

    /**
     * Places a single ground segment: fills the terrain gap since the previous ground position,
     * drops terrain fill at this one, adds a flat/sloped support if the counter fires, and
     * updates the cursor.
     */
    private static void placeGroundSegmentWithTerrainFill(PlacementContext ctx,
                                                           Vec3 worldPos, BlockPos trackPos,
                                                           Vec3 derivative, Vec3 normal,
                                                           GroundFillCursor fillCursor) {
        int currFillX = trackPos.getX(), currFillY = trackPos.getY(), currFillZ = trackPos.getZ();
        if (fillCursor.hasPrev()) {
            TerrainFillPlacer.placeTerrainFillWithGaps(ctx.level(),
                    fillCursor.x, fillCursor.y, fillCursor.z, fillCursor.tangent,
                    currFillX, currFillY, currFillZ, derivative);
        }
        TerrainFillPlacer.placeTerrainFill(ctx.level(), worldPos, derivative);
        fillCursor.set(currFillX, currFillY, currFillZ, derivative);

        ctx.head().getBridgePillarCounter().checkAndUpdate(ctx.level(), worldPos, derivative);
    }

    /**
     * Caps the placement with endpoint decking (bezier sampling often stops 1 block short)
     * and triggers connection updates for bridge and ground supports.
     */
    private static void finalizeEndpoint(PlacementContext ctx, BridgeSchematicPlacer bridgePlacer,
                                          boolean usedBridge, BlockPos endPos, Vec3 origin,
                                          List<BezierSegmentData> segments) {
        if (usedBridge && !endPos.equals(BlockPos.containing(origin))
                && shouldUseBridgeDecking(ctx.level(), endPos)) {
            Vec3 lastDerivative = segments.get(segments.size() - 1).derivative();
            Vec3 endTangent = lastDerivative != null ? lastDerivative
                    : Vec3.atLowerCornerOf(ctx.expandDir().getNormal());
            bridgePlacer.placeDeckingAt(ctx.level(), endPos, endTangent);
        }

        if (usedBridge) {
            bridgePlacer.updateConnections(ctx.level());
        }
    }

    /**
     * Mutable cursor tracking the previous ground-fill position so gap-filling between
     * consecutive ground segments can span any interleaved bridge runs correctly.
     */
    private static final class GroundFillCursor {
        int x, y, z;
        @Nullable Vec3 tangent;

        GroundFillCursor(int x, int y, int z, @Nullable Vec3 tangent) {
            this.x = x; this.y = y; this.z = z; this.tangent = tangent;
        }

        boolean hasPrev() {
            return x != Integer.MIN_VALUE;
        }

        void reset() {
            this.x = Integer.MIN_VALUE;
            this.tangent = null;
        }

        void set(int x, int y, int z, Vec3 tangent) {
            this.x = x; this.y = y; this.z = z; this.tangent = tangent;
        }
    }

    /**
     * Flushes bezier segments as ground supports (terrain fill + pillars).
     * Used when a bridge run is too short or gap segments need ground placement.
     */
    private static void flushSegmentsAsGround(PlacementContext ctx,
                                               List<BezierSegmentData> segments, Vec3 origin) {
        for (BezierSegmentData seg : segments) {
            Vec3 worldPos = seg.position().add(origin);
            Vec3 deriv = seg.derivative();
            Vec3 normal = seg.normal();
            if (deriv == null) deriv = Vec3.atLowerCornerOf(ctx.expandDir().getNormal());
            if (normal == null) normal = deriv.cross(new Vec3(0, 1, 0)).normalize();

            TerrainFillPlacer.placeTerrainFill(ctx.level(), worldPos, deriv);
            ctx.head().getBridgePillarCounter().checkAndUpdate(ctx.level(), worldPos, deriv);
        }
    }

    /**
     * Applies branch-side zone from a recently placed branch junction stored on the head.
     * This extends the end-wall suppression to segments placed after the branch handler,
     * where the branch curve is still close to the parent track's decking edge.
     * Consumes the stored junction info so it's only applied once.
     */
    private static void applyRecentBranchJunction(PlacementContext ctx, BridgeSchematicPlacer bridgePlacer,
                                                   @Nullable BlockPos explicitJunction) {
        if (explicitJunction != null) return; // Already handled by caller
        BlockPos recentJunction = ctx.head().getLastBranchJunctionPos();
        Direction recentBranchDir = ctx.head().getLastBranchDirection();
        if (recentJunction != null && recentBranchDir != null) {
            bridgePlacer.addBranchSideZone(recentJunction, ctx.expandDir(), recentBranchDir,
                    PlacementConstants.STANDARD_SEGMENT_LENGTH);
            bridgePlacer.addJunctionZone(recentJunction, 3, ctx.expandDir(), recentBranchDir);
            bridgePlacer.addPillarExclusion(recentJunction, JUNCTION_PILLAR_CLEARANCE_RADIUS);
            ctx.head().clearLastBranchJunction();
        }
    }

    /**
     * Delegates to {@link WaterBridgeDetector#shouldUseBridgeDecking} for backward compatibility.
     */
    public static boolean shouldUseBridgeDecking(ServerLevel level, BlockPos trackPos) {
        return WaterBridgeDetector.shouldUseBridgeDecking(level, trackPos);
    }
}
