package com.equipo2b.scheduler.model;

import java.time.ZoneId;
import java.util.Objects;

/**
 * Representa un aeropuerto con su ubicación geográfica, capacidad de almacén y huso horario.
 * Inmutable.
 * 
 * @param id Código IATA del aeropuerto (ej: "JFK", "CDG")
 * @param city Ciudad donde se ubica el aeropuerto
 * @param country País donde se ubica el aeropuerto
 * @param zoneId Huso horario para conversiones temporales
 * @param storageCapacity Capacidad máxima del almacén (500-800 maletas)
 * @param latitude Latitud para cálculos de distancia
 * @param longitude Longitud para cálculos de distancia
 * @param continent Continente (AMERICA, EUROPE, ASIA)
 */
public record Airport(
    String id,
    String city,
    String country,
    ZoneId zoneId,
    int storageCapacity,
    double latitude,
    double longitude,
    Continent continent
) {
    /**
     * Constructor compacto con validaciones.
     */
    public Airport {
        Objects.requireNonNull(id, "Airport ID cannot be null");
        Objects.requireNonNull(zoneId, "ZoneId cannot be null");
        Objects.requireNonNull(continent, "Continent cannot be null");
        
        // Validar que la capacidad sea positiva (el valor real viene de los datos o backend)
        if (storageCapacity <= 0) {
            throw new IllegalArgumentException(
                "Storage capacity must be positive, got: " + storageCapacity);
        }
    }
}
