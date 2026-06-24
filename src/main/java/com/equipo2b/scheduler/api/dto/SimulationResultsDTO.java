package com.equipo2b.scheduler.api.dto;

import java.util.List;

/**
 * Resumen de resultados de una simulación de 5 días.
 * Se serializa a JSON en data/results/sim_{id}.json al finalizar.
 */
public record SimulationResultsDTO(
    String simulationId,
    String scenario,
    String startDate,
    String endDate,
    String completedAt,           // ISO-8601 timestamp
    double fitness,
    int totalBatches,
    int routedBatches,
    int unroutableBatches,
    double slaCompliancePercent,
    int totalCycles,
    String algorithmUsed,         // "GATS" o "TABU_PURE"
    List<DaySnapshotDTO> daySnapshots,
    CollapseInfoDTO collapseInfo  // null salvo en COLLAPSE_SIMULATION que colapsó
) {
    /** Snapshot de métricas al cierre de cada día simulado */
    public record DaySnapshotDTO(
        int day,
        String date,
        int routesCompleted,
        int totalBags,
        int batchesOnTime,
        int batchesDelayed,
        int batchesCritical,
        double avgFitness,
        String collapseLevel,
        int avgOccupancy,
        int replanned
    ) {}

    /** Condiciones bajo las que se declaró el colapso (cuándo, qué lo provocó y por qué). */
    public record CollapseInfoDTO(
        String causeCode,         // WAREHOUSE_SATURATION, UNSERVICEABLE_BATCHES, CAPACITY_SATURATION, ALGORITHM_FITNESS
        String causeLabel,        // etiqueta legible
        String reason,            // por qué se consideró colapso (con métricas)
        String detectedAtReal,    // ISO-8601, reloj real
        String detectedAtSim,     // ISO-8601, tiempo simulado
        double occupancyPct,
        double unserviceablePct,
        int criticalAirports,
        int totalAirports,
        int cycle
    ) {}
}
