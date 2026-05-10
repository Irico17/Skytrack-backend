package com.equipo2b.scheduler.api.dto;

/**
 * Request para iniciar una simulación.
 * scenario: "DAY_TO_DAY", "PERIOD_SIMULATION", "COLLAPSE_SIMULATION"
 * startDate: Fecha de inicio de la ventana de datos, formato "yyyy-MM-dd" (opcional, null = usar todos los datos).
 *            Para PERIOD_SIMULATION: filtra envíos en [startDate, startDate + 5 días].
 */
public record SimulationRequestDTO(
    String scenario,
    String startDate
) {}
