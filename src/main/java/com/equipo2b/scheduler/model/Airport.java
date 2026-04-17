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
        
        // Validación condicional según modo
        if (ValidationMode.isStrict()) {
            // Modo STRICT: Especificaciones del diseño
            if (storageCapacity < 500 || storageCapacity > 800) {
                throw new IllegalArgumentException(
                    "Storage capacity must be between 500 and 800 (STRICT mode), got: " + storageCapacity);
            }
        } else {
            // Modo LENIENT: Rango ampliado para datos reales
            if (storageCapacity < 300 || storageCapacity > 1000) {
                throw new IllegalArgumentException(
                    "Storage capacity must be between 300 and 1000 (LENIENT mode), got: " + storageCapacity);
            }
        }
    }
}
