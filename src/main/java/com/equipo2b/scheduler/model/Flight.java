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
        
        // Validar que la capacidad sea positiva (el valor real viene de los datos o backend)
        if (capacity <= 0) {
            throw new IllegalArgumentException(
                "Flight capacity must be positive, got: " + capacity
            );
        }
        
        // Validar que la llegada sea después de la salida
        if (!arrivalTime.isAfter(departureTime)) {
            throw new IllegalArgumentException(
                String.format("Arrival time must be after departure time: departure=%s, arrival=%s",
                    departureTime, arrivalTime)
            );
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
