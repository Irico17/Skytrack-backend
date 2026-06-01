package com.equipo2b.scheduler.monitoring;

import com.equipo2b.scheduler.model.Airport;
import com.equipo2b.scheduler.model.AirportManager;
import com.equipo2b.scheduler.model.AssignedRoute;
import com.equipo2b.scheduler.model.ShipmentBatch;
import com.equipo2b.scheduler.model.Solution;
import com.equipo2b.scheduler.model.StorageEvent;
import com.equipo2b.scheduler.model.StorageEventType;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Calcula inventario actual de maletas por aeropuerto para un instante simulado.
 */
public class StorageInventoryService {
    private final AirportManager airportManager;
    private volatile Solution cachedSolution;
    private volatile int cachedRouteCount = -1;
    private volatile List<StorageEvent> cachedSortedEvents = List.of();

    public StorageInventoryService(AirportManager airportManager) {
        if (airportManager == null) {
            throw new NullPointerException("AirportManager cannot be null");
        }
        this.airportManager = airportManager;
    }

    public Map<Airport, Integer> calculateCurrentBags(Solution solution, ZonedDateTime currentTime) {
        return calculateCurrentBags(solution, currentTime, List.of());
    }

    public Map<Airport, Integer> calculateCurrentBags(
            Solution solution,
            ZonedDateTime currentTime,
            List<ShipmentBatch> knownBatches) {
        Map<Airport, Integer> inventory = new LinkedHashMap<>();
        for (Airport airport : airportManager.getAllAirports()) {
            inventory.put(airport, 0);
        }

        if (currentTime == null) {
            return inventory;
        }

        Set<String> routedBatchIds = applyRoutedStorageEvents(solution, currentTime, inventory);
        applyUnroutedOriginInventory(knownBatches, currentTime, routedBatchIds, inventory);

        return inventory;
    }

    private Set<String> applyRoutedStorageEvents(
            Solution solution,
            ZonedDateTime currentTime,
            Map<Airport, Integer> inventory) {
        Set<String> routedBatchIds = new HashSet<>();
        if (solution == null || solution.getRoutes().isEmpty()) {
            return routedBatchIds;
        }

        for (AssignedRoute route : solution.getRoutes().values()) {
            routedBatchIds.add(route.getBatch().batchId());
        }

        List<StorageEvent> events = getSortedEvents(solution);
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

        return routedBatchIds;
    }

    private void applyUnroutedOriginInventory(
            List<ShipmentBatch> knownBatches,
            ZonedDateTime currentTime,
            Set<String> routedBatchIds,
            Map<Airport, Integer> inventory) {
        if (knownBatches == null || knownBatches.isEmpty()) {
            return;
        }

        for (ShipmentBatch batch : knownBatches) {
            if (batch == null || routedBatchIds.contains(batch.batchId()) || batch.ingressTime().isAfter(currentTime)) {
                continue;
            }
            Airport origin = batch.origin();
            int next = inventory.getOrDefault(origin, 0) + batch.quantity();
            inventory.put(origin, Math.max(0, next));
        }
    }

    private List<StorageEvent> getSortedEvents(Solution solution) {
        int routeCount = solution.getRoutes().size();
        if (solution == cachedSolution && routeCount == cachedRouteCount) {
            return cachedSortedEvents;
        }

        synchronized (this) {
            if (solution == cachedSolution && routeCount == cachedRouteCount) {
                return cachedSortedEvents;
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

            cachedSolution = solution;
            cachedRouteCount = routeCount;
            cachedSortedEvents = List.copyOf(events);
            return cachedSortedEvents;
        }
    }

    private static int eventPriority(StorageEventType type) {
        return type == StorageEventType.ARRIVAL ? 0 : 1;
    }
}
