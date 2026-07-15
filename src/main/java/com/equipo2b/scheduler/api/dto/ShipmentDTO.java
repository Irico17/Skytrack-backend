package com.equipo2b.scheduler.api.dto;

/**
 * Lote de maletas registrado en una simulación.
 */
public record ShipmentDTO(
    String batchId,
    String clientId,
    String originId,
    String destinationId,
    int quantity,
    String ingressTime,
    String deadline,
    String status
) {}
