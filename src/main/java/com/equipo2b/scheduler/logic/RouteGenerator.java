package com.equipo2b.scheduler.logic;

import com.equipo2b.scheduler.model.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

// Generates initial random (but feasible) routes for later optimization
// Constructs a route using a simple proximity heuristic and random selection
public class RouteGenerator {
    private final Map<String, List<Flight>> flightPlanByOrigin; // To search Flights by origin (later converted to ScheduledFlight)
    private final AirportManager airportManager;
    private static final int MAX_HOPS = 4; // To avoid infinite routes

    public RouteGenerator(List<Flight> allFlights, AirportManager am) {
        this.airportManager = am;
        this.flightPlanByOrigin = allFlights.stream()
                .collect(Collectors.groupingBy(Flight::getOrigin));
    }

    /**
     * Genera una ruta aleatoria pero factible (espacial y temporalmente).
     * Si no logra generarla, devuelve null
     */
    public ShipmentRoute generateFeasibleRoute(Shipment shipment) {
        ShipmentRoute route = new ShipmentRoute(shipment);
        String currentLoc = shipment.getOriginId();
        LocalDateTime currentTime = shipment.getDepartureDateTime();

        for (int hop = 0; hop < MAX_HOPS; hop++) {
            // Get flights that leave from current airport
            List<Flight> potentialFlights = flightPlanByOrigin.getOrDefault(currentLoc, new ArrayList<>());

            // Get flights that depart after the luggage has been turned in
            List<ScheduledFlight> validOptions = new ArrayList<>();
            for (Flight f : potentialFlights) {
                // Try for current day
                ScheduledFlight sf = new ScheduledFlight(f, currentTime.toLocalDate());

                if (sf.getDepartureDateTime().isBefore(currentTime)) { // If not possible, try tomorrow
                    sf = new ScheduledFlight(f, currentTime.toLocalDate().plusDays(1));
                }
                validOptions.add(sf);
            }

            if (validOptions.isEmpty()) return null; // There are no available flights

            // Selection Heuristics: We prefer flights that get us closer to our destination
            ScheduledFlight chosen = pickBestOption(validOptions, shipment.getDestinationId());

            route.addStep(chosen);

            // Hop to next location
            currentLoc = chosen.getDestination();
            currentTime = chosen.getArrivalDateTime();

            if (currentLoc.equals(shipment.getDestinationId())) {
                return route;
            }
        }

        return null;
    }

    private ScheduledFlight pickBestOption(List<ScheduledFlight> options, String destinationId) {
        // For now, the heuristic has not been implemented
        return options.get(new Random().nextInt(options.size()));
    }

    /**
     * Crea una solución factible completa desde cero para todos los envíos mandados.
     */
    public Solution generateNewSolution(List<Shipment> shipments) {
        Solution solution = new Solution();
        int totalShipments = shipments.size();
        int successCount = 0;

        for (Shipment shipment : shipments) {
            ShipmentRoute route = null;
            int attempts = 0;
            int maxAttempts = 50;

            while (route == null && attempts < maxAttempts) {
                route = generateFeasibleRoute(shipment);
                attempts++;
            }

            if (route != null) {
                solution.addRoute(route);
                successCount++;
            } else {
                // (For testing)
                System.err.println("Critical: No se pudo generar ruta para el envio " + shipment.getId()
                        + " tras " + maxAttempts + " intentos.");
            }
        }

        // (For testing)
        System.out.println("Solución generada: " + successCount + "/" + totalShipments + " envíos ruteados.");

        return solution;
    }
}
