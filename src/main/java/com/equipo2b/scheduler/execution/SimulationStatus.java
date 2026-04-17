package com.equipo2b.scheduler.execution;

import com.equipo2b.scheduler.monitoring.CollapseLevel;

import java.time.ZonedDateTime;

/**
 * Estado actual de una simulación en ejecución.
 * 
 * Proporciona información sobre el progreso y métricas de la simulación.
 * 
 * @param isRunning Si la simulación está en ejecución
 * @param isPaused Si la simulación está pausada
 * @param currentCycle Ciclo actual de planificación
 * @param simulatedTime Tiempo simulado actual
 * @param batchesProcessed Número de lotes procesados
 * @param batchesFailed Número de lotes fallidos
 * @param currentFitness Fitness actual de la solución
 * @param collapseLevel Nivel de colapso actual
 */
public record SimulationStatus(
    boolean isRunning,
    boolean isPaused,
    int currentCycle,
    ZonedDateTime simulatedTime,
    int batchesProcessed,
    int batchesFailed,
    double currentFitness,
    CollapseLevel collapseLevel
) {
    /**
     * Obtiene una representación en texto del estado.
     * 
     * @return Resumen del estado
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Estado: ");
        if (isRunning) {
            sb.append(isPaused ? "PAUSADO" : "EN EJECUCIÓN");
        } else {
            sb.append("DETENIDO");
        }
        sb.append("\n");
        sb.append("Ciclo: ").append(currentCycle).append("\n");
        sb.append("Tiempo simulado: ").append(simulatedTime).append("\n");
        sb.append("Lotes procesados: ").append(batchesProcessed).append("\n");
        sb.append("Lotes fallidos: ").append(batchesFailed).append("\n");
        sb.append("Fitness: ").append(String.format("%.2f", currentFitness)).append("\n");
        sb.append("Nivel de colapso: ").append(collapseLevel);
        return sb.toString();
    }
}
