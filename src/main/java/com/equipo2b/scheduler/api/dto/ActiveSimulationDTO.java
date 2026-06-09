package com.equipo2b.scheduler.api.dto;

/**
 * Estado resumido de la unica simulacion compartida en memoria.
 */
public record ActiveSimulationDTO(
    String simulationId,
    String scenario,
    String scenarioDescription,
    String status,
    String startDateTime,
    String simulatedTime,
    int currentCycle,
    double daysElapsed,
    int K,
    int Ta,
    int Sa,
    int Sc,
    int connectedClients,
    String startedAt,
    String finishedAt,
    boolean canJoin
) {}