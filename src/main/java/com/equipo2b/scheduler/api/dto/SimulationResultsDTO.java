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
    List<DaySnapshotDTO> daySnapshots
) {
    /** Snapshot de métricas al cierre de cada día simulado */
    public record DaySnapshotDTO(
        int day,
        String date,
        int routesCompleted,
        int batchesOnTime,
        int batchesDelayed,
        int batchesCritical,
        double avgFitness,
        String collapseLevel
    ) {}
}
