package com.equipo2b.scheduler.api.dto;

/**
 * Request para iniciar una simulación.
 * scenario: "DAY_TO_DAY", "PERIOD_SIMULATION", "COLLAPSE_SIMULATION"
 * startDateTime: Instante de inicio ISO-8601 con offset o UTC,
 *                por ejemplo "2026-07-22T15:00:00Z".
 *                Los valores legacy sin zona se interpretan como UTC.
 * startDate: Compatibilidad con clientes antiguos, formato "yyyy-MM-dd".
 *            Para PERIOD_SIMULATION: filtra envíos en [inicio, inicio + 5 días].
 */
public record SimulationRequestDTO(
    String scenario,
    String startDate,
    String startDateTime
) {
    public String effectiveStartDateTime() {
        return startDateTime != null && !startDateTime.isBlank() ? startDateTime : startDate;
    }
}
