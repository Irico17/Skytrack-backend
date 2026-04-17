package com.equipo2b.scheduler.model;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Representa un lote de maletas con mismo origen, destino y cliente.
 * Inmutable. Principio: "Equipaje con Dueño".
 * 
 * @param batchId ID único global del lote
 * @param airportBatchId ID del lote en el aeropuerto origen
 * @param clientId ID del cliente propietario
 * @param origin Aeropuerto origen (inmutable)
 * @param destination Aeropuerto destino final (inmutable)
 * @param quantity Cantidad de maletas en el lote
 * @param ingressTime Timestamp de ingreso al sistema
 */
public record ShipmentBatch(
    String batchId,
    String airportBatchId,
    String clientId,
    Airport origin,
    Airport destination,
    int quantity,
    ZonedDateTime ingressTime
) {
    /**
     * Constructor compacto con validaciones.
     */
    public ShipmentBatch {
        Objects.requireNonNull(batchId, "Batch ID cannot be null");
        Objects.requireNonNull(clientId, "Client ID cannot be null");
        Objects.requireNonNull(origin, "Origin cannot be null");
        Objects.requireNonNull(destination, "Destination cannot be null");
        Objects.requireNonNull(ingressTime, "Ingress time cannot be null");
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
    }
    
    /**
     * Calcula el SLA (plazo máximo) según continentes de origen y destino.
     * Mismo continente: 24 horas
     * Diferentes continentes: 48 horas
     * 
     * @return Duration representando el SLA
     */
    public Duration calculateSLA() {
        boolean sameContinents = origin.continent() == destination.continent();
        return sameContinents ? Duration.ofHours(24) : Duration.ofHours(48);
    }
    
    /**
     * Verifica si el lote cumple SLA dado un tiempo de llegada.
     * 
     * @param arrivalTime Tiempo de llegada al destino final
     * @return true si cumple el SLA, false en caso contrario
     */
    public boolean meetsSLA(ZonedDateTime arrivalTime) {
        Duration elapsed = Duration.between(ingressTime, arrivalTime);
        return elapsed.compareTo(calculateSLA()) <= 0;
    }
}
