package com.equipo2b.scheduler.api.dto;

/**
 * Request para registrar un lote transaccional de maletas en DAY_TO_DAY.
 */
public record ShipmentRequestDTO(
    String clientId,
    String originId,
    String destinationId,
    int quantity,
    String ingressTime
) {}
