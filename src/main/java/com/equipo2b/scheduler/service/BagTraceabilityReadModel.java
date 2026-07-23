package com.equipo2b.scheduler.service;

import com.equipo2b.scheduler.api.dto.BagTraceabilityDTO;
import com.equipo2b.scheduler.model.AssignedRoute;
import com.equipo2b.scheduler.model.Flight;
import com.equipo2b.scheduler.model.ShipmentBatch;
import com.equipo2b.scheduler.model.Solution;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Construye trazabilidad individual de maletas sin cambiar la granularidad del optimizador.
 */
final class BagTraceabilityReadModel {
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private BagTraceabilityReadModel() {}

    record Query(
        int page,
        int size,
        String text,
        String state,
        String clientId,
        String batchId
    ) {}

    static BagTraceabilityDTO build(
            String simulationId,
            ZonedDateTime simulatedTime,
            Solution solution,
            List<ShipmentBatch> knownBatches,
            Query query) {

        Query safeQuery = normalize(query);
        Map<String, AssignedRoute> routeByBatch = solution != null
            ? solution.getRoutes()
            : Map.of();
        Map<String, ShipmentBatch> batchesById = orderedBatches(knownBatches, routeByBatch);

        SummaryAccumulator summary = new SummaryAccumulator();
        List<BagTraceabilityDTO.BagItemDTO> pageItems = new ArrayList<>();
        long matched = 0;
        long start = (long) safeQuery.page() * safeQuery.size();
        long end = start + safeQuery.size();

        for (ShipmentBatch batch : batchesById.values()) {
            AssignedRoute route = routeByBatch.get(batch.batchId());
            BagSnapshot snapshot = snapshot(batch, route, simulatedTime);
            summary.add(snapshot.state(), batch.quantity(), snapshot.meetsSla());

            if (!matchesBatchFilters(batch, safeQuery)) {
                continue;
            }

            for (int sequence = 1; sequence <= batch.quantity(); sequence++) {
                String bagId = formatBagId(batch.batchId(), sequence);
                if (!matchesBagFilters(bagId, batch, snapshot, safeQuery)) {
                    continue;
                }

                if (matched >= start && matched < end) {
                    pageItems.add(toItem(bagId, sequence, batch, route, snapshot, simulatedTime));
                }
                matched++;
            }
        }

        return new BagTraceabilityDTO(
            simulationId,
            format(simulatedTime),
            safeQuery.page(),
            safeQuery.size(),
            matched,
            summary.toDto(matched),
            pageItems
        );
    }

    private static Query normalize(Query query) {
        int page = query != null ? Math.max(0, query.page()) : 0;
        int size = query != null ? Math.max(1, Math.min(query.size(), 200)) : 50;
        return new Query(
            page,
            size,
            normalizeText(query != null ? query.text() : null),
            normalizeText(query != null ? query.state() : null),
            normalizeText(query != null ? query.clientId() : null),
            normalizeText(query != null ? query.batchId() : null)
        );
    }

    private static Map<String, ShipmentBatch> orderedBatches(
            List<ShipmentBatch> knownBatches,
            Map<String, AssignedRoute> routeByBatch) {
        Map<String, ShipmentBatch> batches = new LinkedHashMap<>();
        // IMPORTANTE: las rutas de la solución van PRIMERO. Cuando applyCapacityAwareSplitting
        // divide un lote, la clave original conserva una ruta con cantidad REDUCIDA (el resto
        // se reubica en sub-lotes -S1/-S2 con claves nuevas). Si knownBatches (la lista
        // PRÍSTINA, sin reducir) se procesara primero, putIfAbsent se quedaría con la cantidad
        // original completa para esa clave y el sub-lote se sumaría aparte — contando las
        // maletas divididas DOS VECES y mostrándolas en el vuelo equivocado (el de antes de
        // dividir). Procesando las rutas primero, cada clave refleja su cantidad y vuelo
        // reales; knownBatches solo aporta los lotes que NO tienen ninguna ruta (pendientes).
        for (AssignedRoute route : routeByBatch.values()) {
            ShipmentBatch batch = route.getBatch();
            batches.putIfAbsent(batch.batchId(), batch);
        }
        if (knownBatches != null) {
            for (ShipmentBatch batch : knownBatches) {
                if (batch == null || batches.containsKey(batch.batchId())) {
                    continue;
                }
                // El lote registrado NUNCA se muta cuando la semilla del GA lo divide (admisión
                // de origen / findMaxRoutableQuantity): esas porciones nacen con ids ANIDADOS
                // nuevos ("UI-xxx-S1-S1", "UI-xxx-S1-S2-S1", ...) vía RouteGenerator/
                // GeneticAlgorithm.splitPortion, nunca reemplazando la clave original. Sin este
                // descuento, un lote de 500 partido en 340+90 seguía apareciendo aquí con sus
                // 500 originales COMPLETAS — 930 "maletas" visibles para 500 reales, y el envío
                // se veía "sin ruta" en el inspector aunque el 86% ya viajaba. Mismo criterio de
                // subárbol tolerante a huecos que Scheduler.routedQuantity (huérfano aquí de esa
                // clase: se reimplementa localmente porque este read-model no tiene acceso a
                // Solution ni a los métodos privados del Scheduler).
                int alreadyRouted = subtreeRoutedQuantity(routeByBatch, batch.batchId(), 0);
                int remaining = batch.quantity() - alreadyRouted;
                if (remaining <= 0) {
                    continue;  // todo el lote ya vive bajo sub-lotes con ruta: no hay nada pendiente
                }
                ShipmentBatch effective = remaining == batch.quantity()
                    ? batch
                    : new ShipmentBatch(batch.batchId(), batch.airportBatchId(), batch.clientId(),
                        batch.origin(), batch.destination(), remaining, batch.ingressTime());
                batches.put(batch.batchId(), effective);
            }
        }
        return batches;
    }

    /** Sufijos "-S&lt;n&gt;" tolerados vacíos antes de cortar el escaneo (mismo criterio que
     * {@code Scheduler.SUBTREE_SCAN_GAP_TOLERANCE}: un peel descartado deja huecos). */
    private static final int SUBTREE_SCAN_GAP_TOLERANCE = 3;
    private static final int SUBTREE_SCAN_MAX_DEPTH = 5;

    /** Maletas ya routeadas bajo el subárbol de {@code id} (id propio + descendientes -S&lt;n&gt;
     * anidados), replicando {@code Scheduler.subtreeRoutedQuantity} contra {@code routeByBatch}. */
    private static int subtreeRoutedQuantity(Map<String, AssignedRoute> routeByBatch, String id, int depth) {
        int total = 0;
        AssignedRoute own = routeByBatch.get(id);
        if (own != null) {
            total += own.getBatch().quantity();
        }
        if (depth >= SUBTREE_SCAN_MAX_DEPTH) {
            return total;
        }
        int emptyStreak = 0;
        for (int i = 1; emptyStreak < SUBTREE_SCAN_GAP_TOLERANCE; i++) {
            String childId = id + "-S" + i;
            if (!routeByBatch.containsKey(childId) && !routeByBatch.containsKey(childId + "-S1")) {
                emptyStreak++;
                continue;
            }
            emptyStreak = 0;
            total += subtreeRoutedQuantity(routeByBatch, childId, depth + 1);
        }
        return total;
    }

    private static BagTraceabilityDTO.BagItemDTO toItem(
            String bagId,
            int sequence,
            ShipmentBatch batch,
            AssignedRoute route,
            BagSnapshot snapshot,
            ZonedDateTime simulatedTime) {
        List<BagTraceabilityDTO.BagEventDTO> events = buildEvents(batch, route, simulatedTime);
        String lastEvent = null;
        String nextEvent = null;
        for (BagTraceabilityDTO.BagEventDTO event : events) {
            if (event.completed()) {
                lastEvent = event.type();
            } else if (nextEvent == null) {
                nextEvent = event.type();
            }
        }

        return new BagTraceabilityDTO.BagItemDTO(
            bagId,
            sequence,
            batch.batchId(),
            batch.clientId(),
            batch.origin().id(),
            batch.destination().id(),
            snapshot.state(),
            snapshot.currentAirportId(),
            snapshot.currentFlightId(),
            lastEvent,
            nextEvent,
            format(batch.ingressTime()),
            format(batch.ingressTime().plus(batch.calculateSLA())),
            route != null ? format(route.getFinalArrivalTime()) : null,
            route != null ? format(route.getDeliveredTime()) : null,
            snapshot.meetsSla(),
            snapshot.progress(),
            events
        );
    }

    private static BagSnapshot snapshot(ShipmentBatch batch, AssignedRoute route, ZonedDateTime simulatedTime) {
        if (simulatedTime == null || simulatedTime.isBefore(batch.ingressTime())) {
            return new BagSnapshot("NOT_REGISTERED", batch.origin().id(), null, false, 0.0);
        }

        if (route == null || route.getFlights().isEmpty()) {
            return new BagSnapshot("PENDING_ROUTE", batch.origin().id(), null, false, 0.0);
        }

        if (!simulatedTime.isBefore(route.getDeliveredTime())) {
            return new BagSnapshot("DELIVERED", batch.destination().id(), null, route.meetsSLA(), 1.0);
        }

        List<Flight> flights = route.getFlights();
        for (int i = 0; i < flights.size(); i++) {
            Flight flight = flights.get(i);
            if (!simulatedTime.isBefore(flight.departureTime()) && simulatedTime.isBefore(flight.arrivalTime())) {
                return new BagSnapshot(
                    "IN_FLIGHT",
                    null,
                    flight.flightId(),
                    route.meetsSLA(),
                    progress(batch.ingressTime(), route.getFinalArrivalTime(), simulatedTime)
                );
            }
            if (simulatedTime.isBefore(flight.departureTime())) {
                String state = i == 0 ? "AT_ORIGIN" : "AT_TRANSFER";
                String airportId = i == 0 ? batch.origin().id() : flights.get(i - 1).destination().id();
                return new BagSnapshot(
                    state,
                    airportId,
                    null,
                    route.meetsSLA(),
                    progress(batch.ingressTime(), route.getFinalArrivalTime(), simulatedTime)
                );
            }
        }

        return new BagSnapshot(
            "AT_TRANSFER",
            flights.get(flights.size() - 1).destination().id(),
            null,
            route.meetsSLA(),
            progress(batch.ingressTime(), route.getFinalArrivalTime(), simulatedTime)
        );
    }

    private static List<BagTraceabilityDTO.BagEventDTO> buildEvents(
            ShipmentBatch batch,
            AssignedRoute route,
            ZonedDateTime simulatedTime) {
        List<BagTraceabilityDTO.BagEventDTO> events = new ArrayList<>();
        addEvent(events, "REGISTERED", batch.origin().id(), null, batch.ingressTime(), simulatedTime);
        addEvent(events, "WAREHOUSE_IN", batch.origin().id(), null, batch.ingressTime(), simulatedTime);

        if (route == null) {
            return events;
        }

        for (Flight flight : route.getFlights()) {
            addEvent(events, "LOADED", flight.origin().id(), flight.flightId(), flight.departureTime(), simulatedTime);
            addEvent(events, "ARRIVED", flight.destination().id(), flight.flightId(), flight.arrivalTime(), simulatedTime);
        }
        addEvent(events, "DELIVERED", batch.destination().id(), null, route.getDeliveredTime(), simulatedTime);
        return events;
    }

    private static void addEvent(
            List<BagTraceabilityDTO.BagEventDTO> events,
            String type,
            String airportId,
            String flightId,
            ZonedDateTime timestamp,
            ZonedDateTime simulatedTime) {
        events.add(new BagTraceabilityDTO.BagEventDTO(
            type,
            airportId,
            flightId,
            format(timestamp),
            simulatedTime != null && !timestamp.isAfter(simulatedTime)
        ));
    }

    private static boolean matchesBatchFilters(ShipmentBatch batch, Query query) {
        if (!query.clientId().isBlank() && !batch.clientId().toLowerCase(Locale.ROOT).contains(query.clientId())) {
            return false;
        }
        if (query.batchId().isBlank()) {
            return true;
        }
        // Filtro por FAMILIA exacta, no contains: pedir "B16" debe traer B16 y todos sus
        // sub-lotes ("B16-S1", "B16-S2-S1", ...) — así el inspector de un envío dividido ve
        // las maletas de TODAS sus rutas — pero nunca a "B168" (el contains anterior sí lo
        // traía) ni a hermanos ajenos cuando se pide un sub-lote concreto ("B16-S1" trae su
        // propio subárbol, no a "B16-S2").
        String id = batch.batchId().toLowerCase(Locale.ROOT);
        return id.equals(query.batchId()) || id.startsWith(query.batchId() + "-s");
    }

    private static boolean matchesBagFilters(
            String bagId,
            ShipmentBatch batch,
            BagSnapshot snapshot,
            Query query) {
        if (!query.state().isBlank() && !snapshot.state().equalsIgnoreCase(query.state())) {
            return false;
        }
        if (query.text().isBlank()) {
            return true;
        }

        String searchable = String.join(" ",
            bagId,
            batch.batchId(),
            batch.clientId(),
            batch.origin().id(),
            batch.destination().id(),
            snapshot.state(),
            snapshot.currentAirportId() != null ? snapshot.currentAirportId() : "",
            snapshot.currentFlightId() != null ? snapshot.currentFlightId() : ""
        ).toLowerCase(Locale.ROOT);
        return searchable.contains(query.text());
    }

    private static double progress(ZonedDateTime start, ZonedDateTime end, ZonedDateTime now) {
        long totalMs = Duration.between(start, end).toMillis();
        if (totalMs <= 0) return 1.0;
        long elapsedMs = Duration.between(start, now).toMillis();
        return Math.max(0.0, Math.min(1.0, (double) elapsedMs / totalMs));
    }

    private static String formatBagId(String batchId, int sequence) {
        return batchId + "-B" + String.format("%04d", sequence);
    }

    private static String format(ZonedDateTime value) {
        return value != null ? value.format(ISO) : null;
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record BagSnapshot(
        String state,
        String currentAirportId,
        String currentFlightId,
        boolean meetsSla,
        double progress
    ) {}

    private static final class SummaryAccumulator {
        private long totalBags;
        private long notRegisteredBags;
        private long pendingRouteBags;
        private long warehouseBags;
        private long inFlightBags;
        private long deliveredBags;
        private long delayedBags;

        void add(String state, int quantity, boolean meetsSla) {
            totalBags += quantity;
            if (!meetsSla && ("IN_FLIGHT".equals(state) || "AT_TRANSFER".equals(state) || "DELIVERED".equals(state))) {
                delayedBags += quantity;
            }
            switch (state) {
                case "NOT_REGISTERED" -> notRegisteredBags += quantity;
                case "PENDING_ROUTE" -> pendingRouteBags += quantity;
                case "IN_FLIGHT" -> inFlightBags += quantity;
                case "DELIVERED" -> deliveredBags += quantity;
                default -> warehouseBags += quantity;
            }
        }

        BagTraceabilityDTO.SummaryDTO toDto(long matchedBags) {
            return new BagTraceabilityDTO.SummaryDTO(
                totalBags,
                matchedBags,
                notRegisteredBags,
                pendingRouteBags,
                warehouseBags,
                inFlightBags,
                deliveredBags,
                delayedBags
            );
        }
    }
}