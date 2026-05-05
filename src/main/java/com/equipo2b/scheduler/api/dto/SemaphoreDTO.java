package com.equipo2b.scheduler.api.dto;

/**
 * Estado del semáforo de capacidades: vuelos, almacenes y SLA.
 */
public record SemaphoreDTO(
    String flights,          // GREEN, AMBER, RED
    String storage,
    String sla,
    double flightOccupancy,
    double storageOccupancy,
    double slaCompliance
) {
    public static SemaphoreDTO unknown() {
        return new SemaphoreDTO("UNKNOWN", "UNKNOWN", "UNKNOWN", 0.0, 0.0, 0.0);
    }
}
