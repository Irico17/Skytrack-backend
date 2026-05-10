package com.equipo2b.scheduler.api.dto;

import java.util.List;

/**
 * Actualizacion continua del inventario de almacenes durante la simulacion.
 */
public record StorageUpdateDTO(
    String type,
    String simulationId,
    int cycle,
    String simulatedTime,
    double daysElapsed,
    List<CycleUpdateDTO.AirportCapacityDTO> airportCapacities
) {}