package com.equipo2b.scheduler.model;

import java.time.ZonedDateTime;
import java.util.Comparator;
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

    /**
     * Orden canónico para reproducir eventos de almacén: por timestamp, y entre eventos con
     * el MISMO timestamp exacto (frecuente cuando varias maletas comparten vuelo), ARRIVAL
     * antes que DEPARTURE — así una llegada nunca "adelanta" a una salida simultánea del
     * mismo instante, evitando picos de ocupación espurios que dependan del orden de
     * inserción en vez de una regla fija.
     *
     * <p>Antes cada clase que reproducía eventos (SolutionEvaluator, RouteValidator,
     * CapacityMonitor, StorageInventoryService) ordenaba solo por timestamp, sin desempate —
     * dos eventos simultáneos podían procesarse en cualquier orden según cómo hubiera
     * iterado el HashMap de rutas esa vez, dando resultados no reproducibles. Centralizado
     * aquí para que las cuatro coincidan siempre.</p>
     */
    public static final Comparator<StorageEvent> CHRONOLOGICAL_ORDER = Comparator
        .comparing(StorageEvent::timestamp)
        .thenComparing(event -> event.type() == StorageEventType.ARRIVAL ? 0 : 1);
}
