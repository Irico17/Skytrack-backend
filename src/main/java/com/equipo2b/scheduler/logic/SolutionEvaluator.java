package com.equipo2b.scheduler.logic;

// We use cost as fitness. Objective: Minimize cost (which is a double)

import com.equipo2b.scheduler.model.*;
import com.equipo2b.scheduler.util.TimeConverter;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

public class SolutionEvaluator {

    private static final double INVALID_ROUTE_PENALTY = 1_000_000.0;

    public double evaluate(Solution solution, AirportManager airportManager) {
        double totalScore = 0;
        List<StorageEvent> storageTimeline = new ArrayList<>();
        Map<ScheduledFlight, Integer> flightOccupancy = new HashMap<>(); // Flight -> Quantity

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

            // Collect flight occupancy
            for (ScheduledFlight f : route.getSteps()) {
                int currentQty = flightOccupancy.getOrDefault(f, 0);
                flightOccupancy.put(f, currentQty + route.getShipment().getQuantity());
            }
        }

        // Verify airport storage capacity constraints
        totalScore += calculateCapacityPenalties(storageTimeline, airportManager);
        totalScore += calculateFlightPenalties(flightOccupancy);

        return totalScore;
    }

    private double calculateFlightPenalties(Map<ScheduledFlight, Integer> occupancyMap) {
        double penalty = 0;

        for (var entry : occupancyMap.entrySet()) {
            ScheduledFlight flight = entry.getKey();
            int totalQuantity = entry.getValue();
            int maxCapacity = flight.getBaseFlight().getCapacity();

            if (totalQuantity > maxCapacity) {
                int excess = totalQuantity - maxCapacity;

                // Penalty: A high base cost + the square of the excess
                // This creates a "steep hill" for the algorithm to climb down
                penalty += 20000 + (Math.pow(excess, 2) * 100);
            }
        }
        return penalty;
    }

    private double calculateCapacityPenalties(List<StorageEvent> timeline, AirportManager am) {
        Collections.sort(timeline); // Order by time

        Map<String, Integer> currentOccupancy = new HashMap<>(); // Assumes initial empty storage
        double penalty = 0;

        for (StorageEvent event : timeline) {
            // Update storage with new event
            int newCount = currentOccupancy.getOrDefault(event.airportId(), 0) + event.delta();
            currentOccupancy.put(event.airportId(), newCount);

            // if for testing
            if (am.getAirport(event.airportId()) == null){
                System.out.println(event);
            }
            // Compare with airport max capacity
            int maxCap = am.getAirport(event.airportId()).getCapacity();
            if (newCount > maxCap) {
                penalty += (newCount - maxCap) * 500.0; // Penalty proportional to excess
            }
        }
        return penalty;
    }

    private void generateStorageEvents(ShipmentRoute route, List<StorageEvent> timeline, AirportManager am) {
        Shipment s = route.getShipment();
        List<ScheduledFlight> steps = route.getSteps();

        // Register luggage in origin airport
        timeline.add(new StorageEvent(s.getDepartureDateTime(), s.getQuantity(), s.getOriginId()));

        // Other movements
        for (int i = 0; i < steps.size(); i++) {
            ScheduledFlight flight = steps.get(i);

            // Leaves current airport
            timeline.add(new StorageEvent(flight.getDepartureDateTime(), s.getQuantity(), flight.getOrigin()));

            // Enters arrival airport. If it is the final airport, it is shipped and leaves the system
            if (i < steps.size() - 1) {
                timeline.add(new StorageEvent(flight.getArrivalDateTime(), s.getQuantity(), flight.getDestination()));
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
