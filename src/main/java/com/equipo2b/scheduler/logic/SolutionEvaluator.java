package com.equipo2b.scheduler.logic;

// We use cost as fitness. Objective: Minimize cost (which is a double)

import com.equipo2b.scheduler.model.*;
import com.equipo2b.scheduler.util.TimeConverter;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class SolutionEvaluator {

    private static final double INVALID_ROUTE_PENALTY = 1_000_000.0;

    public double evaluate(Solution solution, AirportManager airportManager) {
        double totalScore = 0;
        List<StorageEvent> storageTimeline = new ArrayList<>();

        for (ShipmentRoute route : solution.getRoutes().values()) {
            // Extremely high cost for infeasible solutions
            if (!RouteValidator.validateRoute(route)) {
                totalScore += INVALID_ROUTE_PENALTY;
                continue;
            }

            // Calculate the "Time to Destination" (Efficiency)
            totalScore += calculateRouteTime(route, airportManager);

            // Generate storage events to later check storage constraints
            generateStorageEvents(route, storageTimeline, airportManager);
        }

        return totalScore;
    }

    private void generateStorageEvents(ShipmentRoute route, List<StorageEvent> timeline, AirportManager am) {
        Shipment s = route.getShipment();
        List<ScheduledFlight> steps = route.getSteps();

        // Evento 1: Entra al aeropuerto de origen (Registro)
        timeline.add(new StorageEvent(s.getDepartureDateTime(), 1, s.getOriginId()));

        // Eventos intermedios
        for (int i = 0; i < steps.size(); i++) {
            ScheduledFlight flight = steps.get(i);

            // Sale del aeropuerto actual (Vuelo despega)
            timeline.add(new StorageEvent(flight.getDepartureDateTime(), -1, flight.getOrigin()));

            // Entra al aeropuerto de destino (Vuelo aterriza)
            // Nota: Si es el último destino, el paquete se entrega y "sale" del sistema de almacenamiento.
            if (i < steps.size() - 1) {
                timeline.add(new StorageEvent(flight.getArrivalDateTime(am), 1, flight.getDestination()));
            }
        }
    }

    private double calculateRouteTime(ShipmentRoute route, AirportManager am) {
        Shipment shipment = route.getShipment();
        LocalDateTime startTime = shipment.getDepartureDateTime(); // From the instant it is turned in
        ScheduledFlight firstFlight = route.getSteps().getFirst();

        // Find the arrival time of the last flight in the sequence
        ScheduledFlight lastFlight = route.getSteps().getLast();
        LocalDateTime arrivalTime = lastFlight.getArrivalDateTime();

        // Duration in minutes from shipment registration to final delivery
        long elapsedMinutes = TimeConverter.getElapsedMinutes(
                startTime, am.getAirport(firstFlight.getOrigin()),
                arrivalTime, am.getAirport(lastFlight.getOrigin())
        );

        return elapsedMinutes * 1.0;
    }
}
