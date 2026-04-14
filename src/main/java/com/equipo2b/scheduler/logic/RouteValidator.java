package com.equipo2b.scheduler.logic;

import com.equipo2b.scheduler.model.Flight;
import com.equipo2b.scheduler.model.ScheduledFlight;
import com.equipo2b.scheduler.model.ShipmentRoute;

import java.time.LocalTime;
import java.util.List;

/**
 * Valida si una ruta (secuencia de vuelos) es físicamente posible y válida para enviar de un lugar a otro
 * un envío. (A -> B -> C).
 */

public class RouteValidator {

    public static boolean validateRoute(ShipmentRoute route){
        List<ScheduledFlight> steps = route.getSteps();

        // Empty routes are not valid routes
        if (steps.isEmpty()) return false;

        // The beginning of the route must be at the airport from which the shipment was first sent
        if (!steps.get(0).getOrigin().equals( route.getShipment().getOriginId()) ) return false;

        // The last flight must be to the shipment destination
        ScheduledFlight lastFlight = steps.get(steps.size() - 1);
        if ( !lastFlight.getDestination().equals( route.getShipment().getDestinationId() )) return false;

        // Check for continuity of each step (flight)
        for (int i = 0; i < steps.size() - 1; i++) {
            ScheduledFlight current = steps.get(i);
            ScheduledFlight next = steps.get(i + 1);

            if (!isContinuousSpace(current, next)) return false; // Airport A -> Airport B -> Airport C
            if (!isContinuousTime(current, next)) return false; // 15:00 -> 15:10 -> 14:00
        }

        return true;
    }

    private static boolean isContinuousSpace(ScheduledFlight a, ScheduledFlight b) {
        return a.getDestination().equals( b.getOrigin() );
    }

    private static boolean isContinuousTime(ScheduledFlight a, ScheduledFlight b){
        return b.getDepartureDateTime().isAfter(a.getDepartureDateTime());
    }
}
