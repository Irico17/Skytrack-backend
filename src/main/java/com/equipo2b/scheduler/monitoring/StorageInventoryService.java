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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

        Map<String, Integer> routedBagsByBase = applyRoutedStorageEvents(solution, currentTime, inventory);
        applyUnroutedOriginInventory(knownBatches, currentTime, routedBagsByBase, inventory);

        return inventory;
    }

    /**
     * Solo maletas SIN ruta aún sentadas en origen (faltante por base). Es el piso correcto
     * para construcción: no incluye routed (eso va en la timeline) ni picos ATP futuros.
     */
    public Map<Airport, Integer> calculateUnroutedOriginOccupancy(
            Solution solution,
            ZonedDateTime currentTime,
            List<ShipmentBatch> knownBatches) {
        Map<String, Integer> routedBagsByBase = new HashMap<>();
        if (solution != null && !solution.getRoutes().isEmpty()) {
            for (AssignedRoute route : solution.getRoutes().values()) {
                routedBagsByBase.merge(
                    baseBatchId(route.getBatch().batchId()),
                    route.getBatch().quantity(),
                    Integer::sum);
            }
        }
        Map<Airport, Integer> inventory = new HashMap<>();
        applyUnroutedOriginInventory(knownBatches, currentTime, routedBagsByBase, inventory);
        return inventory;
    }

    /**
     * Ocupación de planificación (ATP / scheduled receipts): inventario en suelo en
     * {@code now} más el pico proyectado al reaplicar eventos de rutas YA comprometidas
     * con timestamp después de {@code now}.
     *
     * <p>Sin esto, las maletas en vuelo hacia un hub (ARRIVAL futuro) son invisibles al
     * residual del ciclo: el planificador ve "hub vacío" y vuelve a concentrar hops ahí
     * hasta el desborde live (&gt;100%).</p>
     *
     * <p>{@code horizon} null = reservar <strong>todas</strong> las llegadas/salidas futuras
     * comprometidas (no solo Sc). Truncar a Sc dejaba llegar inbound intercontinental
     * "después de la ventana" sin reserva → picos 130%+ en hubs como UMMS.</p>
     */
    public Map<Airport, Integer> calculatePlanningOccupancy(
            Solution solution,
            ZonedDateTime now,
            ZonedDateTime horizon,
            List<ShipmentBatch> knownBatches) {
        Map<Airport, Integer> current = calculateCurrentBags(solution, now, knownBatches);
        if (now == null || solution == null || solution.getRoutes().isEmpty()) {
            return current;
        }

        Map<Airport, Integer> running = new HashMap<>(current);
        Map<Airport, Integer> peak = new LinkedHashMap<>(current);

        List<StorageEvent> events = getSortedEvents(solution);
        for (StorageEvent event : events) {
            if (!event.timestamp().isAfter(now)) {
                continue;
            }
            if (horizon != null && event.timestamp().isAfter(horizon)) {
                break;
            }
            int delta = event.type() == StorageEventType.ARRIVAL
                ? event.quantity()
                : -event.quantity();
            int next = Math.max(0, running.getOrDefault(event.airport(), 0) + delta);
            running.put(event.airport(), next);
            peak.merge(event.airport(), next, Math::max);
        }
        return peak;
    }

    private Map<String, Integer> applyRoutedStorageEvents(
            Solution solution,
            ZonedDateTime currentTime,
            Map<Airport, Integer> inventory) {
        Map<String, Integer> routedBagsByBase = new HashMap<>();
        if (solution == null || solution.getRoutes().isEmpty()) {
            return routedBagsByBase;
        }

        // Id BASE (sin sufijo "-S<n>") → maletas REALMENTE enrutadas bajo esa base. Un lote
        // dividido por applyCapacityAwareSplitting solo existe en la solución bajo sus
        // sub-lotes ("B16-S1" con 30, "B16-S2" con 20), nunca bajo su id original completo
        // ("B16" con 100) — antes solo se guardaba el id BASE como "ya-enrutado" (todo/nada, un
        // Set), así que un split PARCIAL (30+20=50 de 100) hacía que applyUnroutedOriginInventory
        // (más abajo) tratara las 50 maletas restantes como si NUNCA hubieran existido: no
        // aparecían en el origen (el id base ya estaba en el set) ni en ningún otro almacén (sus
        // sub-lotes no creados no generan StorageEvent) — se evaporaban del inventario. Ahora se
        // suma la cantidad real por base, y applyUnroutedOriginInventory agrega solo el
        // FALTANTE (batch.quantity() - enrutadas) en vez de saltar el lote entero.
        for (AssignedRoute route : solution.getRoutes().values()) {
            routedBagsByBase.merge(
                baseBatchId(route.getBatch().batchId()), route.getBatch().quantity(), Integer::sum);
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

        return routedBagsByBase;
    }

    /**
     * Suma en el almacén de origen el FALTANTE de cada lote conocido: {@code batch.quantity()}
     * menos lo que ya está enrutado (bajo su id base o cualquier sub-lote "-S&lt;n&gt;"), nunca
     * el lote completo cuando solo una parte quedó sin ruta. Las maletas YA enrutadas no se
     * cuentan aquí — ya aportan al inventario por sus propios {@link StorageEvent} (ver
     * {@link #applyRoutedStorageEvents}); sumar también su cantidad completa aquí las
     * contaría dos veces.
     */
    private void applyUnroutedOriginInventory(
            List<ShipmentBatch> knownBatches,
            ZonedDateTime currentTime,
            Map<String, Integer> routedBagsByBase,
            Map<Airport, Integer> inventory) {
        if (knownBatches == null || knownBatches.isEmpty()) {
            return;
        }

        for (ShipmentBatch batch : knownBatches) {
            if (batch == null || batch.ingressTime().isAfter(currentTime)) {
                continue;
            }
            int routed = routedBagsByBase.getOrDefault(batch.batchId(), 0);
            int missing = batch.quantity() - routed;
            if (missing <= 0) {
                continue;
            }
            Airport origin = batch.origin();
            int next = inventory.getOrDefault(origin, 0) + missing;
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
