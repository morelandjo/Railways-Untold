package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseStructureScanner.PredictedStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.*;

/**
 * A compact, machine-parseable capture of everything the pure planner saw for one plan: the scalar
 * inputs to {@link CoarseRoutePlanner#planRouteOffThread}, the terrain heights it sampled, the
 * avoidable structures and existing-track clusters it routed around, the coordinates it found to be
 * ocean/water, and the config slope. Deserializing it and re-running the planner reproduces the route
 * deterministically with no world - the basis for turning a production bug report into a regression test.
 *
 * Format is line-oriented {@code key value} pairs. The variable-length parts each get their own
 * line kind: one {@code structure} line per avoidable structure, one {@code track} line per existing
 * cluster, and the {@code heights}/{@code oceans}/{@code waters} coordinate lists. Heights are emitted
 * in sorted (x,z) order and the ocean/water coordinate sets are sorted too, so the serialized form
 * carries no clocks, identity, or map-iteration order and is stable and diffable.
 */
public final class ReplayRecord {

    public static final String VERSION = "v2";

    public final BlockPos start;
    public final BlockPos target;
    public final UUID headId;
    public final Direction headDir;
    @Nullable public final Direction arrivalDir;
    public final int descentHintY;
    public final boolean isFromTrackTip;
    public final int seaLevel;
    public final double maxSlopeRatio;
    /** Sampled terrain heights keyed by packed (x,z), in sorted order. */
    public final Map<Long, Integer> heights;
    /** Avoidable structures the planner routed around, in plan order. */
    public final List<PredictedStructure> structures;
    /** Existing-track clusters the planner avoided, in plan order. */
    public final List<RouteObstacleAvoider.ExistingTrackCluster> tracks;
    /** Packed (x,z) where {@code isLikelyOcean} was true; everything else replays as false. */
    public final Set<Long> oceanCoords;
    /** Packed (x,z) where {@code hasWaterAtPosition} was true; everything else replays as false. */
    public final Set<Long> waterCoords;

    public ReplayRecord(BlockPos start, BlockPos target, UUID headId, Direction headDir,
                 @Nullable Direction arrivalDir, int descentHintY, boolean isFromTrackTip,
                 int seaLevel, double maxSlopeRatio, Map<Long, Integer> heights,
                 List<PredictedStructure> structures, List<RouteObstacleAvoider.ExistingTrackCluster> tracks,
                 Set<Long> oceanCoords, Set<Long> waterCoords) {
        this.start = start;
        this.target = target;
        this.headId = headId;
        this.headDir = headDir;
        this.arrivalDir = arrivalDir;
        this.descentHintY = descentHintY;
        this.isFromTrackTip = isFromTrackTip;
        this.seaLevel = seaLevel;
        this.maxSlopeRatio = maxSlopeRatio;
        this.heights = heights;
        this.structures = structures;
        this.tracks = tracks;
        this.oceanCoords = oceanCoords;
        this.waterCoords = waterCoords;
    }

    public static long packKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static int unpackX(long key) {
        return (int) (key >> 32);
    }

    public static int unpackZ(long key) {
        return (int) key;
    }

    public String serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append("REPLAY ").append(VERSION).append('\n');
        sb.append("start ").append(start.getX()).append(' ').append(start.getY()).append(' ').append(start.getZ()).append('\n');
        sb.append("target ").append(target.getX()).append(' ').append(target.getY()).append(' ').append(target.getZ()).append('\n');
        sb.append("headId ").append(headId).append('\n');
        sb.append("headDir ").append(headDir.name()).append('\n');
        sb.append("arrivalDir ").append(arrivalDir == null ? "-" : arrivalDir.name()).append('\n');
        sb.append("descentHintY ").append(descentHintY).append('\n');
        sb.append("isFromTrackTip ").append(isFromTrackTip).append('\n');
        sb.append("seaLevel ").append(seaLevel).append('\n');
        sb.append("maxSlopeRatio ").append(maxSlopeRatio).append('\n');

        sb.append("structures ").append(structures.size()).append('\n');
        for (PredictedStructure s : structures) {
            BlockPos c = s.approximateCenter();
            sb.append("structure ")
                    .append(s.chunkPos().x).append(' ').append(s.chunkPos().z).append(' ')
                    .append(c.getX()).append(' ').append(c.getY()).append(' ').append(c.getZ()).append(' ')
                    .append(s.isVillage()).append(' ')
                    .append(s.structureSetName().isEmpty() ? "-" : s.structureSetName()).append(' ')
                    .append(s.possibleStructures().isEmpty() ? "-" : String.join(",", s.possibleStructures())).append(' ')
                    .append(serializeFootprint(s.footprint()))
                    .append('\n');
        }

        sb.append("tracks ").append(tracks.size()).append('\n');
        for (RouteObstacleAvoider.ExistingTrackCluster t : tracks) {
            BlockPos lo = t.boundingMin();
            BlockPos hi = t.boundingMax();
            sb.append("track ").append(t.headId()).append(' ')
                    .append(lo.getX()).append(' ').append(lo.getY()).append(' ').append(lo.getZ()).append(' ')
                    .append(hi.getX()).append(' ').append(hi.getY()).append(' ').append(hi.getZ())
                    .append('\n');
        }

        appendCoordList(sb, "oceans", oceanCoords);
        appendCoordList(sb, "waters", waterCoords);

        // Heights sorted by packed key for a stable, diffable line.
        Map<Long, Integer> sorted = (heights instanceof TreeMap) ? heights : new TreeMap<>(heights);
        sb.append("heights ").append(sorted.size());
        for (Map.Entry<Long, Integer> e : sorted.entrySet()) {
            sb.append(' ').append(unpackX(e.getKey())).append(',').append(unpackZ(e.getKey())).append(',').append(e.getValue());
        }
        sb.append('\n');
        return sb.toString();
    }

    private static void appendCoordList(StringBuilder sb, String key, Set<Long> coords) {
        Set<Long> sorted = (coords instanceof TreeSet) ? coords : new TreeSet<>(coords);
        sb.append(key).append(' ').append(sorted.size());
        for (long k : sorted) {
            sb.append(' ').append(unpackX(k)).append(',').append(unpackZ(k));
        }
        sb.append('\n');
    }

    public static ReplayRecord parse(String text) {
        Map<String, String> fields = new LinkedHashMap<>();
        String heightsLine = null;
        String oceansLine = null;
        String watersLine = null;
        List<PredictedStructure> structures = new ArrayList<>();
        List<RouteObstacleAvoider.ExistingTrackCluster> tracks = new ArrayList<>();
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("REPLAY")) {
                continue;
            }
            if (line.startsWith("structure ")) {
                structures.add(parseStructure(line.substring("structure ".length())));
                continue;
            }
            if (line.startsWith("track ")) {
                tracks.add(parseTrack(line.substring("track ".length())));
                continue;
            }
            if (line.startsWith("heights ")) {
                heightsLine = line.substring("heights ".length());
                continue;
            }
            if (line.startsWith("oceans ")) {
                oceansLine = line.substring("oceans ".length());
                continue;
            }
            if (line.startsWith("waters ")) {
                watersLine = line.substring("waters ".length());
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
        UUID headId = UUID.fromString(fields.get("headId"));
        Direction headDir = Direction.valueOf(fields.get("headDir"));
        String arr = fields.get("arrivalDir");
        Direction arrivalDir = (arr == null || arr.equals("-")) ? null : Direction.valueOf(arr);
        int descentHintY = Integer.parseInt(fields.get("descentHintY"));
        boolean isFromTrackTip = Boolean.parseBoolean(fields.get("isFromTrackTip"));
        int seaLevel = Integer.parseInt(fields.get("seaLevel"));
        double maxSlopeRatio = Double.parseDouble(fields.get("maxSlopeRatio"));

        Map<Long, Integer> heights = new TreeMap<>();
        if (heightsLine != null && !heightsLine.isBlank()) {
            String[] toks = heightsLine.trim().split("\\s+");
            // toks[0] is the count; the rest are x,z,h triples.
            for (int i = 1; i < toks.length; i++) {
                String[] xzh = toks[i].split(",");
                heights.put(packKey(Integer.parseInt(xzh[0]), Integer.parseInt(xzh[1])), Integer.parseInt(xzh[2]));
            }
        }

        return new ReplayRecord(start, target, headId, headDir, arrivalDir, descentHintY,
                isFromTrackTip, seaLevel, maxSlopeRatio, heights, structures, tracks,
                parseCoordList(oceansLine), parseCoordList(watersLine));
    }

    private static Set<Long> parseCoordList(@Nullable String line) {
        Set<Long> coords = new LinkedHashSet<>();
        if (line == null || line.isBlank()) {
            return coords;
        }
        String[] toks = line.trim().split("\\s+");
        // toks[0] is the count; the rest are x,z pairs.
        for (int i = 1; i < toks.length; i++) {
            String[] xz = toks[i].split(",");
            coords.add(packKey(Integer.parseInt(xz[0]), Integer.parseInt(xz[1])));
        }
        return coords;
    }

    private static PredictedStructure parseStructure(String s) {
        String[] p = s.trim().split("\\s+");
        ChunkPos chunkPos = new ChunkPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]));
        BlockPos center = new BlockPos(Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]));
        boolean isVillage = Boolean.parseBoolean(p[5]);
        String setName = p[6].equals("-") ? "" : p[6];
        List<String> possible = p[7].equals("-") ? List.of() : List.of(p[7].split(","));
        // Field 8 (footprint) is optional so records written before box-aware avoidance still parse.
        List<BoundingBox> footprint = p.length > 8 ? parseFootprint(p[8]) : null;
        return new PredictedStructure(chunkPos, center, setName, possible, isVillage, footprint);
    }

    /** Footprint as {@code minX,minY,minZ,maxX,maxY,maxZ} per box, boxes joined by ';'; '-' when absent. */
    private static String serializeFootprint(@Nullable List<BoundingBox> footprint) {
        if (footprint == null || footprint.isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < footprint.size(); i++) {
            BoundingBox b = footprint.get(i);
            if (i > 0) sb.append(';');
            sb.append(b.minX()).append(',').append(b.minY()).append(',').append(b.minZ()).append(',')
              .append(b.maxX()).append(',').append(b.maxY()).append(',').append(b.maxZ());
        }
        return sb.toString();
    }

    @Nullable
    private static List<BoundingBox> parseFootprint(String field) {
        if (field.equals("-")) {
            return null;
        }
        List<BoundingBox> boxes = new ArrayList<>();
        for (String box : field.split(";")) {
            String[] c = box.split(",");
            boxes.add(new BoundingBox(
                    Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2]),
                    Integer.parseInt(c[3]), Integer.parseInt(c[4]), Integer.parseInt(c[5])));
        }
        return boxes;
    }

    private static RouteObstacleAvoider.ExistingTrackCluster parseTrack(String s) {
        String[] p = s.trim().split("\\s+");
        UUID id = UUID.fromString(p[0]);
        BlockPos lo = new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
        BlockPos hi = new BlockPos(Integer.parseInt(p[4]), Integer.parseInt(p[5]), Integer.parseInt(p[6]));
        return new RouteObstacleAvoider.ExistingTrackCluster(id, lo, hi);
    }

    private static BlockPos parsePos(String s) {
        String[] p = s.trim().split("\\s+");
        return new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
    }
}
