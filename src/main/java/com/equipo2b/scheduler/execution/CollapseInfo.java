package com.equipo2b.scheduler.execution;

import java.time.ZonedDateTime;

/**
 * Condiciones bajo las que se declaró el colapso del sistema.
 *
 * <p>Se captura en el instante exacto en que {@link SimulationController} detecta el
 * colapso, para poder "publicar" en el reporte: cuándo ocurrió (tiempo real y simulado),
 * qué lo provocó (la causa concreta) y por qué se consideró colapso (las métricas que
 * cruzaron el umbral).</p>
 *
 * @param causeCode        Código de causa (enum-like): WAREHOUSE_SATURATION,
 *                         UNSERVICEABLE_BATCHES, CAPACITY_SATURATION, ALGORITHM_FITNESS.
 * @param causeLabel       Etiqueta legible de la causa (es-PE).
 * @param reason           Motivo detallado: por qué se consideró colapso (con métricas).
 * @param detectedAtReal   Marca de tiempo real (reloj de pared) en que se detectó.
 * @param detectedAtSim    Marca de tiempo simulado en que se detectó.
 * @param occupancyPct     Ocupación promedio del sistema en el momento del colapso (0-100).
 * @param unserviceablePct Porcentaje de lotes no atendibles (0-100).
 * @param criticalAirports Nº de aeropuertos en estado crítico (≥90% de su capacidad).
 * @param totalAirports    Nº total de aeropuertos evaluados.
 * @param cycle            Ciclo de planificación en el que se detectó.
 */
public record CollapseInfo(
    String causeCode,
    String causeLabel,
    String reason,
    ZonedDateTime detectedAtReal,
    ZonedDateTime detectedAtSim,
    double occupancyPct,
    double unserviceablePct,
    int criticalAirports,
    int totalAirports,
    int cycle
) {}
