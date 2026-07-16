package com.equipo2b.scheduler.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Contrato temporal de la simulación.
 *
 * <p>Los clientes nuevos deben enviar un instante ISO-8601 con offset o zona
 * ({@code 2026-07-22T15:00:00Z} o {@code 2026-07-22T10:00:00-05:00}).
 * Internamente el motor se normaliza a UTC. Los formatos sin zona se conservan
 * temporalmente por compatibilidad y se interpretan como UTC, igual que antes.</p>
 */
public final class SimulationTimeParser {

    private SimulationTimeParser() {
    }

    public static ZonedDateTime parseToUtc(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String trimmed = value.trim();

        try {
            return ZonedDateTime.parse(trimmed, DateTimeFormatter.ISO_ZONED_DATE_TIME)
                .withZoneSameInstant(ZoneOffset.UTC);
        } catch (Exception ignored) {
            // Un ISO con offset, pero sin ZoneId, se intenta abajo.
        }

        try {
            return OffsetDateTime.parse(trimmed, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                .atZoneSameInstant(ZoneOffset.UTC);
        } catch (Exception ignored) {
            // Formatos legacy sin zona se intentan abajo.
        }

        try {
            if (trimmed.contains("T")) {
                return LocalDateTime.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .atZone(ZoneOffset.UTC);
            }
            return LocalDate.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE)
                .atStartOfDay(ZoneOffset.UTC);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Fecha/hora inválida. Use ISO-8601 con zona, por ejemplo "
                    + "2026-07-22T15:00:00Z o 2026-07-22T10:00:00-05:00",
                e
            );
        }
    }
}
