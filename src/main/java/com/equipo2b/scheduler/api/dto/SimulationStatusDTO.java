package com.equipo2b.scheduler.api.dto;

/**
 * Estado actual de una simulación, enviado por REST y WebSocket.
 */
public record SimulationStatusDTO(
    String simulationId,
    String status,           // RUNNING, PAUSED, STOPPED, COMPLETED
    String scenario,
    int currentCycle,
    String simulatedTime,    // ISO-8601
    int batchesProcessed,
    int batchesFailed,
    int batchesPending,
    double currentFitness,
    String collapseLevel,    // NORMAL, WARNING, CRITICAL, COLLAPSED
    boolean isCollapsed,
    SemaphoreDTO semaphores
) {}
