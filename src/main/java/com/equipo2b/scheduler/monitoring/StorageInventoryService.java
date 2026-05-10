package com.equipo2b.scheduler.monitoring;

import com.equipo2b.scheduler.model.Airport;
import com.equipo2b.scheduler.model.AirportManager;
import com.equipo2b.scheduler.model.AssignedRoute;
import com.equipo2b.scheduler.model.Solution;
import com.equipo2b.scheduler.model.StorageEvent;
import com.equipo2b.scheduler.model.StorageEventType;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calcula inventario actual de maletas por aeropuerto para un instante simulado.
 */
public class StorageInventoryService {
    private final AirportManager airportManager;

    public StorageInventoryService(AirportManager airportManager) {
        if (airportManager == null) {
            throw new NullPointerException("AirportManager cannot be null");
        }
        this.airportManager = airportManager;
    }

    public Map<Airport, Integer> calculateCurrentBags(Solution solution, ZonedDateTime currentTime) {
        Map<Airport, Integer> inventory = new LinkedHashMap<>();
        for (Airport airport : airportManager.getAllAirports()) {
            inventory.put(airport, 0);
        }

        if (solution == null || currentTime == null || solution.getRoutes().isEmpty()) {
            return inventory;
        }

        List<StorageEvent> events = new ArrayList<>();
        for (AssignedRoute route : solution.getRoutes().values()) {
            events.addAll(route.getStorageEvents());
        }

        events.sort((a, b) -> {
            int byTime = a.timestamp().compareTo(b.timestamp());
            if (byTime != 0) return byTime;
            return Integer.compare(eventPriority(a.type()), eventPriority(b.type()));
        });

        for (StorageEvent event : events) {
            if (event.timestamp().isAfter(currentTime)) {
                break;
            }

            int delta = event.type() == StorageEventType.ARRIVAL
                ? event.quantity()
                : -event.quantity();
            int next = inventory.getOrDefault(event.airport(), 0) + delta;
            inventory.put(event.airport(), Math.max(0, next));
        }

        return inventory;
    }

    private static int eventPriority(StorageEventType type) {
        return type == StorageEventType.ARRIVAL ? 0 : 1;
    }
}
