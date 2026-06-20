package com.vodmordia.railwaysuntold.worldgen.placement.support;

import com.vodmordia.railwaysuntold.worldgen.placement.support.SupportProfileRecord.Band;
import com.vodmordia.railwaysuntold.worldgen.placement.support.SupportProfileRecord.Decision;
import com.vodmordia.railwaysuntold.worldgen.placement.support.SupportProfileRecord.Reason;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks the {@code [SUPPORT]} record format: serializing, parsing, and re-serializing must be
 * byte-identical, and the parsed fields must match the original. Pure data, no ServerLevel.
 */
class SupportProfileRoundTripTest {

    @Test
    void caveBailRoundTripsByteForByte() {
        SupportProfileRecord rec = new SupportProfileRecord(
                231, 67, -24, Decision.NONE, Reason.DEEP_GAP,
                List.of(new Band('S', 2), new Band('A', 18), new Band('S', 4)));

        String serialized = rec.serialize();
        SupportProfileRecord parsed = SupportProfileRecord.parse(serialized);

        assertEquals(serialized, parsed.serialize(), "serialize -> parse -> serialize must be stable");
        assertEquals(231, parsed.trackX);
        assertEquals(67, parsed.trackY);
        assertEquals(-24, parsed.trackZ);
        assertEquals(Decision.NONE, parsed.decision);
        assertEquals(Reason.DEEP_GAP, parsed.reason);
        assertEquals(rec.bands, parsed.bands);
    }

    @Test
    void bridgeDeckingWithUnspecifiedReasonUsesDash() {
        SupportProfileRecord rec = new SupportProfileRecord(
                10, 70, -5, Decision.BRIDGE_DECKING, Reason.UNSPECIFIED,
                List.of(new Band('A', 6), new Band('S', 10)));

        String serialized = rec.serialize();

        assertEquals(true, serialized.contains("reason -"), "UNSPECIFIED serializes as '-'");
        assertEquals(serialized, SupportProfileRecord.parse(serialized).serialize());
    }

    @Test
    void emptyColumnSerializesZeroBands() {
        SupportProfileRecord rec = new SupportProfileRecord(
                0, 64, 0, Decision.TERRAIN_FILL, Reason.UNSPECIFIED, List.of());

        SupportProfileRecord parsed = SupportProfileRecord.parse(rec.serialize());

        assertEquals(0, parsed.bands.size());
        assertEquals(rec.serialize(), parsed.serialize());
    }
}
