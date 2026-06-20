package com.vodmordia.railwaysuntold.worldgen.head;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * A compact, machine-parseable capture of one village (re)assignment decision, so a bad choice seen
 * in-game can be reproduced offline as a regression test - the same role {@code [REPLAY]}/{@code [COMPILE]}
 * play for the route planner, for the village-targeting subsystem they don't cover.
 *
 * It records the head's pose at decision time (the thing that biases selection - e.g. a head momentarily
 * swung off-course by an avoidance detour), the spawn/radius scalars the filters key off, every candidate
 * village that was nearby (with its pose-filter outcomes and tracker flags), and the village that was
 * actually chosen. {@link VillageAssignmentReplay} re-derives the choice from this record with no world.
 *
 * Format is line-oriented {@code key value}; one {@code candidate} line per nearby village. No clocks,
 * identity, or map order - stable and diffable.
 */
public final class VillageAssignRecord {

    public static final String VERSION = "v1";

    /** Where a candidate came from at capture time. */
    public enum Source { LOCATED, PREDICTED }

    /** One nearby village considered, with the runtime outcome of each filter that gates it. */
    public record Candidate(Source source, BlockPos center, boolean notBehind, boolean backtracking,
                            boolean assigned, boolean attempted) {}

    public final int headNumber;
    public final String mode;
    public final BlockPos pos;
    @Nullable public final Direction dir;
    public final Direction initDir;
    public final boolean isOriginal;
    public final BlockPos spawn;
    public final int searchRadius;
    public final int headDistFromSpawn;
    /** The village the runtime committed to, or null if none was assigned. */
    @Nullable public final BlockPos chosen;
    public final List<Candidate> candidates;

    public VillageAssignRecord(int headNumber, String mode, BlockPos pos, @Nullable Direction dir,
                               Direction initDir, boolean isOriginal, BlockPos spawn, int searchRadius,
                               int headDistFromSpawn, @Nullable BlockPos chosen, List<Candidate> candidates) {
        this.headNumber = headNumber;
        this.mode = mode;
        this.pos = pos;
        this.dir = dir;
        this.initDir = initDir;
        this.isOriginal = isOriginal;
        this.spawn = spawn;
        this.searchRadius = searchRadius;
        this.headDistFromSpawn = headDistFromSpawn;
        this.chosen = chosen;
        this.candidates = candidates;
    }

    public String serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append("VILLAGE-ASSIGN ").append(VERSION).append('\n');
        sb.append("head ").append(headNumber).append('\n');
        sb.append("mode ").append(mode).append('\n');
        sb.append("pos ").append(pos.getX()).append(' ').append(pos.getY()).append(' ').append(pos.getZ()).append('\n');
        sb.append("dir ").append(dir == null ? "-" : dir.name()).append('\n');
        sb.append("initDir ").append(initDir.name()).append('\n');
        sb.append("isOriginal ").append(isOriginal).append('\n');
        sb.append("spawn ").append(spawn.getX()).append(' ').append(spawn.getY()).append(' ').append(spawn.getZ()).append('\n');
        sb.append("searchRadius ").append(searchRadius).append('\n');
        sb.append("headDistFromSpawn ").append(headDistFromSpawn).append('\n');
        sb.append("chosen ").append(chosen == null ? "-"
                : chosen.getX() + " " + chosen.getY() + " " + chosen.getZ()).append('\n');
        sb.append("candidates ").append(candidates.size()).append('\n');
        for (Candidate c : candidates) {
            sb.append("candidate ").append(c.source().name()).append(' ')
                    .append(c.center().getX()).append(' ').append(c.center().getY()).append(' ').append(c.center().getZ()).append(' ')
                    .append(bit(c.notBehind())).append(' ').append(bit(c.backtracking())).append(' ')
                    .append(bit(c.assigned())).append(' ').append(bit(c.attempted())).append('\n');
        }
        return sb.toString();
    }

    public static VillageAssignRecord parse(String text) {
        String mode = "NEW";
        int headNumber = 0;
        int searchRadius = 0;
        int headDistFromSpawn = 0;
        boolean isOriginal = true;
        BlockPos pos = BlockPos.ZERO;
        BlockPos spawn = BlockPos.ZERO;
        BlockPos chosen = null;
        Direction dir = null;
        Direction initDir = Direction.NORTH;
        List<Candidate> candidates = new ArrayList<>();

        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("VILLAGE-ASSIGN")) continue;
            String[] t = line.split("\\s+");
            switch (t[0]) {
                case "head" -> headNumber = Integer.parseInt(t[1]);
                case "mode" -> mode = t[1];
                case "pos" -> pos = new BlockPos(Integer.parseInt(t[1]), Integer.parseInt(t[2]), Integer.parseInt(t[3]));
                case "dir" -> dir = "-".equals(t[1]) ? null : Direction.valueOf(t[1]);
                case "initDir" -> initDir = Direction.valueOf(t[1]);
                case "isOriginal" -> isOriginal = Boolean.parseBoolean(t[1]);
                case "spawn" -> spawn = new BlockPos(Integer.parseInt(t[1]), Integer.parseInt(t[2]), Integer.parseInt(t[3]));
                case "searchRadius" -> searchRadius = Integer.parseInt(t[1]);
                case "headDistFromSpawn" -> headDistFromSpawn = Integer.parseInt(t[1]);
                case "chosen" -> chosen = "-".equals(t[1]) ? null
                        : new BlockPos(Integer.parseInt(t[1]), Integer.parseInt(t[2]), Integer.parseInt(t[3]));
                case "candidate" -> candidates.add(new Candidate(
                        Source.valueOf(t[1]),
                        new BlockPos(Integer.parseInt(t[2]), Integer.parseInt(t[3]), Integer.parseInt(t[4])),
                        t[5].equals("1"), t[6].equals("1"), t[7].equals("1"), t[8].equals("1")));
                default -> { /* candidates count line and any unknown keys are ignored */ }
            }
        }
        return new VillageAssignRecord(headNumber, mode, pos, dir, initDir, isOriginal, spawn,
                searchRadius, headDistFromSpawn, chosen, candidates);
    }

    private static int bit(boolean b) {
        return b ? 1 : 0;
    }
}
