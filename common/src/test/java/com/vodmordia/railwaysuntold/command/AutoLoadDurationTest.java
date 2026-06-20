package com.vodmordia.railwaysuntold.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The {@code /railways autoload <duration>} parser: 20 ticks/sec, bare number = minutes. */
class AutoLoadDurationTest {

    @Test
    void parsesBareMinutes() {
        assertEquals(30L * 60 * 20, RailwaysUntoldCommands.parseDurationTicks("30"));
    }

    @Test
    void parsesUnitSuffixes() {
        assertEquals(30L * 60 * 20, RailwaysUntoldCommands.parseDurationTicks("30m"));
        assertEquals(60L * 60 * 20, RailwaysUntoldCommands.parseDurationTicks("1h"));
        assertEquals(45L * 20, RailwaysUntoldCommands.parseDurationTicks("45s"));
    }

    @Test
    void parsesCompound() {
        assertEquals((60L * 60 + 30 * 60) * 20, RailwaysUntoldCommands.parseDurationTicks("1h30m"));
        assertEquals((2L * 3600 + 5 * 60 + 10) * 20, RailwaysUntoldCommands.parseDurationTicks("2h5m10s"));
    }

    @Test
    void rejectsGarbage() {
        assertEquals(0, RailwaysUntoldCommands.parseDurationTicks("abc"));
        assertEquals(0, RailwaysUntoldCommands.parseDurationTicks(""));
        assertEquals(0, RailwaysUntoldCommands.parseDurationTicks("m"));
        assertEquals(0, RailwaysUntoldCommands.parseDurationTicks("30x"));
    }
}
