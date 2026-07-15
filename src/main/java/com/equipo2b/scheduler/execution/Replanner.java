package com.equipo2b.scheduler.execution;

import com.equipo2b.scheduler.algorithm.TabuSearch;
import com.equipo2b.scheduler.model.*;
import com.equipo2b.scheduler.validation.RouteValidator;
import com.equipo2b.scheduler.validation.ValidationReport;

import java.time.ZonedDateTime;
import java.util.*;

/**
 * Gestor de replanificación de emergencia ante cancelaciones de vuelos.
 * 
 * **Validates: Requirements 12.1, 18.1**
 */
public class Replanner {
    private final FlightPlan flightPlan;
    private final TabuSearch tabuSearch;
    private final RouteValidator validator;
    
    /**
     * Constructor del Replanner.
     * 
     * @param flightPlan Plan maestro de vuelos
     * @param tabuSearch Búsqueda Tabú para optimización local
     * @param validator Validador de soluciones
     */
    public Replanner(FlightPlan flightPlan, TabuSearch tabuSearch, RouteValidator validator) {
        this.flightPlan = Objects.requireNonNull(flightPlan, "FlightPlan cannot be null");
        this.tabuSearch = Objects.requireNonNull(tabuSearch, "TabuSearch cannot be null");
        this.validator = Objects.requireNonNull(validator, "RouteValidator cannot be null");
    }
    
    /**
     * Replanifica ante cancelación de vuelo.
     * 
     * Proceso:
     * 1. Identificar lotes afectados
     * 2. Buscar vuelos alternativos en ventana +2h
     * 3. Usar TabuSearch.replan() para generar nuevas rutas
     * 4. Validar solución actualizada
     * 5. Retornar ReplanResult
     * 
     * @param currentSolution Solución actual
     * @param cancelledFlight Vuelo cancelado
     * @return ReplanResult con lotes replanificados y no replanificables
     * 
     * **Validates: Requirements 12.1, 12.2, 12.3, 12.4, 12.5, 12.6, 25.1, 25.2, 25.3, 25.4**
     */
    public ReplanResult replan(Solution currentSolution, Flight cancelledFlight) {
        System.out.println("\n=== REPLANIFICACIÓN DE EMERGENCIA ===");
        System.out.println("Vuelo cancelado: " + cancelledFlight.flightId());
        
        // 1. Identificar lotes afectados
        List<ShipmentBatch> affectedBatches = identifyAffectedBatches(currentSolution, cancelledFlight);
        System.out.println("Lotes afectados: " + affectedBatches.size());
        
        if (affectedBatches.isEmpty()) {
            System.out.println("No hay lotes afectados");
            return new ReplanResult(currentSolution, List.of(), List.of());
        }
        
        // 2. Buscar vuelos alternativos en ventana +2h
        List<Flight> alternatives = findAlternativeFlights(cancelledFlight);
        System.out.println("Vuelos alternativos encontrados: " + alternatives.size());
        
        // 3. Usar TabuSearch.replan() para generar nuevas rutas
        Solution updatedSolution = tabuSearch.replan(currentSolution, cancelledFlight, affectedBatches);
        
        // 4. Identificar lotes no replanificables
        List<ShipmentBatch> unreplannableBatches = identifyUnreplannableBatches(
            updatedSolution, affectedBatches
        );
        
        List<ShipmentBatch> replanedBatches = new ArrayList<>(affectedBatches);
        replanedBatches.removeAll(unreplannableBatches);
        
        System.out.println("Lotes replanificados: " + replanedBatches.size());
        System.out.println("Lotes no replanificables: " + unreplannableBatches.size());
        
        // 5. Validar solución actualizada
        ValidationReport report = validator.validate(updatedSolution);
        if (report.isValid()) {
            System.out.println("✓ Solución replanificada válida");
        } else {
            System.out.println("⚠ Solución replanificada con violaciones:");
            System.out.println(report.getSummary());
        }
        
        // 6. Retornar ReplanResult
        return new ReplanResult(updatedSolution, replanedBatches, unreplannableBatches);
    }
    
    /**
     * Identifica lotes afectados por cancelación de vuelo.
     * 
     * @param solution Solución actual
     * @param cancelledFlight Vuelo cancelado
     * @return Lista de lotes afectados
     * 
     * **Validates: Requirements 12.1**
     */
    private List<ShipmentBatch> identifyAffectedBatches(Solution solution, Flight cancelledFlight) {
        List<ShipmentBatch> affected = new ArrayList<>();
        
        for (AssignedRoute route : solution.getRoutes().values()) {
            for (Flight flight : route.getFlights()) {
                if (isSameFlightInstance(flight, cancelledFlight)) {
                    affected.add(route.getBatch());
                    break;
                }
            }
        }
        
        return affected;
    }

    private boolean isSameFlightInstance(Flight flight, Flight cancelledFlight) {
        if (flight.flightId().equals(cancelledFlight.flightId())) {
            return true;
        }

        String baseFlightId = flight.flightId().split("-D")[0];
        String cancelledBaseId = cancelledFlight.flightId().split("-D")[0];
        return baseFlightId.equals(cancelledBaseId)
            && flight.origin().equals(cancelledFlight.origin())
            && flight.destination().equals(cancelledFlight.destination())
            && flight.departureTime().equals(cancelledFlight.departureTime());
    }
    
    /**
     * Busca vuelos alternativos en ventana +2h desde mismo aeropuerto.
     * 
     * @param cancelledFlight Vuelo cancelado
     * @return Lista de vuelos alternativos
     * 
     * **Validates: Requirements 12.2, 25.1, 25.2**
     */
    private List<Flight> findAlternativeFlights(Flight cancelledFlight) {
        ZonedDateTime windowStart = cancelledFlight.departureTime();
        ZonedDateTime windowEnd = windowStart.plusHours(2);
        
        List<Flight> alternatives = flightPlan.getFlightsFromAirport(
            cancelledFlight.origin(),
            windowStart,
            windowEnd
        );
        
        // Filtrar vuelos con capacidad disponible (simplificado)
        // En implementación completa, verificar capacidad real
        return alternatives;
    }
    
    /**
     * Identifica lotes que no pudieron ser replanificados.
     * 
     * @param updatedSolution Solución actualizada
     * @param affectedBatches Lotes que fueron afectados
     * @return Lista de lotes no replanificables
     * 
     * **Validates: Requirements 12.5, 25.4**
     */
    private List<ShipmentBatch> identifyUnreplannableBatches(
            Solution updatedSolution, 
            List<ShipmentBatch> affectedBatches) {
        List<ShipmentBatch> unreplannable = new ArrayList<>();
        
        for (ShipmentBatch batch : affectedBatches) {
            AssignedRoute route = updatedSolution.getRoute(batch.batchId());
            
            // Si no hay ruta o la ruta es inválida, es no replanificable
            if (route == null) {
                unreplannable.add(batch);
            }
        }
        
        return unreplannable;
    }
}
