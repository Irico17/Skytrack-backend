package com.equipo2b.scheduler.service;

import com.equipo2b.scheduler.api.dto.SemaphoreDTO;
import com.equipo2b.scheduler.model.Solution;
import com.equipo2b.scheduler.monitoring.CapacityMonitor;
import com.equipo2b.scheduler.monitoring.TrafficLightIndicator;
import com.equipo2b.scheduler.monitoring.TrafficLightReport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Servicio de métricas de semáforo para la solución actual.
 */
@Service
public class MetricsService {

    @Autowired
    private SimulationService simulationService;

    /**
     * Calcula y retorna el estado del semáforo de la simulación activa.
     */
    public SemaphoreDTO getSemaphores(String simId) {
        return simulationService.getSemaphores(simId);
    }
}
