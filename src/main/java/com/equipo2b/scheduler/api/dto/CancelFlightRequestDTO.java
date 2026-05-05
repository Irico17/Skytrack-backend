package com.equipo2b.scheduler.api.dto;

/**
 * Request para cancelar un vuelo específico de un día.
 * El flightId base viene en la URL; el día aquí para construir el ID ajustado.
 */
public record CancelFlightRequestDTO(
    String day  // "2026-09-27" — día específico del vuelo a cancelar
) {}
