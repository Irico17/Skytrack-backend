package com.equipo2b.scheduler.api.dto;

import java.util.List;

/**
 * Actualización por ciclo enviada al frontend vía WebSocket.
 * Emitida al finalizar cada ciclo de planificación con tipo "CYCLE_UPDATE".
 */
public record CycleUpdateDTO(
    String type,             // "CYCLE_UPDATE"
    String simulationId,
    int cycle,
    String simulatedTime,    // ISO-8601 — tiempo simulado al inicio del ciclo
    double daysElapsed,      // 0.0 – 5.0
    boolean simulationComplete,
    double fitness,
    int batchesProcessed,
    int batchesFailed,
    int totalRoutes,
    int totalBags,
    SemaphoreDTO semaphores,
    BatchSummaryDTO batchSummary,
    List<ActiveFlightDTO> activeFlights,      // vuelos con maletas asignadas
    List<AirportCapacityDTO> airportCapacities // ocupación actual de almacenes
) {
    /**
     * Resumen del estado de los lotes en el ciclo actual.
     */
    public record BatchSummaryDTO(
        int onTime,      // Lotes que cumplen SLA
        int delayed,     // Lotes que no cumplen SLA
        int unrouted     // Lotes sin ruta asignada
    ) {}

    /**
     * Vuelo activo con maletas asignadas por el planificador.
     */
    public record ActiveFlightDTO(
        String flightId,
        String originId,
        String destinationId,
        String departureTime,    // ISO-8601
        String arrivalTime,      // ISO-8601
        int bagsCount,
        boolean meetsSla
    ) {}

    /**
     * Ocupación actual del almacén de un aeropuerto.
     * Calculada desde los StorageEvents de la solución.
     */
    public record AirportCapacityDTO(
        String airportId,
        int currentBags,         // maletas en almacén ahora
        int maxCapacity,         // capacidad máxima del almacén
        double occupancyRatio    // currentBags / maxCapacity (0.0 – 1.0+)
    ) {}
}
