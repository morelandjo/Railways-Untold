package com.vodmordia.railwaysuntold.worldgen.integration.create.util;

import com.mojang.logging.LogUtils;
import com.simibubi.create.Create;
import com.simibubi.create.api.event.TrackGraphMergeEvent;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.graph.TrackNodeLocation.DiscoveredLocation;
import com.simibubi.create.content.trains.signal.SignalPropagator;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackMaterial;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Non-destructive graph edge addition for bezier connections.
 */
public final class DirectGraphConnector {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Cached reflection for accessing BezierConnection's Couple fields
    // (Couple is in catnip which isn't on the compile classpath)
    private static volatile boolean reflectionInitialized = false;
    private static Field axesField;
    private static Field normalsField;
    private static Method coupleGetFirst;
    private static Method coupleGetSecond;

    private DirectGraphConnector() {}

    /**
     * Gets an existing graph at the given location, or bootstraps a new one if trains
     * are present (to avoid falling back to destructive TrackPropagator rebuilds).
     *
     */
    private static TrackGraph getOrBootstrapGraph(ServerLevel level, TrackNodeLocation loc) {
        TrackGraph graph = Create.RAILWAYS.getGraph(level, loc);
        if (graph != null) return graph;
        if (Create.RAILWAYS.trains.isEmpty()) return null; // safe to use propagation

        TrackGraph newGraph = new TrackGraph();
        Create.RAILWAYS.putGraphWithDefaultGroup(newGraph);
        LOGGER.trace("[DirectGraphConnect] Bootstrapped new graph {} for location {}", newGraph.id, loc);
        return newGraph;
    }

    /**
     * Connects two track endpoints via a bezier curve directly in the track graph.
     *
     * @param level              The server level
     * @param firstPos           Position of first track block
     * @param secondPos          Position of second track block
     * @param bezierConnectionObj The BezierConnection object
     * @return true if the connection was added successfully, false to fall back to propagation
     */
    public static boolean connectBezierDirect(ServerLevel level, BlockPos firstPos, BlockPos secondPos,
                                               Object bezierConnectionObj) {
        try {
            if (!(bezierConnectionObj instanceof BezierConnection bezier)) {
                return false;
            }

            ensureReflectionInitialized();
            if (coupleGetFirst == null || coupleGetSecond == null) {
                return false;
            }

            // Extract axes and normals from the BezierConnection via reflection on Couple
            Object axesCouple = axesField.get(bezier);
            Object normalsCouple = normalsField.get(bezier);

            Vec3 axis1 = (Vec3) coupleGetFirst.invoke(axesCouple);
            Vec3 axis2 = (Vec3) coupleGetSecond.invoke(axesCouple);
            Vec3 normal1 = (Vec3) coupleGetFirst.invoke(normalsCouple);
            Vec3 normal2 = (Vec3) coupleGetSecond.invoke(normalsCouple);

            if (axis1 == null || axis2 == null || normal1 == null || normal2 == null) {
                return false;
            }

            // Compute node positions at block edges using cardinal directions.
            // Using cardinal (not axis-based) positions ensures bezier endpoint nodes
            // coincide with straight track nodes at the same block face.
            Direction cardinal1 = TrackNodeGeometry.axisToCardinalDirection(axis1);
            Direction cardinal2 = TrackNodeGeometry.axisToCardinalDirection(axis2);
            Vec3 nodePos1 = TrackNodeGeometry.computeNodePositionFromDirection(firstPos, cardinal1);
            Vec3 nodePos2 = TrackNodeGeometry.computeNodePositionFromDirection(secondPos, cardinal2);

            // Look up existing graphs at each endpoint
            TrackNodeLocation loc1 = new TrackNodeLocation(nodePos1).in(level.dimension());
            TrackNodeLocation loc2 = new TrackNodeLocation(nodePos2).in(level.dimension());

            TrackGraph graph1 = getOrBootstrapGraph(level, loc1);
            TrackGraph graph2 = getOrBootstrapGraph(level, loc2);

            if (graph1 == null && graph2 == null) {
                LOGGER.trace("[DirectGraphConnect] No graph at either endpoint {} or {}", firstPos, secondPos);
                return false;
            }

            // Determine target graph, merging if necessary
            TrackGraph targetGraph = resolveTargetGraph(graph1, graph2);

            // Build DiscoveredLocation objects matching the existing node positions
            DiscoveredLocation disc1 = new DiscoveredLocation(level, nodePos1)
                    .withDirection(axis1.normalize())
                    .withNormal(normal1);
            DiscoveredLocation disc2 = new DiscoveredLocation(level, nodePos2)
                    .withDirection(axis2.normalize())
                    .withNormal(normal2);

            // Ensure nodes exist in the target graph 
            List<TrackNode> newNodes = new ArrayList<>();
            if (targetGraph.createNodeIfAbsent(disc1)) {
                TrackNode n = targetGraph.locateNode(disc1);
                if (n != null) newNodes.add(n);
            }
            if (targetGraph.createNodeIfAbsent(disc2)) {
                TrackNode n = targetGraph.locateNode(disc2);
                if (n != null) newNodes.add(n);
            }

            // Connect the nodes - adds bidirectional edges, computes intersections, syncs to clients
            targetGraph.connectNodes(level, disc1, disc2, bezier);

            // Create internal straight edges within each bezier endpoint block.
            // These bridge the gap between the bezier node (on the curve side) and
            // the opposite block edge node (where straight track neighbors connect).
            connectEndpointInternalEdge(level, targetGraph, firstPos, cardinal1, newNodes);
            connectEndpointInternalEdge(level, targetGraph, secondPos, cardinal2, newNodes);

            // Notify signals of new nodes (matches Create's TrackPropagator lifecycle)
            for (TrackNode node : newNodes) {
                SignalPropagator.notifySignalsOfNewNode(targetGraph, node);
            }

            LOGGER.trace("[DirectGraphConnect] Connected {} -> {} in graph {}",
                    firstPos.toShortString(), secondPos.toShortString(), targetGraph.id);
            return true;

        } catch (Exception e) {
            LOGGER.warn("[DirectGraphConnect] Failed to connect {} -> {}: {}",
                    firstPos, secondPos, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Connects a straight track segment directly in the track graph,
     * without triggering TrackPropagator's destructive rebuild.
     *
     *
     * @param level          The server level
     * @param segmentStart   First block of the straight segment
     * @param segmentEnd     Last block of the straight segment
     * @param trackDirection Direction from start to end (NORTH/SOUTH/EAST/WEST)
     * @return true if the connection was added successfully, false to fall back to propagation
     */
    public static boolean connectStraightDirect(ServerLevel level, BlockPos segmentStart,
                                                 BlockPos segmentEnd, Direction trackDirection) {
        try {
            warnIfMisaligned(segmentStart, segmentEnd, trackDirection);
            Vec3 dirVec = Vec3.atLowerCornerOf(trackDirection.getNormal());
            Vec3 UP = new Vec3(0, 1, 0);

            // Node at the entry edge of segmentStart (facing opposite to travel direction)
            Vec3 nodePos1 = TrackNodeGeometry.computeNodePositionFromDirection(segmentStart, trackDirection.getOpposite());
            // Node at the exit edge of segmentEnd (facing travel direction)
            Vec3 nodePos2 = TrackNodeGeometry.computeNodePositionFromDirection(segmentEnd, trackDirection);

            TrackNodeLocation loc1 = new TrackNodeLocation(nodePos1).in(level.dimension());
            TrackNodeLocation loc2 = new TrackNodeLocation(nodePos2).in(level.dimension());

            TrackGraph graph1 = getOrBootstrapGraph(level, loc1);
            TrackGraph graph2 = getOrBootstrapGraph(level, loc2);

            if (graph1 == null && graph2 == null) {
                LOGGER.trace("[DirectGraphConnect] No graph at either straight endpoint {} or {}",
                        segmentStart, segmentEnd);
                return false;
            }

            TrackGraph targetGraph = resolveTargetGraph(graph1, graph2);

            TrackMaterial material = getActiveTrackMaterial();

            // Direction at entry node points outward (opposite of travel)
            DiscoveredLocation disc1 = new DiscoveredLocation(level, nodePos1)
                    .withDirection(dirVec.scale(-1))
                    .withNormal(UP)
                    .materialA(material).materialB(material);
            // Direction at exit node points outward (travel direction)
            DiscoveredLocation disc2 = new DiscoveredLocation(level, nodePos2)
                    .withDirection(dirVec)
                    .withNormal(UP)
                    .materialA(material).materialB(material);

            List<TrackNode> newNodes = new ArrayList<>();
            if (targetGraph.createNodeIfAbsent(disc1)) {
                TrackNode n = targetGraph.locateNode(disc1);
                if (n != null) newNodes.add(n);
            }
            if (targetGraph.createNodeIfAbsent(disc2)) {
                TrackNode n = targetGraph.locateNode(disc2);
                if (n != null) newNodes.add(n);
            }

            // null bezier = straight edge
            targetGraph.connectNodes(level, disc1, disc2, null);

            for (TrackNode node : newNodes) {
                SignalPropagator.notifySignalsOfNewNode(targetGraph, node);
            }

            LOGGER.trace("[DirectGraphConnect] Connected straight {} -> {} in graph {}",
                    segmentStart.toShortString(), segmentEnd.toShortString(), targetGraph.id);
            return true;

        } catch (Exception e) {
            LOGGER.warn("[DirectGraphConnect] Failed straight connect {} -> {}: {}",
                    segmentStart, segmentEnd, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Connects an entire straight track segment in the graph.
     *
     * Creates nodes at the entry/exit edges of each block. Adjacent blocks share the
     * node at their common boundary (same position), so no between-block edges are needed.
     * Only within-block edges (entry->exit) are created, forming a chain through the segment.
     *
     * @param level          The server level
     * @param segmentStart   First block of the segment
     * @param segmentEnd     Last block of the segment
     * @param trackDirection Cardinal direction from start to end
     * @param distance       Number of blocks in the segment (end - start along axis)
     * @return true if all connections were added successfully
     */
    public static boolean connectStraightSegmentDirect(ServerLevel level, BlockPos segmentStart,
                                                        BlockPos segmentEnd, Direction trackDirection,
                                                        int distance) {
        if (distance < 1) {
            return false;
        }

        try {
            Vec3 dirVec = Vec3.atLowerCornerOf(trackDirection.getNormal());
            Vec3 UP = new Vec3(0, 1, 0);

            // Find a graph at the entry edge of the start block (should exist from previous segment)
            Vec3 entryNodePos = TrackNodeGeometry.computeNodePositionFromDirection(segmentStart, trackDirection.getOpposite());
            TrackNodeLocation entryLoc = new TrackNodeLocation(entryNodePos).in(level.dimension());
            TrackGraph entryGraph = getOrBootstrapGraph(level, entryLoc);

            // Also check the exit edge of the end block
            Vec3 exitNodePos = TrackNodeGeometry.computeNodePositionFromDirection(segmentEnd, trackDirection);
            TrackNodeLocation exitLoc = new TrackNodeLocation(exitNodePos).in(level.dimension());
            TrackGraph exitGraph = getOrBootstrapGraph(level, exitLoc);

            if (entryGraph == null && exitGraph == null) {
                LOGGER.trace("[DirectGraphConnect] No graph at either segment endpoint {} or {}",
                        segmentStart, segmentEnd);
                return false;
            }

            TrackGraph targetGraph = resolveTargetGraph(entryGraph, exitGraph);
            TrackMaterial material = getActiveTrackMaterial();
            List<TrackNode> newNodes = new ArrayList<>();

            // Walk through each block, creating nodes at block edges and connecting within each block.
            for (int i = 0; i <= distance; i++) {
                BlockPos blockPos = segmentStart.relative(trackDirection, i);

                // Entry edge of this block
                Vec3 entryPos = TrackNodeGeometry.computeNodePositionFromDirection(blockPos, trackDirection.getOpposite());
                DiscoveredLocation entryNode = new DiscoveredLocation(level, entryPos)
                        .withDirection(dirVec.scale(-1))
                        .withNormal(UP)
                        .materialA(material).materialB(material);

                // Exit edge of this block
                Vec3 exitPos = TrackNodeGeometry.computeNodePositionFromDirection(blockPos, trackDirection);
                DiscoveredLocation exitNode = new DiscoveredLocation(level, exitPos)
                        .withDirection(dirVec)
                        .withNormal(UP)
                        .materialA(material).materialB(material);

                if (targetGraph.createNodeIfAbsent(entryNode)) {
                    TrackNode n = targetGraph.locateNode(entryNode);
                    if (n != null) newNodes.add(n);
                }
                if (targetGraph.createNodeIfAbsent(exitNode)) {
                    TrackNode n = targetGraph.locateNode(exitNode);
                    if (n != null) newNodes.add(n);
                }

                // Connect entry to exit within this block
                targetGraph.connectNodes(level, entryNode, exitNode, null);
            }

            // Notify signals of all new nodes (matches Create's TrackPropagator lifecycle)
            for (TrackNode node : newNodes) {
                SignalPropagator.notifySignalsOfNewNode(targetGraph, node);
            }

            LOGGER.trace("[DirectGraphConnect] Connected segment {} -> {} ({} blocks, {} new nodes) in graph {}",
                    segmentStart.toShortString(), segmentEnd.toShortString(), distance + 1, newNodes.size(), targetGraph.id);
            return true;

        } catch (Exception e) {
            LOGGER.warn("[DirectGraphConnect] Failed segment connect {} -> {}: {}",
                    segmentStart, segmentEnd, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Ensures a single track position is registered in the graph.
     * Used for station placement where the track already exists but needs
     * its nodes visible to Create's station binding.
     *
     * @param level          The server level
     * @param trackPos       Position of the track block
     * @param trackDirection Direction the track runs
     * @return true if nodes were ensured, false to fall back to propagation
     */
    public static boolean ensureTrackInGraph(ServerLevel level, BlockPos trackPos, Direction trackDirection) {
        try {
            Vec3 dirVec = Vec3.atLowerCornerOf(trackDirection.getNormal());
            Vec3 UP = new Vec3(0, 1, 0);

            // Both edges of this track block
            Vec3 nodePos1 = TrackNodeGeometry.computeNodePositionFromDirection(trackPos, trackDirection);
            Vec3 nodePos2 = TrackNodeGeometry.computeNodePositionFromDirection(trackPos, trackDirection.getOpposite());

            TrackNodeLocation loc1 = new TrackNodeLocation(nodePos1).in(level.dimension());
            TrackNodeLocation loc2 = new TrackNodeLocation(nodePos2).in(level.dimension());

            TrackGraph graph1 = getOrBootstrapGraph(level, loc1);
            TrackGraph graph2 = getOrBootstrapGraph(level, loc2);

            // If both nodes already exist in a graph, the track is already registered
            if (graph1 != null && graph2 != null) {
                TrackGraph g = (graph1 == graph2) ? graph1 : resolveTargetGraph(graph1, graph2);
                if (g.locateNode(loc1) != null && g.locateNode(loc2) != null) {
                    return true; // Already fully in graph
                }
            }

            if (graph1 == null && graph2 == null) {
                return false; // No graph context at all, fall back
            }

            TrackGraph targetGraph = resolveTargetGraph(graph1, graph2);
            TrackMaterial material = getActiveTrackMaterial();

            DiscoveredLocation disc1 = new DiscoveredLocation(level, nodePos1)
                    .withDirection(dirVec)
                    .withNormal(UP)
                    .materialA(material).materialB(material);
            DiscoveredLocation disc2 = new DiscoveredLocation(level, nodePos2)
                    .withDirection(dirVec.scale(-1))
                    .withNormal(UP)
                    .materialA(material).materialB(material);

            targetGraph.createNodeIfAbsent(disc1);
            targetGraph.createNodeIfAbsent(disc2);
            targetGraph.connectNodes(level, disc1, disc2, null);

            return true;
        } catch (Exception e) {
            LOGGER.warn("[DirectGraphConnect] Failed ensureTrackInGraph at {}: {}",
                    trackPos, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Creates the internal straight edge within a bezier endpoint block, bridging
     * the bezier-side node to the opposite block edge where straight track connects.
     *
     */
    private static void connectEndpointInternalEdge(ServerLevel level, TrackGraph targetGraph,
                                                     BlockPos endpointPos, Direction bezierCardinal,
                                                     List<TrackNode> newNodes) {
        Direction oppositeCardinal = bezierCardinal.getOpposite();

        Vec3 bezierNodePos = TrackNodeGeometry.computeNodePositionFromDirection(endpointPos, bezierCardinal);
        Vec3 oppositeNodePos = TrackNodeGeometry.computeNodePositionFromDirection(endpointPos, oppositeCardinal);

        if (bezierNodePos.distanceToSqr(oppositeNodePos) < 0.001) {
            return;
        }

        Vec3 UP = new Vec3(0, 1, 0);
        Vec3 dirVec = Vec3.atLowerCornerOf(bezierCardinal.getNormal());
        TrackMaterial material = getActiveTrackMaterial();

        DiscoveredLocation oppositeDisc = new DiscoveredLocation(level, oppositeNodePos)
                .withDirection(dirVec.scale(-1))
                .withNormal(UP)
                .materialA(material).materialB(material);
        DiscoveredLocation bezierDisc = new DiscoveredLocation(level, bezierNodePos)
                .withDirection(dirVec)
                .withNormal(UP)
                .materialA(material).materialB(material);

        if (targetGraph.createNodeIfAbsent(oppositeDisc)) {
            TrackNode n = targetGraph.locateNode(oppositeDisc);
            if (n != null) newNodes.add(n);
        }
        if (targetGraph.createNodeIfAbsent(bezierDisc)) {
            TrackNode n = targetGraph.locateNode(bezierDisc);
            if (n != null) newNodes.add(n);
        }
        targetGraph.connectNodes(level, oppositeDisc, bezierDisc, null);

        LOGGER.trace("[DirectGraphConnect] Connected internal endpoint edge at {} ({} -> {})",
                endpointPos.toShortString(), oppositeNodePos, bezierNodePos);
    }

    /**
     * Logs a warning when connectStraightDirect is called with endpoints that aren't
     * axis-aligned for the given trackDirection.
     */
    private static void warnIfMisaligned(BlockPos from, BlockPos to, Direction trackDirection) {
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();
        boolean ew = trackDirection.getAxis() == Direction.Axis.X;
        boolean ns = trackDirection.getAxis() == Direction.Axis.Z;
        boolean perpOk = (ew && dz == 0) || (ns && dx == 0) || (!ew && !ns);
        if (perpOk && dy == 0) return;

        StringBuilder caller = new StringBuilder();
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        for (int i = 2; i < Math.min(st.length, 7); i++) {
            if (caller.length() > 0) caller.append(" <- ");
            caller.append(st[i].getClassName().substring(st[i].getClassName().lastIndexOf('.') + 1))
                    .append('.').append(st[i].getMethodName())
                    .append(':').append(st[i].getLineNumber());
        }

    }

    /**
     * Resolves which graph to use when endpoints may be in different graphs.
     * Merges if necessary, returns the surviving graph.
     */
    private static TrackGraph resolveTargetGraph(TrackGraph graph1, TrackGraph graph2) {
        if (graph1 != null && graph2 != null && graph1 != graph2) {
            return mergeGraphs(graph1, graph2);
        }
        return graph1 != null ? graph1 : graph2;
    }

    /**
     * Merges two different graphs by transferring the smaller into the larger.
     * Handles train reassignment (done internally by transferAll) and cleanup.
     *
     * @return the surviving (larger) graph
     */
    private static TrackGraph mergeGraphs(TrackGraph graph1, TrackGraph graph2) {
        TrackGraph target, source;
        if (graph1.getNodes().size() >= graph2.getNodes().size()) {
            target = graph1;
            source = graph2;
        } else {
            target = graph2;
            source = graph1;
        }

        // Post merge event before transferring (matches Create's TrackPropagator lifecycle)
        NeoForge.EVENT_BUS.post(new TrackGraphMergeEvent(source, target));

        // transferAll moves all nodes, edges, edge points, and reassigns trains
        source.transferAll(target);

        // Remove the now-empty source graph from the railway manager
        try {
            Create.RAILWAYS.removeGraphAndGroup(source);
        } catch (Exception e) {
            LOGGER.warn("[DirectGraphConnect] Failed to remove merged graph {}: {}", source.id, e.getMessage(), e);
        }

        LOGGER.trace("[DirectGraphConnect] Merged graph {} into {}", source.id, target.id);
        return target;
    }

    /**
     * Gets the active track material. Uses the Railway mod compat material if set
     */
    private static TrackMaterial getActiveTrackMaterial() {
        Object active = CreateTrackUtil.getActiveTrackMaterial();
        if (active instanceof TrackMaterial mat) {
            return mat;
        }
        return TrackMaterial.ANDESITE;
    }

    private static void ensureReflectionInitialized() {
        if (reflectionInitialized) return;
        synchronized (DirectGraphConnector.class) {
            if (reflectionInitialized) return;
            try {
                Class<?> bezierClass = CreateTrackUtil.getBezierConnectionClass();
                if (bezierClass != null) {
                    axesField = bezierClass.getField("axes");
                    normalsField = bezierClass.getField("normals");
                }

                Method cachedFirst = CreateTrackUtil.getCoupleGetFirstMethod();
                Method cachedSecond = CreateTrackUtil.getCoupleGetSecondMethod();
                if (cachedFirst != null && cachedSecond != null) {
                    coupleGetFirst = cachedFirst;
                    coupleGetSecond = cachedSecond;
                }
            } catch (ReflectiveOperationException e) {
                LOGGER.error("[DirectGraphConnect] Failed to initialize reflection: {}", e.getMessage());
            } finally {
                reflectionInitialized = true;
            }
        }
    }
}
