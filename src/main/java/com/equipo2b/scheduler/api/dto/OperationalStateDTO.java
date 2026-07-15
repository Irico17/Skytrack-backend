package com.equipo2b.scheduler.api.dto;

import java.util.List;

/**
 * Read model compacto para paneles operativos.
 */
public record OperationalStateDTO(
    String simulationId,
    String simulatedTime,
    List<TransportUnitItemDTO> transportUnits,
    List<WarehouseItemDTO> warehouses,
    List<ShipmentOperationalItemDTO> shipments
) {
    public record TransportUnitItemDTO(
        String flightId,
        String originId,
        String destinationId,
        String departureTime,
        String arrivalTime,
        int capacity,
        int bagsCount,
        double occupancyRatio,
        boolean empty,
        boolean meetsSla
    ) {}

    public record WarehouseItemDTO(
        String airportId,
        String city,
        String country,
        int currentBags,
        int maxCapacity,
        double occupancyRatio,
        String semaphore
    ) {}

    public record ShipmentOperationalItemDTO(
        String batchId,
        String clientId,
        String originId,
        String destinationId,
        int quantity,
        String state,
        String currentFlightId,
        boolean meetsSla,
        double progress,
        String virtualProductStart,
        String virtualProductEnd
    ) {}
}