package com.equipo2b.scheduler.api.dto;

import com.equipo2b.scheduler.execution.ReplanResult;
import com.equipo2b.scheduler.execution.SimulationStatus;
import com.equipo2b.scheduler.model.AssignedRoute;
import com.equipo2b.scheduler.model.Flight;
import com.equipo2b.scheduler.model.ShipmentBatch;
import com.equipo2b.scheduler.model.Solution;
import com.equipo2b.scheduler.monitoring.TrafficLightReport;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Utilidad para convertir objetos de dominio a DTOs.
 */
public final class DTOMapper {

    private DTOMapper() {}

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    // ===== SOLUTION =====

    public static SolutionDTO toSolutionDTO(Solution solution) {
        if (solution == null) {
            return new SolutionDTO(0, 0, 0.0, 0, 0.0, List.of());
        }

        long slaOk = solution.getRoutes().values().stream()
            .filter(AssignedRoute::meetsSLA)
            .count();
        int total = solution.getRoutes().size();
        double slaPercent = total > 0 ? (slaOk * 100.0 / total) : 100.0;

        List<RouteDTO> routes = solution.getRoutes().values().stream()
            .map(DTOMapper::toRouteDTO)
            .collect(Collectors.toList());

        return new SolutionDTO(
            total,
            solution.getTotalBags(),
            solution.getFitness(),
            (int) slaOk,
            slaPercent,
            routes
        );
    }

    public static RouteDTO toRouteDTO(AssignedRoute route) {
        ShipmentBatch batch = route.getBatch();
        List<FlightSegmentDTO> segments = route.getFlights().stream()
            .map(DTOMapper::toFlightSegmentDTO)
            .collect(Collectors.toList());

        return new RouteDTO(
            batch.batchId(),
            batch.clientId(),
            batch.origin().id(),
            batch.destination().id(),
            batch.quantity(),
            route.meetsSLA(),
            route.getSLASlack().toString(),
            route.getFinalArrivalTime().format(ISO),
            segments
        );
    }

    public static FlightSegmentDTO toFlightSegmentDTO(Flight flight) {
        return new FlightSegmentDTO(
            flight.flightId(),
            flight.origin().id(),
            flight.destination().id(),
            flight.departureTime().format(ISO),
            flight.arrivalTime().format(ISO),
            flight.capacity()
        );
    }

    // ===== STATUS =====

    public static SimulationStatusDTO toStatusDTO(
            String simId,
            SimulationStatus status,
            int batchesPending,
            String scenarioName,
            TrafficLightReport trafficReport) {

        SemaphoreDTO semaphore = trafficReport != null
            ? new SemaphoreDTO(
                trafficReport.flightColor().name(),
                trafficReport.storageColor().name(),
                trafficReport.slaColor().name(),
                trafficReport.flightOccupancy(),
                trafficReport.storageOccupancy(),
                trafficReport.slaCompliance())
            : SemaphoreDTO.unknown();

        String simTime = status.simulatedTime() != null
            ? status.simulatedTime().format(ISO)
            : null;

        return new SimulationStatusDTO(
            simId,
            statusLabel(status),
            scenarioName,
            status.currentCycle(),
            simTime,
            status.batchesProcessed(),
            status.batchesFailed(),
            batchesPending,
            status.currentFitness(),
            status.collapseLevel().name(),
            status.collapseLevel().name().equals("COLLAPSED"),
            semaphore
        );
    }

    private static String statusLabel(SimulationStatus s) {
        if (!s.isRunning()) return "STOPPED";
        if (s.isPaused()) return "PAUSED";
        return "RUNNING";
    }

    // ===== REPLAN =====

    public static ReplanResultDTO toReplanResultDTO(String flightId, ReplanResult result) {
        List<String> unreplannableIds = result.unreplannableBatches().stream()
            .map(ShipmentBatch::batchId)
            .collect(Collectors.toList());

        return new ReplanResultDTO(
            flightId,
            result.unreplannableBatches().size() + result.replanedBatches().size(),
            result.replanedBatches().size(),
            result.unreplannableBatches().size(),
            result.updatedSolution().getFitness(),
            unreplannableIds
        );
    }
}
