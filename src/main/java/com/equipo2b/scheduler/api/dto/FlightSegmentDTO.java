package com.equipo2b.scheduler.api.dto;

/**
 * Segmento de vuelo dentro de una ruta.
 */
public record FlightSegmentDTO(
    String flightId,
    String originId,
    String destinationId,
    String departureTime,  // ISO-8601
    String arrivalTime,    // ISO-8601
    int capacity
) {}
