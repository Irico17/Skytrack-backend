package com.equipo2b.scheduler.logic;

import com.equipo2b.scheduler.model.*;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.*;

/**
 * Genera rutas factibles para lotes de maletas.
 * Utiliza búsqueda de caminos (BFS) con validación de restricciones temporales.
 *
 * <p>Restricciones verificadas en BFS:
 * <ul>
 *   <li>Tiempos de escala (mínimo 10 minutos)</li>
 *   <li>SLA (24h mismo continente, 48h diferentes continentes)</li>
 *   <li>Conexiones válidas entre vuelos</li>
 *   <li>Capacidad residual de vuelos y hubs (cuando se pasa {@link CapacityContext})</li>
 * </ul>
 *
 * <p>Sin {@link CapacityContext}, la BFS solo valida topología/tiempo/SLA; la capacidad
 * dura la evalúa {@link SolutionEvaluator}. Con contexto (construcción GA/Tabú), se
 * filtran vuelos sin residual y hubs cerca del límite según ocupación parcial + baseline.
 *
 * <p><strong>Validates: Requirements 14.1, 14.6</strong>
 */
public class RouteGenerator {
    private final FlightPlan flightPlan;
    private final AirportManager airportManager;
    private final Map<RouteCacheKey, List<List<Flight>>> routePathCache = new ConcurrentHashMap<>();

    private int maxAttempts = 12;
    private int maxCachedVariants = 5;
    // Las claves incluyen instante de ingreso; en simulaciones masivas casi no se
    // reutilizan entre lotes. Un límite de 20k retenía rutas de ciclos antiguos y
    // presionaba innecesariamente el heap de 1 GB.
    private static final int MAX_ROUTE_CACHE_ENTRIES = 4_096;
    // Tope práctico de tramos por ruta. No se limita "artificialmente" a pocos saltos:
    // el SLA (24h intra / 48h inter) ya poda los caminos largos y la BFS está acotada por
    // su set `visited` (aeropuerto+hora), así que subir a 5 no degrada el rendimiento.
    // 5 tramos es el máximo que el SLA permite en la práctica.
    private static final int MAX_HOPS = 5;

    /** Probabilidad de posponer vuelos directos cuando se pide explorar multi-hop. */
    private static final double DEFER_DIRECT_PROBABILITY = 0.55;

    /**
     * @param flightPlan Plan maestro de vuelos disponibles
     * @param airportManager Gestor de aeropuertos (capacidades de almacén para filtros)
     */
    public RouteGenerator(FlightPlan flightPlan, AirportManager airportManager) {
        this.flightPlan = Objects.requireNonNull(flightPlan, "FlightPlan cannot be null");
        this.airportManager = Objects.requireNonNull(airportManager, "AirportManager cannot be null");
    }

    public void configureSearchEffort(int maxAttempts, int maxCachedVariants) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.maxCachedVariants = Math.max(1, maxCachedVariants);
        routePathCache.clear();
    }

    /** Tope de tramos por ruta (acotado por SLA en la práctica). */
    public static int maxHops() {
        return MAX_HOPS;
    }

    /**
     * Búsqueda BFS de camino entre aeropuertos.
     * Considera restricciones temporales, SLA y, si hay contexto, capacidad residual.
     */
    private List<Flight> findPath(Airport origin, Airport destination,
                                  ZonedDateTime startTime, Duration sla,
                                  List<Flight> allowedFlights, boolean randomize,
                                  int batchQuantity, CapacityContext capacity,
                                  boolean preferMultiHop) {
        ZonedDateTime deadline = startTime.plus(sla);

        Queue<SearchNode> queue = new LinkedList<>();
        queue.add(new SearchNode(origin, startTime, new ArrayList<>()));

        Set<String> visited = new HashSet<>();
        Random random = randomize ? ThreadLocalRandom.current() : null;

        while (!queue.isEmpty()) {
            SearchNode node = queue.poll();

            if (node.airport.equals(destination)) {
                return node.path;
            }

            if (node.path.size() >= MAX_HOPS) {
                continue;
            }

            List<Flight> availableFlights;
            if (allowedFlights != null) {
                availableFlights = new ArrayList<>(allowedFlights.stream()
                    .filter(f -> f.origin().equals(node.airport))
                    .filter(f -> f.departureTime().isAfter(node.currentTime))
                    .filter(f -> f.arrivalTime().isBefore(deadline))
                    .toList());
            } else {
                availableFlights = new ArrayList<>(
                    flightPlan.getFlightsFromAirport(node.airport, node.currentTime, deadline)
                );
            }

            if (randomize && random != null) {
                Collections.shuffle(availableFlights, random);
                if (preferMultiHop && node.path.isEmpty() && random.nextDouble() < DEFER_DIRECT_PROBABILITY) {
                    deferDirectFlights(availableFlights, destination);
                }
            } else {
                availableFlights.sort(Comparator
                    .comparing(Flight::departureTime)
                    .thenComparing(Flight::arrivalTime)
                    .thenComparing(Flight::flightId));
                if (preferMultiHop && node.path.isEmpty()) {
                    deferDirectFlights(availableFlights, destination);
                }
            }

            for (Flight flight : availableFlights) {
                Duration layover = Duration.between(node.currentTime, flight.departureTime());
                if (layover.toMinutes() < 10) {
                    continue;
                }

                if (!flight.arrivalTime().isBefore(deadline)) {
                    continue;
                }

                if (capacity != null && batchQuantity > 0) {
                    if (!capacity.hasFlightCapacity(flight, batchQuantity)) {
                        continue;
                    }
                    Airport hub = flight.destination();
                    boolean isFinalDestination = hub.equals(destination);
                    // Origen ya ocupa espacio; hubs intermedios y destino final necesitan residual.
                    // Intermedios: filtrar duro + soft-limit. Destino: solo residual duro.
                    if (!isFinalDestination) {
                        if (!capacity.hasHubCapacity(hub, batchQuantity) || capacity.isHubNearLimit(hub)) {
                            continue;
                        }
                    } else if (!capacity.hasHubCapacity(hub, batchQuantity)) {
                        continue;
                    }
                }

                List<Flight> newPath = new ArrayList<>(node.path);
                newPath.add(flight);

                String visitKey = flight.destination().id() + "_" +
                                 flight.arrivalTime().toEpochSecond() / 3600;

                if (!visited.contains(visitKey)) {
                    visited.add(visitKey);
                    queue.add(new SearchNode(
                        flight.destination(),
                        flight.arrivalTime(),
                        newPath
                    ));
                }
            }
        }

        return null;
    }

    /**
     * Búsqueda de LLEGADA MÁS TEMPRANA: variante de Dijkstra (label-setting) sobre la red
     * tiempo-dependiente de vuelos.
     *
     * <p>Diferencia clave con el BFS de {@link #findPath}: el BFS con cola FIFO devuelve el
     * primer camino que alcanza el destino en orden de NÚMERO DE ESCALAS — puede preferir un
     * directo que despega 10 horas más tarde sobre una escala que ya habría llegado. Aquí la
     * cola de prioridad expande siempre la etiqueta con menor hora de llegada y la dominancia
     * por aeropuerto ("ya llegué antes ahí") poda el resto, así que el primer camino que toca
     * el destino es EL de llegada más temprana — máximo margen de SLA, y con escalas cuando
     * las escalas genuinamente llegan antes.</p>
     *
     * <p>Mismos filtros que el BFS: escala mínima 10 min, deadline de SLA, capacidad residual
     * de vuelos y hubs cuando hay {@link CapacityContext}. El tope {@link #MAX_HOPS} acota la
     * profundidad (con la poda por dominancia casi nunca se alcanza).</p>
     */
    private List<Flight> findEarliestArrivalPath(Airport origin, Airport destination,
                                                 ZonedDateTime startTime, Duration sla,
                                                 int batchQuantity, CapacityContext capacity) {
        ZonedDateTime deadline = startTime.plus(sla);

        record Label(Airport airport, ZonedDateTime time, List<Flight> path) {}
        PriorityQueue<Label> frontier = new PriorityQueue<>(Comparator.comparing(Label::time));
        frontier.add(new Label(origin, startTime, List.of()));
        Map<String, ZonedDateTime> bestArrival = new HashMap<>();

        while (!frontier.isEmpty()) {
            Label label = frontier.poll();

            if (label.airport().equals(destination)) {
                return new ArrayList<>(label.path());
            }
            ZonedDateTime known = bestArrival.get(label.airport().id());
            if (known != null && !label.time().isBefore(known)) {
                continue;  // dominada: ya alcanzamos este aeropuerto más temprano
            }
            bestArrival.put(label.airport().id(), label.time());

            if (label.path().size() >= MAX_HOPS) {
                continue;
            }

            for (Flight flight : flightPlan.getFlightsFromAirport(label.airport(), label.time(), deadline)) {
                if (Duration.between(label.time(), flight.departureTime()).toMinutes() < 10) {
                    continue;
                }
                if (!flight.arrivalTime().isBefore(deadline)) {
                    continue;
                }
                if (capacity != null && batchQuantity > 0) {
                    if (!capacity.hasFlightCapacity(flight, batchQuantity)) {
                        continue;
                    }
                    Airport hub = flight.destination();
                    boolean isFinalDestination = hub.equals(destination);
                    if (!isFinalDestination) {
                        if (!capacity.hasHubCapacity(hub, batchQuantity) || capacity.isHubNearLimit(hub)) {
                            continue;
                        }
                    } else if (!capacity.hasHubCapacity(hub, batchQuantity)) {
                        continue;
                    }
                }
                ZonedDateTime prevArrival = bestArrival.get(flight.destination().id());
                if (prevArrival != null && !flight.arrivalTime().isBefore(prevArrival)) {
                    continue;  // poda temprana: llegaríamos igual o más tarde que lo ya logrado
                }
                List<Flight> newPath = new ArrayList<>(label.path().size() + 1);
                newPath.addAll(label.path());
                newPath.add(flight);
                frontier.add(new Label(flight.destination(), flight.arrivalTime(), newPath));
            }
        }

        return null;
    }

    /** Mueve vuelos directos al final de la lista para explorar escalas primero. */
    private static void deferDirectFlights(List<Flight> flights, Airport destination) {
        List<Flight> directs = new ArrayList<>();
        List<Flight> others = new ArrayList<>();
        for (Flight f : flights) {
            if (f.destination().equals(destination)) {
                directs.add(f);
            } else {
                others.add(f);
            }
        }
        flights.clear();
        flights.addAll(others);
        flights.addAll(directs);
    }

    public AssignedRoute generateFeasibleRoute(ShipmentBatch batch) {
        return generateFeasibleRoute(batch, null, null, false);
    }

    public AssignedRoute generateFeasibleRoute(ShipmentBatch batch, CapacityContext capacity) {
        return generateFeasibleRoute(batch, null, capacity, false);
    }

    /**
     * Variante que prioriza caminos con escalas cuando hay alternativas (balanceo de red).
     */
    public AssignedRoute generateFeasibleRoutePreferMultiHop(ShipmentBatch batch, CapacityContext capacity) {
        return generateFeasibleRoute(batch, null, capacity, true);
    }

    /**
     * Genera una ruta factible IGNORANDO el cache de rutas (BFS aleatorizado fresco).
     */
    public AssignedRoute generateFeasibleRouteNoCache(ShipmentBatch batch) {
        return generateFeasibleRouteNoCache(batch, null, false);
    }

    public AssignedRoute generateFeasibleRouteNoCache(ShipmentBatch batch, CapacityContext capacity) {
        return generateFeasibleRouteNoCache(batch, capacity, false);
    }

    public AssignedRoute generateFeasibleRouteNoCache(
            ShipmentBatch batch, CapacityContext capacity, boolean preferMultiHop) {
        Objects.requireNonNull(batch, "Batch cannot be null");
        return generateFeasibleRouteUncached(
            batch, batch.calculateSLA(), null, maxAttempts, capacity, preferMultiHop);
    }

    public AssignedRoute generateEarliestFeasibleRoute(ShipmentBatch batch) {
        return generateEarliestFeasibleRoute(batch, null);
    }

    public AssignedRoute generateEarliestFeasibleRoute(ShipmentBatch batch, CapacityContext capacity) {
        Objects.requireNonNull(batch, "Batch cannot be null");
        Duration sla = batch.calculateSLA();

        AssignedRoute filtered = earliestPathRoute(batch, sla, capacity);
        if (filtered != null) {
            return filtered;
        }
        if (capacity == null) {
            return null;
        }
        // FALLBACK NIVEL 2: relajar solo el umbral suave de proximidad a hubs (92%). La
        // capacidad DURA de almacén (hasHubCapacity) se sigue exigiendo en cada escala —
        // nunca se permite desbordar un almacén, solo se amplía qué caminos se consideran.
        AssignedRoute relaxedSoft = earliestPathRoute(batch, sla, capacity.withHubSoftLimitRelaxed());
        if (relaxedSoft != null) {
            return relaxedSoft;
        }
        // FALLBACK NIVEL 3: además ignorar capacidad de VUELO (un desborde de vuelo se
        // corrige después vía applyCapacityAwareSplitting; uno de almacén no tiene
        // corrección posterior, así que su capacidad dura nunca se relaja aquí tampoco).
        AssignedRoute relaxedFlight = earliestPathRoute(
            batch, sla, capacity.withHubSoftLimitRelaxed().withFlightCapacityRelaxed());
        if (relaxedFlight != null) {
            return relaxedFlight;
        }
        // Ningún camino en la red respeta la capacidad de almacén dentro del SLA: el lote
        // queda sin ruta este ciclo (retry) en vez de forzar un desborde de almacén.
        return null;
    }

    private AssignedRoute earliestPathRoute(ShipmentBatch batch, Duration sla, CapacityContext capacity) {
        try {
            List<Flight> flightPath = findEarliestArrivalPath(
                batch.origin(),
                batch.destination(),
                batch.ingressTime(),
                sla,
                batch.quantity(),
                capacity
            );

            if (flightPath == null || flightPath.isEmpty()) {
                return null;
            }

            AssignedRoute route = new AssignedRoute(batch, flightPath);
            return route.meetsSLA() ? route : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public AssignedRoute generateFeasibleRoute(ShipmentBatch batch, List<Flight> allowedFlights) {
        return generateFeasibleRoute(batch, allowedFlights, null, false);
    }

    public AssignedRoute generateFeasibleRoute(
            ShipmentBatch batch, List<Flight> allowedFlights, CapacityContext capacity) {
        return generateFeasibleRoute(batch, allowedFlights, capacity, false);
    }

    /**
     * Genera una ruta factible. Con {@code capacity != null}, filtra por residual de
     * vuelos/hubs usando ocupación parcial de la solución en construcción.
     */
    public AssignedRoute generateFeasibleRoute(
            ShipmentBatch batch,
            List<Flight> allowedFlights,
            CapacityContext capacity,
            boolean preferMultiHop) {
        Objects.requireNonNull(batch, "Batch cannot be null");
        Duration sla = batch.calculateSLA();

        if (allowedFlights == null) {
            if (routePathCache.size() > MAX_ROUTE_CACHE_ENTRIES) {
                int toEvict = MAX_ROUTE_CACHE_ENTRIES / 4;
                var it = routePathCache.keySet().iterator();
                while (toEvict-- > 0 && it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
            RouteCacheKey key = RouteCacheKey.from(batch, sla);
            List<List<Flight>> cachedPaths = routePathCache.computeIfAbsent(
                key,
                ignored -> findCandidatePaths(batch, sla, null, maxCachedVariants, null, preferMultiHop)
            );

            List<List<Flight>> candidates = cachedPaths;
            if (preferMultiHop && capacity == null) {
                // Cache sin preferMultiHop: complementar con búsqueda fresca multi-hop
                List<List<Flight>> multi = findCandidatePaths(
                    batch, sla, null, maxCachedVariants, null, true);
                if (!multi.isEmpty()) {
                    candidates = mergeUniquePaths(cachedPaths, multi);
                }
            }

            AssignedRoute fromCache = pickCapacityFeasible(batch, candidates, capacity);
            if (fromCache != null) {
                return fromCache;
            }

            // Ningún path cacheado cabe: BFS fresco con filtros de capacidad
            if (capacity != null || preferMultiHop) {
                AssignedRoute fresh = generateFeasibleRouteUncached(
                    batch, sla, null, maxAttempts, capacity, preferMultiHop);
                if (fresh != null) {
                    return fresh;
                }
            }

            // FALLBACK NIVEL 2/3 sobre los paths cacheados: relajar umbral suave de hubs y,
            // si hace falta, capacidad de vuelo — la capacidad DURA de almacén nunca se
            // relaja (ver CapacityContext). Si ni así cabe, el lote queda sin ruta este
            // ciclo (retry) en vez de forzar un desborde de almacén.
            if (capacity != null) {
                AssignedRoute relaxedFromCache = pickCapacityFeasible(
                    batch, candidates, capacity.withHubSoftLimitRelaxed().withFlightCapacityRelaxed());
                if (relaxedFromCache != null) {
                    return relaxedFromCache;
                }
            }
            return null;
        }

        return generateFeasibleRouteUncached(
            batch, sla, allowedFlights, maxAttempts, capacity, preferMultiHop);
    }

    private AssignedRoute pickCapacityFeasible(
            ShipmentBatch batch, List<List<Flight>> paths, CapacityContext capacity) {
        if (paths == null || paths.isEmpty()) {
            return null;
        }
        List<List<Flight>> feasible = new ArrayList<>();
        for (List<Flight> path : paths) {
            if (isPathCapacityFeasible(path, batch, capacity)) {
                feasible.add(path);
            }
        }
        if (feasible.isEmpty()) {
            return null;
        }
        List<Flight> chosen = feasible.get(ThreadLocalRandom.current().nextInt(feasible.size()));
        try {
            return new AssignedRoute(batch, chosen);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private boolean isPathCapacityFeasible(
            List<Flight> path, ShipmentBatch batch, CapacityContext capacity) {
        if (capacity == null) {
            return true;
        }
        int qty = batch.quantity();
        Airport destination = batch.destination();
        for (Flight flight : path) {
            if (!capacity.hasFlightCapacity(flight, qty)) {
                return false;
            }
            Airport hub = flight.destination();
            boolean isFinal = hub.equals(destination);
            if (!isFinal) {
                if (!capacity.hasHubCapacity(hub, qty) || capacity.isHubNearLimit(hub)) {
                    return false;
                }
            } else if (!capacity.hasHubCapacity(hub, qty)) {
                return false;
            }
        }
        return true;
    }

    private static List<List<Flight>> mergeUniquePaths(
            List<List<Flight>> primary, List<List<Flight>> secondary) {
        Map<String, List<Flight>> unique = new LinkedHashMap<>();
        for (List<Flight> path : primary) {
            unique.putIfAbsent(pathSignature(path), path);
        }
        for (List<Flight> path : secondary) {
            unique.putIfAbsent(pathSignature(path), path);
        }
        return List.copyOf(unique.values());
    }

    private static String pathSignature(List<Flight> path) {
        return path.stream()
            .map(Flight::flightId)
            .reduce((a, b) -> a + ">" + b)
            .orElse("");
    }

    /**
     * Busca una ruta factible con hasta 3 niveles de relajación de capacidad, NINGUNO de
     * los cuales relaja jamás {@link CapacityContext#hasHubCapacity} — así un almacén nunca
     * se desborda por esta vía, a costa de que el lote pueda quedar sin ruta este ciclo (va
     * a reintento) si de verdad no existe ningún camino que respete su capacidad.
     */
    private AssignedRoute generateFeasibleRouteUncached(
            ShipmentBatch batch,
            Duration sla,
            List<Flight> allowedFlights,
            int maxAttempts,
            CapacityContext capacity,
            boolean preferMultiHop) {

        AssignedRoute strict = tryGenerateRoute(batch, sla, allowedFlights, maxAttempts, capacity, preferMultiHop);
        if (strict != null || capacity == null) {
            return strict;
        }

        int relaxedAttempts = Math.max(2, maxAttempts / 2);

        // FALLBACK NIVEL 2: relajar solo el umbral suave de proximidad a hubs (92%).
        AssignedRoute relaxedSoft = tryGenerateRoute(
            batch, sla, allowedFlights, relaxedAttempts, capacity.withHubSoftLimitRelaxed(), preferMultiHop);
        if (relaxedSoft != null) {
            return relaxedSoft;
        }

        // FALLBACK NIVEL 3: además ignorar capacidad de VUELO (se corrige después vía
        // applyCapacityAwareSplitting). La capacidad de almacén se sigue exigiendo siempre.
        return tryGenerateRoute(batch, sla, allowedFlights, relaxedAttempts,
            capacity.withHubSoftLimitRelaxed().withFlightCapacityRelaxed(), preferMultiHop);
    }

    private AssignedRoute tryGenerateRoute(
            ShipmentBatch batch,
            Duration sla,
            List<Flight> allowedFlights,
            int maxAttempts,
            CapacityContext capacity,
            boolean preferMultiHop) {

        try {
            List<Flight> deterministicPath = findPath(
                batch.origin(),
                batch.destination(),
                batch.ingressTime(),
                sla,
                allowedFlights,
                false,
                batch.quantity(),
                capacity,
                preferMultiHop
            );

            if (deterministicPath != null && !deterministicPath.isEmpty()) {
                AssignedRoute route = new AssignedRoute(batch, deterministicPath);
                if (route.meetsSLA()) {
                    return route;
                }
            }
        } catch (IllegalArgumentException e) {
            // Continuar con variantes aleatorias
        }

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                List<Flight> flightPath = findPath(
                    batch.origin(),
                    batch.destination(),
                    batch.ingressTime(),
                    sla,
                    allowedFlights,
                    true,
                    batch.quantity(),
                    capacity,
                    preferMultiHop || attempt % 2 == 1
                );

                if (flightPath == null || flightPath.isEmpty()) {
                    continue;
                }

                AssignedRoute route = new AssignedRoute(batch, flightPath);
                if (!route.meetsSLA()) {
                    continue;
                }

                return route;

            } catch (IllegalArgumentException e) {
                continue;
            }
        }

        return null;
    }

    private List<List<Flight>> findCandidatePaths(
            ShipmentBatch batch,
            Duration sla,
            List<Flight> allowedFlights,
            int maxCandidates,
            CapacityContext capacity,
            boolean preferMultiHop) {
        Map<String, List<Flight>> uniquePaths = new LinkedHashMap<>();

        try {
            List<Flight> deterministicPath = findPath(
                batch.origin(),
                batch.destination(),
                batch.ingressTime(),
                sla,
                allowedFlights,
                false,
                batch.quantity(),
                capacity,
                preferMultiHop
            );

            if (deterministicPath != null && !deterministicPath.isEmpty()) {
                AssignedRoute route = new AssignedRoute(batch, deterministicPath);
                if (route.meetsSLA()) {
                    uniquePaths.putIfAbsent(pathSignature(deterministicPath), List.copyOf(deterministicPath));
                }
            }
        } catch (IllegalArgumentException e) {
            // Intentar candidatos aleatorios
        }

        for (int attempt = 0; attempt < maxAttempts && uniquePaths.size() < maxCandidates; attempt++) {
            try {
                // Alternar preferMultiHop en intentos pares/impares para diversificar hops
                boolean multiHopAttempt = preferMultiHop || attempt % 2 == 1;
                List<Flight> flightPath = findPath(
                    batch.origin(),
                    batch.destination(),
                    batch.ingressTime(),
                    sla,
                    allowedFlights,
                    true,
                    batch.quantity(),
                    capacity,
                    multiHopAttempt
                );

                if (flightPath == null || flightPath.isEmpty()) {
                    continue;
                }

                AssignedRoute route = new AssignedRoute(batch, flightPath);
                if (!route.meetsSLA()) {
                    continue;
                }

                uniquePaths.putIfAbsent(pathSignature(flightPath), List.copyOf(flightPath));
            } catch (IllegalArgumentException e) {
                // Intentar otro candidato
            }
        }

        return List.copyOf(uniquePaths.values());
    }

    private record SearchNode(Airport airport, ZonedDateTime currentTime, List<Flight> path) {}

    private record RouteCacheKey(String originId, String destinationId, long ingressMinute, long slaMinutes) {
        private static RouteCacheKey from(ShipmentBatch batch, Duration sla) {
            return new RouteCacheKey(
                batch.origin().id(),
                batch.destination().id(),
                batch.ingressTime().toInstant().getEpochSecond() / 60,
                sla.toMinutes()
            );
        }
    }
}
