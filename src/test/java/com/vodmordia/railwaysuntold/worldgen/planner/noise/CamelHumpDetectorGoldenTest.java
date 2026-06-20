package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainProfile;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainProfile.SamplePoint;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Characterization (golden) test for CamelHumpDetector. Both detectors are pure functions of
 * (profile, trackY), so the dip/peak regions they return are snapshotted directly. detectPeaks
 * and detectCamelHumps should be sign-flipped mirrors of one another; the peak scenarios pin
 * the baseline behavior so the BT3 rebaseline (peaks measured against local terrain, like dips,
 * instead of the global track Y) is a reviewed diff.
 */
class CamelHumpDetectorGoldenTest {

    private static final boolean UPDATE = Boolean.getBoolean("golden.update");
    private static final String GOLDEN_DIR = System.getProperty("golden.source.dir");
    private static final int INTERVAL = 8;

    @BeforeAll
    static void setUp() {
        PlannerTestConfig.bootstrapDefaults();
    }

    @Test
    void detectorRegions() throws IOException {
        StringBuilder sb = new StringBuilder();

        // --- peaks (detectPeaks, maxPeakRise=10) ---
        sb.append(peaksCase("flat at trackY -> no peaks", 72, 10,
                72, 72, 72, 72, 72));
        sb.append(peaksCase("single peak from trackY", 72, 10,
                72, 72, 84, 90, 84, 72, 72));
        // Track Y (72) is far below the local terrain (a ~100 plateau): a real local bump
        // on the plateau. Measured against the global trackY the whole plateau reads as one
        // never-recovering peak and is skipped; measured against local terrain it is a peak.
        sb.append(peaksCase("local bump on elevated plateau (trackY far below)", 72, 10,
                100, 100, 104, 114, 104, 100, 100));

        // --- dips (detectCamelHumps) - unchanged by BT3, included as a regression net ---
        sb.append(dipsCase("flat -> no dips", 80,
                80, 80, 80, 80, 80));
        sb.append(dipsCase("single dip", 80,
                80, 80, 68, 58, 68, 80, 80));

        verify("camel-hump-regions", sb.toString());
    }

    // --- helpers ---

    private static NoiseTerrainProfile profile(int... heights) {
        List<SamplePoint> samples = new ArrayList<>(heights.length);
        for (int i = 0; i < heights.length; i++) {
            samples.add(new SamplePoint(i * INTERVAL, 0, heights[i], 0.0, 0.0, false));
        }
        return new NoiseTerrainProfile(samples, INTERVAL);
    }

    private static String peaksCase(String label, int trackY, int maxPeakRise, int... heights) {
        List<CamelHumpDetector.DipRegion> peaks =
                CamelHumpDetector.detectPeaks(profile(heights), trackY, maxPeakRise);
        return serialize("PEAKS " + label, peaks);
    }

    private static String dipsCase(String label, int trackY, int... heights) {
        List<CamelHumpDetector.DipRegion> dips =
                CamelHumpDetector.detectCamelHumps(profile(heights), trackY);
        return serialize("DIPS " + label, dips);
    }

    private static String serialize(String label, List<CamelHumpDetector.DipRegion> regions) {
        StringBuilder sb = new StringBuilder();
        sb.append("== ").append(label).append(" (").append(regions.size()).append(") ==\n");
        for (CamelHumpDetector.DipRegion r : regions) {
            sb.append(String.format("  start=%d end=%d depth=%d span=%d%n",
                    r.startIndex(), r.endIndex(), r.dipDepth(), r.spanLength()));
        }
        return sb.toString();
    }

    private void verify(String scenario, String actual) throws IOException {
        if (GOLDEN_DIR == null) {
            throw new IllegalStateException("golden.source.dir system property not set");
        }
        Path golden = Path.of(GOLDEN_DIR, scenario + ".txt");
        if (UPDATE || Files.notExists(golden)) {
            Files.createDirectories(golden.getParent());
            Files.writeString(golden, actual);
        }
        String expected = Files.readString(golden);
        assertEquals(expected, actual,
                () -> "Golden mismatch for '" + scenario + "'. If intended, re-run with "
                        + "-Dgolden.update=true and review the diff before committing.");
    }
}
