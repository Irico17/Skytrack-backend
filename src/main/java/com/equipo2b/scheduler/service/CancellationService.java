package com.equipo2b.scheduler.service;

import com.equipo2b.scheduler.api.dto.ReplanResultDTO;
import com.equipo2b.scheduler.api.dto.DTOMapper;
import com.equipo2b.scheduler.execution.ReplanResult;
import com.equipo2b.scheduler.execution.SimulationController;
import com.equipo2b.scheduler.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Servicio de cancelación de vuelos durante una simulación activa.
 *
 * Flujo:
 * 1. Recibe flightId base + día ("2026-09-27")
 * 2. Calcula el offset de días respecto al inicio del plan (2026-01-01)
 * 3. Construye el ID ajustado: "FL001-D240"
 * 4. Registra la cancelación en FlightPlan (filtro automático en futuras búsquedas)
 * 5. Delega replanificación a SimulationController.registerCancellation()
 */
@Service
public class CancellationService {

    @Autowired
    private SimulationService simulationService;

    private static final LocalDate FLIGHT_BASE_DATE = LocalDate.of(2026, 1, 1);

    /**
     * Cancela una instancia de vuelo específica de un día dado y replanifica.
     *
     * @param simId       ID de la simulación activa
     * @param flightIdBase ID base del vuelo (sin sufijo -Dxx), ej: "SKBO-SEQM-03:34"
     * @param day         Día en formato "yyyy-MM-dd", ej: "2026-09-27"
     * @return DTO con resultado de replanificación
     */
    public ReplanResultDTO cancelFlight(String simId, String flightIdBase, String day) {
        if (!simulationService.hasActiveSimulation(simId)) {
            throw new IllegalArgumentException("No hay simulación activa con id: " + simId);
        }

        // 1. Calcular offset de días respecto a la fecha base del plan de vuelos
        LocalDate cancelDay = LocalDate.parse(day, DateTimeFormatter.ISO_LOCAL_DATE);
        long dayOffset = ChronoUnit.DAYS.between(FLIGHT_BASE_DATE, cancelDay);

        // 2. Construir el flight ID ajustado con sufijo de día
        String adjustedFlightId = flightIdBase + "-D" + dayOffset;

        // 3. Marcar como cancelado en FlightPlan (RouteGenerator ya no lo verá)
        FlightPlan flightPlan = simulationService.getCurrentFlightPlan();
        flightPlan.cancelFlight(adjustedFlightId);

        System.out.printf("🚫 Vuelo cancelado: %s (día %s = offset D%d)%n",
            adjustedFlightId, day, dayOffset);

        // 4. Delegar replanificación al controller activo
        // registerCancellation busca el vuelo en el plan, identifica lotes afectados
        // y ejecuta Replanner con TabuSearch
        SimulationController controller = simulationService.getActiveController();
        ReplanResult result = controller.registerCancellation(adjustedFlightId);
        return DTOMapper.toReplanResultDTO(adjustedFlightId, result);
    }
}
