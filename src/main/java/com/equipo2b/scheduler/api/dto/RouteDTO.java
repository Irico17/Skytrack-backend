package com.equipo2b.scheduler.api.dto;

import java.util.List;

/**
 * Ruta asignada a un lote de maletas.
 */
public record RouteDTO(
    String batchId,
    String clientId,
    String originId,
    String destinationId,
    int quantity,
    boolean meetsSLA,
    String slaSlack,         // ISO-8601 duration, ej: "PT2H30M"
    String finalArrivalTime, // ISO-8601 — aterrizaje del último vuelo, NO la entrega al cliente
    String deliveredTime,    // ISO-8601 — finalArrivalTime + ventana de recojo (AssignedRoute.getDeliveredTime())
    List<FlightSegmentDTO> flights
) {}
