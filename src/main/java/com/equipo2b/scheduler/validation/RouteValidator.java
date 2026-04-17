package com.equipo2b.scheduler.validation;

import com.equipo2b.scheduler.model.*;

import java.time.Duration;
import java.util.*;

/**
 * Valida soluciones verificando todas las restricciones duras.
 * 
 * **Validates: Requirements 13.1, 18.1**
 */
public class RouteValidator {
    private final AirportManager airportManager;
    
    public RouteValidator(AirportManager airportManager) {
        this.airportManager = Objects.requireNonNull(airportManager, "AirportManager cannot be null");
    }
    
    /**
     * Valida una solución completa.
     * Ejecuta todas las validaciones y retorna reporte completo.
     * 
     * @param solution Solución a validar
     * @return ValidationReport con todas las violaciones encontradas
     * 
     * **Validates: Requirements 13.1, 13.2, 13.4, 13.5**
     */
    public ValidationReport validate(Solution solution) {
        ValidationReport report = new ValidationReport();
        
        validateFlightCapacities(solution, report);
        validateStorageCapacities(solution, report);
        validateSLACompliance(solution, report);
        validateLayoverTimes(solution, report);
        validateFlightDurations(solution, report);
        
        return report;
    }
    
    /**
     * Valida capacidades de vuelos.
     * 
     * @param solution Solución a validar
     * @param report Reporte donde agregar violaciones
     * 
     * **Validates: Requirements 2.3, 13.2**
     */
    private void validateFlightCapacities(Solution solution, ValidationReport report) {
        // Agrupar maletas por vuelo
        Map<String, Integer> loadByFlight = new HashMap<>();
        
        for (AssignedRoute route : solution.getRoutes().values()) {
            for (Flight flight : route.getFlights()) {
                String flightKey = flight.flightId();
                loadByFlight.merge(flightKey, route.getBatch().quantity(), Integer::sum);
            }
        }
        
        // Verificar excesos
        for (AssignedRoute route : solution.getRoutes().values()) {
            for (Flight flight : route.getFlights()) {
                int load = loadByFlight.getOrDefault(flight.flightId(), 0);
                int excess = Math.max(0, load - flight.capacity());
                
                if (excess > 0) {
                    report.addViolation(new Violation(
                        ViolationType.FLIGHT_CAPACITY,
                        String.format("Flight %s exceeds capacity: %d/%d bags",
                            flight.flightId(), load, flight.capacity()),
                        excess
                    ));
                }
            }
        }
    }
    
    /**
     * Valida capacidades de almacenes.
     * 
     * @param solution Solución a validar
     * @param report Reporte donde agregar violaciones
     * 
     * **Validates: Requirements 3.2, 3.3, 13.2**
     */
    private void validateStorageCapacities(Solution solution, ValidationReport report) {
        // Recopilar todos los eventos de almacenamiento
        List<StorageEvent> allEvents = new ArrayList<>();
        for (AssignedRoute route : solution.getRoutes().values()) {
            allEvents.addAll(route.getStorageEvents());
        }
        
        // Ordenar por timestamp
        allEvents.sort(Comparator.comparing(StorageEvent::timestamp));
        
        // Simular ocupación de cada aeropuerto
        Map<String, Integer> currentOccupancy = new HashMap<>();
        
        for (StorageEvent event : allEvents) {
            String airportId = event.airport().id();
            int current = currentOccupancy.getOrDefault(airportId, 0);
            
            if (event.type() == StorageEventType.ARRIVAL) {
                current += event.quantity();
            } else {
                current -= event.quantity();
            }
            
            currentOccupancy.put(airportId, current);
            
            // Verificar exceso
            int capacity = event.airport().storageCapacity();
            int excess = Math.max(0, current - capacity);
            
            if (excess > 0) {
                report.addViolation(new Violation(
                    ViolationType.STORAGE_CAPACITY,
                    String.format("Airport %s storage exceeds capacity at %s: %d/%d bags",
                        airportId, event.timestamp(), current, capacity),
                    excess
                ));
            }
        }
    }
    
    /**
     * Valida cumplimiento de SLA.
     * 
     * @param solution Solución a validar
     * @param report Reporte donde agregar violaciones
     * 
     * **Validates: Requirements 5.6, 13.2**
     */
    private void validateSLACompliance(Solution solution, ValidationReport report) {
        for (AssignedRoute route : solution.getRoutes().values()) {
            if (!route.meetsSLA()) {
                Duration slack = route.getSLASlack();
                long hoursLate = Math.abs(slack.toHours());
                
                report.addViolation(new Violation(
                    ViolationType.SLA_VIOLATION,
                    String.format("Batch %s violates SLA by %d hours",
                        route.getBatch().batchId(), hoursLate),
                    hoursLate
                ));
            }
        }
    }
    
    /**
     * Valida tiempos de escala entre vuelos.
     * 
     * @param solution Solución a validar
     * @param report Reporte donde agregar violaciones
     * 
     * **Validates: Requirements 6.2, 13.2**
     */
    private void validateLayoverTimes(Solution solution, ValidationReport report) {
        for (AssignedRoute route : solution.getRoutes().values()) {
            List<Flight> flights = route.getFlights();
            
            for (int i = 0; i < flights.size() - 1; i++) {
                Flight current = flights.get(i);
                Flight next = flights.get(i + 1);
                
                Duration layover = Duration.between(current.arrivalTime(), next.departureTime());
                long minutes = layover.toMinutes();
                
                if (minutes < 10) {
                    report.addViolation(new Violation(
                        ViolationType.LAYOVER_VIOLATION,
                        String.format("Insufficient layover between %s and %s: %d minutes (min: 10)",
                            current.flightId(), next.flightId(), minutes),
                        10 - minutes
                    ));
                }
            }
        }
    }
    
    /**
     * Valida duraciones de vuelos según tipo.
     * 
     * @param solution Solución a validar
     * @param report Reporte donde agregar violaciones
     * 
     * **Validates: Requirements 4.1, 4.2, 4.3, 13.2**
     */
    private void validateFlightDurations(Solution solution, ValidationReport report) {
        for (AssignedRoute route : solution.getRoutes().values()) {
            for (Flight flight : route.getFlights()) {
                Duration duration = Duration.between(flight.departureTime(), flight.arrivalTime());
                long hours = duration.toHours();
                
                long expectedHours = flight.type() == FlightType.INTRACONTINENTAL ? 12 : 24;
                
                if (hours != expectedHours) {
                    report.addViolation(new Violation(
                        ViolationType.FLIGHT_DURATION,
                        String.format("Flight %s has incorrect duration: %d hours (expected: %d)",
                            flight.flightId(), hours, expectedHours),
                        Math.abs(hours - expectedHours)
                    ));
                }
            }
        }
    }
}
