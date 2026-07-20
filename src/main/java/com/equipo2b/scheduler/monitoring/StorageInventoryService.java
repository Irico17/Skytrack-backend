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

        // Id BASE (sin sufijo "-S<n>"): un lote dividido por applyCapacityAwareSplitting solo
        // existe en la solución bajo sus sub-lotes ("B16-S1", "B16-S2"), nunca bajo su id
        // original ("B16"). Guardar el id exacto de cada ruta hacía que applyUnroutedOriginInventory
        // (más abajo) NUNCA reconociera a "B16" como ya-enrutado — y le sumaba su cantidad
        // ORIGINAL completa de nuevo en el origen, ENCIMA de lo que sus sub-lotes ya aportaban
        // por sus propios eventos de almacén. Con splitting poco frecuente pasaba casi
        // desapercibido; al volverse la capacidad de almacén una restricción dura (más lotes
        // necesitan dividirse para caber), este doble conteo se volvió sistémico y creciente en
        // toda la red — exactamente el patrón de sobrecarga generalizada reportado en producción.
        for (AssignedRoute route : solution.getRoutes().values()) {
            routedBatchIds.add(baseBatchId(route.getBatch().batchId()));
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

    /** Quita los sufijos "-S&lt;n&gt;" finales de un id de lote (mismo criterio que Scheduler). */
    private static String baseBatchId(String id) {
        String s = id;
        while (true) {
            int idx = s.lastIndexOf("-S");
            if (idx < 0 || idx + 2 >= s.length()) break;
            String suffix = s.substring(idx + 2);
            if (!suffix.chars().allMatch(Character::isDigit)) break;
            s = s.substring(0, idx);
        }
        return s;
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

            events.sort(StorageEvent.CHRONOLOGICAL_ORDER);

            cachedSolution = solution;
            cachedRouteCount = routeCount;
            cachedSortedEvents = List.copyOf(events);
            return cachedSortedEvents;
        }
    }
}
