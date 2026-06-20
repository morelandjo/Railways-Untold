package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.CoarseWaypoint;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.WaypointType;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.YBasis;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A machine-parseable capture of the exact inputs to {@link PrecisionRouteCompiler#compile}: the coarse
 * waypoints, the route start/end, the head direction, and whether the start is a track tip. Compilation
 * is pure - it reads no world or terrain - so deserializing this record and re-running the compiler
 * reproduces the produced segments deterministically. That makes a track-geometry bug seen in-game
 * (a disconnected seam, a sub-minimum-radius curve) reproducible as a unit test without a live server,
 * complementing the terrain-replaying {@link ReplayRecord} which reproduces the coarse planner instead.
 *
 * Format is line-oriented {@code key value} pairs, ending with a single {@code waypoints} line that
 * carries the count followed by one {@code x,y,z,advisedY,TYPE,YBASIS} tuple per waypoint - mirroring
 * {@link ReplayRecord}'s {@code heights} line so the same paste-and-extract flow applies.
 */
public final class CompileRecord {

    public static final String VERSION = "v1";

    public final BlockPos start;
    public final BlockPos target;
    public final Direction headDir;
    public final boolean isFromTrackTip;
    public final List<CoarseWaypoint> waypoints;

    public CompileRecord(BlockPos start, BlockPos target, Direction headDir,
                         boolean isFromTrackTip, List<CoarseWaypoint> waypoints) {
        this.start = start;
        this.target = target;
        this.headDir = headDir;
        this.isFromTrackTip = isFromTrackTip;
        this.waypoints = waypoints;
    }

    public String serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append("COMPILE ").append(VERSION).append('\n');
        sb.append("start ").append(start.getX()).append(' ').append(start.getY()).append(' ').append(start.getZ()).append('\n');
        sb.append("target ").append(target.getX()).append(' ').append(target.getY()).append(' ').append(target.getZ()).append('\n');
        sb.append("headDir ").append(headDir.name()).append('\n');
        sb.append("isFromTrackTip ").append(isFromTrackTip).append('\n');

        sb.append("waypoints ").append(waypoints.size());
        for (CoarseWaypoint wp : waypoints) {
            BlockPos p = wp.position();
            sb.append(' ')
                    .append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ()).append(',')
                    .append(wp.advisedTrackY()).append(',')
                    .append(wp.type().name()).append(',')
                    .append(wp.yBasis().name());
        }
        sb.append('\n');
        return sb.toString();
    }

    public static CompileRecord parse(String text) {
        Map<String, String> fields = new LinkedHashMap<>();
        String waypointsLine = null;
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("COMPILE")) {
                continue;
            }
            if (line.startsWith("waypoints ")) {
                waypointsLine = line.substring("waypoints ".length());
                continue;
            }
            int sp = line.indexOf(' ');
            if (sp < 0) {
                continue;
            }
            fields.put(line.substring(0, sp), line.substring(sp + 1));
        }

        BlockPos start = parsePos(fields.get("start"));
        BlockPos target = parsePos(fields.get("target"));
        Direction headDir = Direction.valueOf(fields.get("headDir"));
        boolean isFromTrackTip = Boolean.parseBoolean(fields.get("isFromTrackTip"));

        List<CoarseWaypoint> waypoints = new ArrayList<>();
        if (waypointsLine != null && !waypointsLine.isBlank()) {
            String[] toks = waypointsLine.trim().split("\\s+");
            // toks[0] is the count; the rest are x,y,z,advisedY,TYPE,YBASIS tuples.
            for (int i = 1; i < toks.length; i++) {
                String[] f = toks[i].split(",");
                BlockPos pos = new BlockPos(
                        Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]));
                int advisedY = Integer.parseInt(f[3]);
                WaypointType type = WaypointType.valueOf(f[4]);
                YBasis yBasis = YBasis.valueOf(f[5]);
                waypoints.add(new CoarseWaypoint(pos, advisedY, type, yBasis));
            }
        }

        return new CompileRecord(start, target, headDir, isFromTrackTip, waypoints);
    }

    private static BlockPos parsePos(String s) {
        String[] p = s.trim().split("\\s+");
        return new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
    }
}
