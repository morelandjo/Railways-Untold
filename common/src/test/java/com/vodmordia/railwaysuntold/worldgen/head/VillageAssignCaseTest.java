package com.vodmordia.railwaysuntold.worldgen.head;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Permanent regression cases promoted from production {@code [VILLAGE-ASSIGN]} logs - the village-targeting
 * analogue of {@code ReplayCaseTest}/{@code CompileCaseTest}. Drop a pasted record into a new
 * {@code golden/vassign/<name>.vassign} file and run the tests; the golden snapshot of what
 * {@link VillageAssignmentReplay#choose} reproduces is generated on first run. Regenerate after an
 * intended change with {@code -Dgolden.update=true} and review the diff - a diff is a behaviour change for
 * a real reported assignment.
 *
 * To turn a bad in-game village choice into a regression test: copy the {@code VILLAGE-ASSIGN} block from
 * the log into a {@code .vassign} file here; the replay then reproduces that exact decision with no world.
 */
class VillageAssignCaseTest {

    private static final boolean UPDATE = Boolean.getBoolean("golden.update");
    private static final String GOLDEN_DIR = System.getProperty("golden.source.dir");

    @TestFactory
    Stream<DynamicTest> vassignCases() throws IOException {
        Path dir = caseDir();
        if (dir == null || Files.notExists(dir)) {
            return Stream.of(DynamicTest.dynamicTest("no vassign cases",
                    () -> { /* none committed yet - the harness is exercised by VillageAssignReplayTest */ }));
        }
        List<Path> cases;
        try (Stream<Path> files = Files.list(dir)) {
            cases = files.filter(p -> p.getFileName().toString().endsWith(".vassign")).sorted().toList();
        }
        return cases.stream().map(p -> {
            String name = stripExtension(p.getFileName().toString());
            return DynamicTest.dynamicTest(name, () -> runCase(dir, name, p));
        });
    }

    private void runCase(Path dir, String name, Path caseFile) throws IOException {
        VillageAssignRecord rec = VillageAssignRecord.parse(Files.readString(caseFile));
        BlockPos replayed = VillageAssignmentReplay.choose(rec);

        String actual = "chosen=" + (rec.chosen == null ? "-" : rec.chosen.toShortString())
                + "\nreplayed=" + (replayed == null ? "-" : replayed.toShortString()) + "\n";

        Path golden = dir.resolve(name + ".txt");
        if (UPDATE || Files.notExists(golden)) {
            Files.writeString(golden, actual);
        }
        assertEquals(Files.readString(golden), actual,
                () -> "Village-assign golden mismatch for '" + name + "'. If intended, re-run with "
                        + "-Dgolden.update=true and review the diff.");
    }

    private static Path caseDir() {
        return GOLDEN_DIR == null ? null : Path.of(GOLDEN_DIR, "vassign");
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }
}
