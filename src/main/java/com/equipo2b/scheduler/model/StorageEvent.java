package com.equipo2b.scheduler.model;

import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Representa un evento de almacenamiento (llegada o salida) en un aeropuerto.
 * 
 * Este record se utiliza para rastrear la capacidad de almacenamiento a lo largo del tiempo,
 * registrando cuándo las maletas llegan o salen de los almacenes de los aeropuertos.
 * 
 * @param airport El aeropuerto donde ocurre el evento
 * @param timestamp El momento exacto del evento en ZonedDateTime
 * @param quantity La cantidad de maletas involucradas en el evento
 * @param type El tipo de evento (ARRIVAL o DEPARTURE)
 */
public record StorageEvent(
    Airport airport,
    ZonedDateTime timestamp,
    int quantity,
    StorageEventType type
) {
    /**
     * Constructor compacto que valida los parámetros del record.
     */
    public StorageEvent {
        Objects.requireNonNull(airport, "Airport cannot be null");
        Objects.requireNonNull(timestamp, "Timestamp cannot be null");
        Objects.requireNonNull(type, "StorageEventType cannot be null");
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
    }
}
