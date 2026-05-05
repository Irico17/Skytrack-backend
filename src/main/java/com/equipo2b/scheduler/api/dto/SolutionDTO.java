package com.equipo2b.scheduler.api.dto;

import java.util.List;

/**
 * Solución completa con todas las rutas asignadas.
 */
public record SolutionDTO(
    int totalRoutes,
    int totalBags,
    double fitness,
    int routesMeetingSLA,
    double slaCompliancePercent,
    List<RouteDTO> routes
) {}
