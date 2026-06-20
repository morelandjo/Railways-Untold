package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a pasted production {@code [COMPILE]} log into a permanent regression case, the same way
 * {@link ReplayCases} does for {@code [REPLAY]}. A user copies the block of log around a reported
 * track-geometry bug; {@link #extractRecord} keeps the run from the {@code COMPILE <version>} header
 * through the {@code waypoints} line and drops the surrounding log, yielding a clean
 * {@link CompileRecord} body that {@link CompileCaseTest} re-compiles and pins against a golden.
 */
final class CompileCases {

    private CompileCases() {}

    /**
     * Pulls the serialized record out of pasted log text: the run from the {@code COMPILE <version>}
     * header line through the {@code waypoints} line. A clean record (no surrounding log) passes
     * through unchanged. Throws if no complete record is present.
     */
    static String extractRecord(String pastedLog) {
        List<String> body = new ArrayList<>();
        boolean started = false;
        for (String raw : pastedLog.split("\n")) {
            String line = raw.strip();
            if (!started) {
                if (line.startsWith("COMPILE ")) {
                    started = true;
                    body.add(line);
                }
                continue;
            }
            body.add(line);
            if (line.startsWith("waypoints ")) {
                return String.join("\n", body) + "\n";
            }
        }
        if (!started) {
            throw new IllegalArgumentException("no [COMPILE] record found (expected a 'COMPILE <version>' line)");
        }
        throw new IllegalArgumentException("[COMPILE] record is truncated (no 'waypoints' line)");
    }
}
