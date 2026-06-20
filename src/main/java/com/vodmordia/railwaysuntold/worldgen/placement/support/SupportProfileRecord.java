package com.vodmordia.railwaysuntold.worldgen.placement.support;

import java.util.ArrayList;
import java.util.List;

/**
 * A compact, machine-parseable capture of one track block's support outcome: the block position,
 * the support that was committed there (bridge decking, terrain fill, or nothing), the reason, and
 * the block column scanned directly below the track encoded as run-length solid/air/fluid bands.
 * Deserializing it reconstructs the sub-surface shape the placement layer saw, so a production
 * support decision (such as a cave the track failed to bridge) can be turned into a regression test.
 *
 * Format is line-oriented {@code key value} pairs, mirroring ReplayRecord: one {@code track} line,
 * a {@code decision} line, a {@code reason} line, and a count-prefixed {@code bands} line whose
 * tokens are {@code <kind><length>} with kind S (solid), A (air or replaceable), or F (fluid),
 * read top-down from the block at trackY-1. The form carries no clocks, identity, or iteration
 * order, so it is stable and diffable.
 */
public final class SupportProfileRecord {

    public static final String VERSION = "v1";

    /** Maximum column depth scanned below the track when building the band profile. */
    public static final int MAX_PROFILE_DEPTH = 24;

    public enum Decision { BRIDGE_DECKING, TERRAIN_FILL, NONE }

    public enum Reason {
        FLUID, ELEVATED, DEEP_GAP, NO_FLOOR, UNSPECIFIED;

        String token() {
            return this == UNSPECIFIED ? "-" : name();
        }

        static Reason parse(String token) {
            return token.equals("-") ? UNSPECIFIED : Reason.valueOf(token);
        }
    }

    /** A run of {@code length} consecutive blocks of one {@code kind} (S, A, or F). */
    public record Band(char kind, int length) {}

    public final int trackX;
    public final int trackY;
    public final int trackZ;
    public final Decision decision;
    public final Reason reason;
    /** The column below the track, top-down from trackY-1, run-length encoded. */
    public final List<Band> bands;

    public SupportProfileRecord(int trackX, int trackY, int trackZ,
                                Decision decision, Reason reason, List<Band> bands) {
        this.trackX = trackX;
        this.trackY = trackY;
        this.trackZ = trackZ;
        this.decision = decision;
        this.reason = reason;
        this.bands = bands;
    }

    public String serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append("SUPPORT ").append(VERSION).append('\n');
        sb.append("track ").append(trackX).append(' ').append(trackY).append(' ').append(trackZ).append('\n');
        sb.append("decision ").append(decision.name()).append('\n');
        sb.append("reason ").append(reason.token()).append('\n');
        sb.append("bands ").append(bands.size());
        for (Band b : bands) {
            sb.append(' ').append(b.kind()).append(b.length());
        }
        sb.append('\n');
        return sb.toString();
    }

    public static SupportProfileRecord parse(String text) {
        int[] track = {0, 0, 0};
        Decision decision = Decision.NONE;
        Reason reason = Reason.UNSPECIFIED;
        List<Band> bands = new ArrayList<>();
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("SUPPORT")) {
                continue;
            }
            int sp = line.indexOf(' ');
            if (sp < 0) {
                continue;
            }
            String key = line.substring(0, sp);
            String value = line.substring(sp + 1).strip();
            switch (key) {
                case "track" -> {
                    String[] p = value.split("\\s+");
                    track[0] = Integer.parseInt(p[0]);
                    track[1] = Integer.parseInt(p[1]);
                    track[2] = Integer.parseInt(p[2]);
                }
                case "decision" -> decision = Decision.valueOf(value);
                case "reason" -> reason = Reason.parse(value);
                case "bands" -> bands = parseBands(value);
                default -> { }
            }
        }
        return new SupportProfileRecord(track[0], track[1], track[2], decision, reason, bands);
    }

    private static List<Band> parseBands(String value) {
        List<Band> bands = new ArrayList<>();
        String[] toks = value.split("\\s+");
        // toks[0] is the count; the rest are <kind><length> tokens.
        for (int i = 1; i < toks.length; i++) {
            String t = toks[i];
            if (t.isEmpty()) {
                continue;
            }
            bands.add(new Band(t.charAt(0), Integer.parseInt(t.substring(1))));
        }
        return bands;
    }
}
