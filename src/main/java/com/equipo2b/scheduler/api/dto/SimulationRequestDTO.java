package com.equipo2b.scheduler.api.dto;

/**
 * Request para iniciar una simulación.
 * scenario: "DAY_TO_DAY", "PERIOD_SIMULATION", "COLLAPSE_SIMULATION"
 * startDateTime: Fecha/hora de inicio de la ventana de datos, formato "yyyy-MM-ddTHH:mm".
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
