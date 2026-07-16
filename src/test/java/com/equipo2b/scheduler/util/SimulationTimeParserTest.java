package com.equipo2b.scheduler.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SimulationTimeParserTest {

    @Test
    void offsetAndUtcRepresentTheSameInstant() {
        var fromPeru = SimulationTimeParser.parseToUtc("2026-07-22T10:00:00-05:00");
        var fromUtc = SimulationTimeParser.parseToUtc("2026-07-22T15:00:00Z");

        assertEquals(Instant.parse("2026-07-22T15:00:00Z"), fromPeru.toInstant());
        assertEquals(fromUtc.toInstant(), fromPeru.toInstant());
        assertEquals(ZoneOffset.UTC, fromPeru.getZone());
    }

    @Test
    void legacyLocalDateTimeRemainsUtcForCompatibility() {
        var parsed = SimulationTimeParser.parseToUtc("2026-07-22T10:00");

        assertEquals(Instant.parse("2026-07-22T10:00:00Z"), parsed.toInstant());
    }

    @Test
    void invalidInputIsRejected() {
        assertThrows(
            IllegalArgumentException.class,
            () -> SimulationTimeParser.parseToUtc("22/07/2026 10:00")
        );
    }
}
