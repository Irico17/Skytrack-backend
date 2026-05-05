package com.equipo2b.scheduler.api.dto;

/**
 * Request para iniciar una simulación.
 * scenario: "DAY_TO_DAY", "PERIOD_SIMULATION", "COLLAPSE_SIMULATION"
 */
public record SimulationRequestDTO(
    String scenario
) {}
