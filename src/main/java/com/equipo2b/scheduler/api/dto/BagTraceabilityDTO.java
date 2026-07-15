package com.equipo2b.scheduler.api.dto;

import java.util.List;

/**
 * Read model paginado para trazabilidad individual de maletas.
 */
public record BagTraceabilityDTO(
    String simulationId,
    String simulatedTime,
    int page,
    int size,
    long totalItems,
    SummaryDTO summary,
    List<BagItemDTO> bags
) {
    public record SummaryDTO(
        long totalBags,
        long matchedBags,
        long notRegisteredBags,
        long pendingRouteBags,
        long warehouseBags,
        long inFlightBags,
        long deliveredBags,
        long delayedBags
    ) {}

    public record BagItemDTO(
        String bagId,
        int sequence,
        String batchId,
        String clientId,
        String originId,
        String destinationId,
        String state,
        String currentAirportId,
        String currentFlightId,
        String lastEvent,
        String nextEvent,
        String ingressTime,
        String deadline,
        String finalArrivalTime,
        boolean meetsSla,
        double progress,
        List<BagEventDTO> events
    ) {}

    public record BagEventDTO(
        String type,
        String airportId,
        String flightId,
        String timestamp,
        boolean completed
    ) {}
}