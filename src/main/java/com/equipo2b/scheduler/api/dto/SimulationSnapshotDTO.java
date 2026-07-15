package com.equipo2b.scheduler.api.dto;

/**
 * Snapshot ligero para clientes que llegan tarde a una simulacion compartida.
 */
public record SimulationSnapshotDTO(
    ActiveSimulationDTO activeSimulation,
    SimulationStatusDTO status
) {}