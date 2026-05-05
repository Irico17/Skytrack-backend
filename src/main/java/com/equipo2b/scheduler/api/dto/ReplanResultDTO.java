package com.equipo2b.scheduler.api.dto;

import java.util.List;

/**
 * Resultado de una replanificación por cancelación de vuelo.
 */
public record ReplanResultDTO(
    String cancelledFlightId,
    int affectedBatches,
    int replannedBatches,
    int unreplannableBatches,
    double newFitness,
    List<String> unreplannableBatchIds
) {}
