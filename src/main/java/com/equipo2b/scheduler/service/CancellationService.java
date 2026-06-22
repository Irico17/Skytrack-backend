package com.equipo2b.scheduler.service;

import com.equipo2b.scheduler.api.dto.ReplanResultDTO;
import com.equipo2b.scheduler.api.dto.DTOMapper;
import com.equipo2b.scheduler.execution.ReplanResult;
import com.equipo2b.scheduler.execution.SimulationController;
import com.equipo2b.scheduler.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
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

        SimulationController controller = simulationService.getActiveController();
        FlightPlan flightPlan = simulationService.getCurrentFlightPlan();

        // 1. Localizar el vuelo base en el plan para conocer su hora de salida y zona horaria de origen.
        Flight baseFlight = flightPlan.getAllFlights().stream()
            .filter(f -> f.flightId().equals(flightIdBase))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Vuelo no encontrado en el plan: " + flightIdBase));

        ZoneId originZone = baseFlight.origin().zoneId() != null
            ? baseFlight.origin().zoneId()
            : ZoneOffset.UTC;
        LocalTime departureTimeOfDay = baseFlight.departureTime().toLocalTime();

        // 2. Determinar el día objetivo según la regla de negocio (decisión PO):
        //    - cancelTime <= salida - 1h  → se cancela la instancia del MISMO día.
        //    - en caso contrario          → se cancela la instancia del DÍA SIGUIENTE
        //      (vuelo "inmediato siguiente"). Todo evaluado en hora local del aeropuerto de origen.
        ZonedDateTime cancelInstant = controller.getSimulatedTime();
        LocalDate targetDate;
        if (cancelInstant != null) {
            ZonedDateTime cancelLocal = cancelInstant.withZoneSameInstant(originZone);
            LocalDate cancelDate = cancelLocal.toLocalDate();
            ZonedDateTime departureToday = ZonedDateTime.of(cancelDate, departureTimeOfDay, originZone);
            targetDate = !cancelLocal.isAfter(departureToday.minusHours(1))
                ? cancelDate
                : cancelDate.plusDays(1);
            System.out.printf("🚫 Cancelación: ahora(local %s)=%s, salida=%s → día objetivo=%s%n",
                originZone, cancelLocal.toLocalTime(), departureTimeOfDay, targetDate);
        } else {
            // Sin reloj activo: usar el día provisto por el cliente como respaldo.
            targetDate = LocalDate.parse(day, DateTimeFormatter.ISO_LOCAL_DATE);
        }

        // 3. Calcular offset de días respecto a la fecha base del plan y construir el ID ajustado.
        long dayOffset = ChronoUnit.DAYS.between(FLIGHT_BASE_DATE, targetDate);
        String adjustedFlightId = flightIdBase + "-D" + dayOffset;

        // 4. Marcar como cancelado en FlightPlan (RouteGenerator ya no lo verá) y replanificar.
        flightPlan.cancelFlight(adjustedFlightId);
        System.out.printf("🚫 Vuelo cancelado: %s (día %s = offset D%d)%n",
            adjustedFlightId, targetDate, dayOffset);

        ReplanResult result = controller.registerCancellation(adjustedFlightId);
        return DTOMapper.toReplanResultDTO(adjustedFlightId, result);
    }
}
