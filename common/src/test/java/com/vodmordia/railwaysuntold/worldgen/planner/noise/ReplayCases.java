package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a pasted production {@code [REPLAY]} log into a permanent regression case. A user copies the
 * block of log around a reported route, hands the raw text to {@link #extractRecord}, and the result is
 * a clean {@link ReplayRecord} body that {@link ReplayCaseTest} replays and pins against a golden.
 *
 * The production emit prints a one-line {@code [REPLAY] head ...} summary, then the serialized record
 * (a {@code REPLAY <version>} header through the {@code heights} line) on the following lines. Log
 * appenders prefix only the first line of a multi-line event, so the record body itself is unprefixed;
 * extraction therefore just keeps the lines from the {@code REPLAY} header through the {@code heights}
 * line and drops everything around them.
 */
final class ReplayCases {

    private ReplayCases() {}

    /**
     * Pulls the serialized record out of pasted log text: the run from the {@code REPLAY <version>}
     * header line through the {@code heights} line. A clean record (no surrounding log) passes through
     * unchanged. Throws if no complete record is present.
     */
    static String extractRecord(String pastedLog) {
        List<String> body = new ArrayList<>();
        boolean started = false;
        for (String raw : pastedLog.split("\n")) {
            String line = raw.strip();
            if (!started) {
                if (line.startsWith("REPLAY ")) {
                    started = true;
                    body.add(line);
                }
                continue;
            }
            body.add(line);
            if (line.startsWith("heights ")) {
                return String.join("\n", body) + "\n";
            }
        }
        if (!started) {
            throw new IllegalArgumentException("no [REPLAY] record found (expected a 'REPLAY <version>' line)");
        }
        throw new IllegalArgumentException("[REPLAY] record is truncated (no 'heights' line)");
    }
}
