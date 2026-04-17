package com.equipo2b.scheduler.model;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Representa un vuelo programado con horarios en ZonedDateTime y capacidad.
 * Inmutable.
 * 
 * Validaciones:
 * - Tipo de vuelo según continentes (mismo = INTRACONTINENTAL, diferente = INTERCONTINENTAL)
 * - Capacidad según tipo (150-250 intra, 150-400 inter)
 * - Duración según tipo (12h intra, 24h inter)
 * 
 * @param flightId ID único del vuelo
 * @param origin Aeropuerto origen
 * @param destination Aeropuerto destino
 * @param departureTime Hora de salida en huso horario del origen
 * @param arrivalTime Hora de llegada en huso horario del destino
 * @param capacity Capacidad de carga (150-250 intra, 150-400 inter)
 * @param type INTRACONTINENTAL o INTERCONTINENTAL
 */
public record Flight(
    String flightId,
    Airport origin,
    Airport destination,
    ZonedDateTime departureTime,
    ZonedDateTime arrivalTime,
    int capacity,
    FlightType type
) {
    /**
     * Constructor compacto con validaciones.
     */
    public Flight {
        Objects.requireNonNull(flightId, "Flight ID cannot be null");
        Objects.requireNonNull(origin, "Origin airport cannot be null");
        Objects.requireNonNull(destination, "Destination airport cannot be null");
        Objects.requireNonNull(departureTime, "Departure time cannot be null");
        Objects.requireNonNull(arrivalTime, "Arrival time cannot be null");
        Objects.requireNonNull(type, "Flight type cannot be null");
        
        // Validar tipo de vuelo según continentes
        boolean sameContinents = origin.continent() == destination.continent();
        FlightType expectedType = sameContinents ? FlightType.INTRACONTINENTAL : FlightType.INTERCONTINENTAL;
        if (type != expectedType) {
            throw new IllegalArgumentException(
                String.format("Flight type mismatch: expected %s for %s->%s but got %s",
                    expectedType, origin.continent(), destination.continent(), type)
            );
        }
        
        // Validar capacidad según tipo y modo
        if (ValidationMode.isStrict()) {
            // Modo STRICT: Especificaciones del diseño
            if (type == FlightType.INTRACONTINENTAL && (capacity < 150 || capacity > 250)) {
                throw new IllegalArgumentException(
                    "Intracontinental flight capacity must be between 150 and 250 (STRICT mode), got: " + capacity
                );
            }
            if (type == FlightType.INTERCONTINENTAL && (capacity < 150 || capacity > 400)) {
                throw new IllegalArgumentException(
                    "Intercontinental flight capacity must be between 150 and 400 (STRICT mode), got: " + capacity
                );
            }
        } else {
            // Modo LENIENT: Rango ampliado para datos reales
            if (capacity < 100 || capacity > 500) {
                throw new IllegalArgumentException(
                    "Flight capacity must be between 100 and 500 (LENIENT mode), got: " + capacity
                );
            }
        }
        
        // Validar duración según tipo y modo
        Duration duration = Duration.between(departureTime, arrivalTime);
        long hours = duration.toHours();
        
        if (ValidationMode.isStrict()) {
            // Modo STRICT: Duraciones exactas según especificaciones
            if (type == FlightType.INTRACONTINENTAL && hours != 12) {
                throw new IllegalArgumentException(
                    "Intracontinental flight must be exactly 12 hours (STRICT mode), got: " + hours + " hours"
                );
            }
            if (type == FlightType.INTERCONTINENTAL && hours != 24) {
                throw new IllegalArgumentException(
                    "Intercontinental flight must be exactly 24 hours (STRICT mode), got: " + hours + " hours"
                );
            }
        } else {
            // Modo LENIENT: Rango ampliado para duraciones reales
            if (duration.toMinutes() < 30) {
                throw new IllegalArgumentException(
                    "Flight duration must be at least 30 minutes (LENIENT mode), got: " + duration.toMinutes() + " minutes"
                );
            }
            if (hours > 48) {
                throw new IllegalArgumentException(
                    "Flight duration cannot exceed 48 hours (LENIENT mode), got: " + hours + " hours"
                );
            }
        }
    }
    
    /**
     * Calcula la capacidad disponible restante después de asignar maletas.
     * 
     * @param assignedBags Número de maletas ya asignadas al vuelo
     * @return Capacidad disponible restante
     */
    public int availableCapacity(int assignedBags) {
        return capacity - assignedBags;
    }
}
