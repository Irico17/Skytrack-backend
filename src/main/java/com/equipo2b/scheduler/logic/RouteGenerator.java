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

    /**
     * Holgura de llegada (ε-Pareto): entre caminos que llegan ≤ earliest + ε y cumplen SLA,
     * preferir el de menor congestión de hubs/vuelos. Lexicográfico: tiempo primero (banda ε),
     * luego balance de recurso. Genérico para cualquier hub saturado.
     */
    private static final Duration ARRIVAL_SLACK_EPSILON = Duration.ofHours(2);

    /** Probabilidad de posponer vuelos directos cuando se pide explorar multi-hop. */
    private static final double DEFER_DIRECT_PROBABILITY = 0.55;

    /**
     * Probabilidad de elegir, entre los caminos cacheados factibles, el de MENOR score de
     * congestión en {@link #pickCapacityFeasible}. El complemento (25%) elige al azar entre
     * TODOS los factibles (incluido el mejor) para conservar diversidad: si siempre se eligiera
     * el mínimo, el GA/Tabú perdería la variedad de vecinos que necesita para explorar, y varios
     * lotes con el mismo origen/destino convergerían siempre al mismo camino, recreando el
     * problema que este cambio busca evitar (concentración en los mismos hubs "buenos").
     */
    private static final double CONGESTION_LEAST_LOADED_PROBABILITY = 0.75;

    /** Peso del término de ocupación de VUELO en el score de congestión de un camino. */
    private static final double CONGESTION_FLIGHT_WEIGHT = 0.5;

    /** Peso del término de ocupación de ALMACÉN (hubs intermedios) en el score de congestión. */
    private static final double CONGESTION_HUB_WEIGHT = 0.5;

    /**
     * Peso del término de espera en el almacén de ORIGEN. Deliberadamente menor que los otros
     * dos: el objetivo es desempatar entre caminos parecidos, no anteponer salir pronto a usar
     * vuelos y hubs libres (y jamás a la asignación o al SLA, que son órdenes de magnitud
     * mayores en el fitness).
     */
    private static final double CONGESTION_ORIGIN_WEIGHT = 0.35;

    /**
     * Convierte un ratio de ocupación (0-1+) en el coste que entra al score de congestión.
     *
     * <p>El coste es CONVEXO (cuadrático), no lineal. Con coste lineal, llevar un lote a un
     * hub que pasa del 20% al 30% costaba exactamente lo mismo que llevarlo a uno que pasa del
     * 60% al 70%, así que entre dos caminos factibles el planificador no tenía motivo para
     * preferir el almacén vacío: la diferencia solo pesaba de verdad al rozar el límite blando
     * del 80%, y por debajo la elección quedaba casi al azar. Medido en corridas reales, eso
     * dejaba desniveles de 24-28 puntos entre el almacén más cargado y la mediana de la red
     * (peor caso 54 puntos: uno al 67% con la mediana en 13%).
     *
     * <p>Al elevar al cuadrado, el coste marginal crece con la ocupación —igual que el término
     * convexo que ya usa la función de fitness para los picos de almacén, así que construcción
     * y evaluación dejan de discrepar—: el salto 60%→70% pesa 2,6 veces más que el 20%→30% y
     * el reparto se vuelve la opción barata. No toca ninguna restricción dura: solo reordena
     * entre caminos YA factibles, así que no puede empeorar asignación ni SLA.
     */
    private static double congestionCost(double ratio) {
        double r = Math.max(0.0, ratio);
        return r * r;
    }

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
                if (capacity != null && batchQuantity > 0) {
                    // Con contexto de capacidad: en vez de shuffle puro, explorar primero los
                    // vuelos MENOS congestionados (ratio de carga del vuelo + ocupación del hub
                    // de llegada) con jitter aleatorio para conservar la diversidad que el
                    // GA/Tabú necesitan. Así hasta los individuos "aleatorios" nacen sesgados
                    // hacia el balanceo, en lugar de depender de que el fitness los corrija
                    // después — el shuffle puro trataba igual un vuelo al 95% que uno al 5%.
                    // Las claves se PRECOMPUTAN (una por vuelo) antes de ordenar: un comparator
                    // con aleatoriedad interna sería inconsistente entre comparaciones y viola
                    // el contrato de sort (TimSort puede lanzar IllegalArgumentException).
                    Map<Flight, Double> explorationKey = new HashMap<>();
                    for (Flight f : availableFlights) {
                        explorationKey.put(f,
                            explorationCongestionKey(f, batchQuantity, destination, capacity, random));
                    }
                    availableFlights.sort(Comparator.comparingDouble(explorationKey::get));
                } else {
                    Collections.shuffle(availableFlights, random);
                }
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
                    // Origen: estancia [now, primer despegue) — mismo criterio que pathFitsWarehouseHard.
                    if (node.path.isEmpty()
                            && !capacity.hasHubCapacity(
                                origin, batchQuantity, startTime, flight.departureTime())) {
                        continue;
                    }
                    Airport hub = flight.destination();
                    boolean isFinalDestination = hub.equals(destination);
                    ZonedDateTime holdUntil = isFinalDestination
                        ? flight.arrivalTime().plus(CapacityContext.FINAL_PICKUP_WINDOW)
                        : minTime(flight.arrivalTime().plus(CapacityContext.PROVISIONAL_HUB_HOLD), deadline);
                    if (!isFinalDestination) {
                        if (!capacity.hasHubCapacity(hub, batchQuantity, flight.arrivalTime(), holdUntil)
                                || capacity.isHubNearLimit(hub, batchQuantity, flight.arrivalTime(), holdUntil)) {
                            continue;
                        }
                    } else if (!capacity.hasHubCapacity(hub, batchQuantity, flight.arrivalTime(), holdUntil)) {
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
     *
     * <p>El criterio de expansión de la cola de prioridad es SIEMPRE la hora de llegada; a
     * igualdad exacta de hora (rara en la práctica) se añade un desempate secundario por menor
     * ocupación relativa de almacén cuando hay {@link CapacityContext} — ver el comentario junto
     * a la construcción de la cola dentro del método.</p>
     */
    private List<Flight> findEarliestArrivalPath(Airport origin, Airport destination,
                                                 ZonedDateTime startTime, Duration sla,
                                                 int batchQuantity, CapacityContext capacity) {
        List<Flight> earliest = findMinArrivalPath(
            origin, destination, startTime, sla, batchQuantity, capacity);
        if (earliest == null || earliest.isEmpty() || capacity == null || batchQuantity <= 0) {
            return earliest;
        }
        // ε-Pareto solo si el camino earliest ya toca un hub en zona soft — evita 2× Dijkstra
        // en cada lote (congelaba ciclos densos / Ta).
        if (!earliestPathStressesHubs(earliest, destination, batchQuantity, capacity)) {
            return earliest;
        }
        ZonedDateTime bestArrival = earliest.get(earliest.size() - 1).arrivalTime();
        ZonedDateTime slaDeadline = startTime.plus(sla);
        ZonedDateTime slackCap = bestArrival.plus(ARRIVAL_SLACK_EPSILON);
        ZonedDateTime arrivalLimit = slackCap.isBefore(slaDeadline) ? slackCap : slaDeadline;
        List<Flight> balanced = findMinCongestionPath(
            origin, destination, startTime, sla, batchQuantity, capacity, arrivalLimit);
        if (balanced == null || balanced.isEmpty()) {
            return earliest;
        }
        return balanced;
    }

    private static boolean earliestPathStressesHubs(
            List<Flight> path, Airport destination, int qty, CapacityContext capacity) {
        for (int i = 0; i < path.size() - 1; i++) {
            Flight flight = path.get(i);
            Airport hub = flight.destination();
            if (hub.equals(destination)) {
                continue;
            }
            // Soft por INTERVALO de layover (no pico global): dispara ε solo si esa escala
            // concreta ya está caliente en su ventana real.
            if (capacity.isHubNearLimit(hub, qty, flight.arrivalTime(), path.get(i + 1).departureTime())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Dijkstra por llegada más temprana (objetivo primario de SLA operativo).
     */
    private List<Flight> findMinArrivalPath(Airport origin, Airport destination,
                                            ZonedDateTime startTime, Duration sla,
                                            int batchQuantity, CapacityContext capacity) {
        ZonedDateTime deadline = startTime.plus(sla);

        record Label(Airport airport, ZonedDateTime time, List<Flight> path) {}

        Comparator<Label> ordering = Comparator.comparing(Label::time);
        if (capacity != null) {
            ordering = ordering.thenComparingDouble(label -> relativeStorageOccupancy(label.airport(), capacity));
        }
        PriorityQueue<Label> frontier = new PriorityQueue<>(ordering);
        frontier.add(new Label(origin, startTime, List.of()));
        Map<String, ZonedDateTime> bestArrival = new HashMap<>();

        while (!frontier.isEmpty()) {
            Label label = frontier.poll();

            if (label.airport().equals(destination)) {
                return new ArrayList<>(label.path());
            }
            ZonedDateTime known = bestArrival.get(label.airport().id());
            if (known != null && !label.time().isBefore(known)) {
                continue;
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
                    if (label.path().isEmpty()
                            && !capacity.hasHubCapacity(
                                origin, batchQuantity, startTime, flight.departureTime())) {
                        continue;
                    }
                    if (!edgeHubCapacityOk(flight, destination, batchQuantity, capacity, deadline)) {
                        continue;
                    }
                }
                ZonedDateTime prevArrival = bestArrival.get(flight.destination().id());
                if (prevArrival != null && !flight.arrivalTime().isBefore(prevArrival)) {
                    continue;
                }
                List<Flight> newPath = new ArrayList<>(label.path().size() + 1);
                newPath.addAll(label.path());
                newPath.add(flight);
                frontier.add(new Label(flight.destination(), flight.arrivalTime(), newPath));
            }
        }

        return null;
    }

    /**
     * Entre caminos con llegada ≤ {@code arrivalLimit}, minimiza congestión (hubs+vuelos).
     * Desempate: llegada más temprana.
     *
     * <p><b>Es una heurística, no una búsqueda exhaustiva.</b> La poda de dominancia mira
     * SOLO la congestión ({@code bestCongestion} por aeropuerto), no el par (congestión,
     * tiempo). Un camino con algo más de congestión pero que llega mucho antes puede quedar
     * descartado aunque fuera el único que alcanza el destino dentro del límite. Una búsqueda
     * correcta necesitaría etiquetas de Pareto (frente por aeropuerto), bastante más caras.
     *
     * <p>Es aceptable porque el llamador la usa como ALTERNATIVA opcional: si devuelve
     * {@code null} se conserva el camino de llegada más temprana, así que un fallo de esta
     * poda nunca deja al lote sin ruta — solo pierde una oportunidad de balanceo.</p>
     */
    private List<Flight> findMinCongestionPath(
            Airport origin,
            Airport destination,
            ZonedDateTime startTime,
            Duration sla,
            int batchQuantity,
            CapacityContext capacity,
            ZonedDateTime arrivalLimit) {
        ZonedDateTime deadline = startTime.plus(sla);

        record Label(Airport airport, ZonedDateTime time, List<Flight> path, double congestion) {}

        Comparator<Label> ordering = Comparator
            .comparingDouble(Label::congestion)
            .thenComparing(Label::time);
        PriorityQueue<Label> frontier = new PriorityQueue<>(ordering);
        frontier.add(new Label(origin, startTime, List.of(), 0.0));
        Map<String, Double> bestCongestion = new HashMap<>();

        List<Flight> bestPath = null;
        double bestScore = Double.POSITIVE_INFINITY;
        ZonedDateTime bestArr = null;

        while (!frontier.isEmpty()) {
            Label label = frontier.poll();

            if (label.airport().equals(destination)) {
                if (label.time().isAfter(arrivalLimit)) {
                    continue;
                }
                if (label.congestion < bestScore
                        || (label.congestion == bestScore
                            && (bestArr == null || label.time().isBefore(bestArr)))) {
                    bestScore = label.congestion;
                    bestArr = label.time();
                    bestPath = new ArrayList<>(label.path());
                }
                continue;
            }

            String key = label.airport().id();
            Double known = bestCongestion.get(key);
            if (known != null && label.congestion > known + 1e-9) {
                continue;
            }
            bestCongestion.put(key, label.congestion);

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
                // Solo se poda por arrivalLimit en el DESTINO final: llegar "tarde" a una
                // escala intermedia no descalifica el camino (puede seguir alcanzando el
                // destino dentro de la banda ε), así que ahí no se poda.
                if (flight.destination().equals(destination)
                        && flight.arrivalTime().isAfter(arrivalLimit)) {
                    continue;
                }
                if (capacity != null && batchQuantity > 0) {
                    if (!capacity.hasFlightCapacity(flight, batchQuantity)) {
                        continue;
                    }
                    if (label.path().isEmpty()
                            && !capacity.hasHubCapacity(
                                origin, batchQuantity, startTime, flight.departureTime())) {
                        continue;
                    }
                    if (!edgeHubCapacityOk(flight, destination, batchQuantity, capacity, deadline)) {
                        continue;
                    }
                }

                double edgeScore = edgeCongestionScore(flight, batchQuantity, destination, capacity);
                double nextCongestion = Math.max(label.congestion, edgeScore);
                List<Flight> newPath = new ArrayList<>(label.path().size() + 1);
                newPath.addAll(label.path());
                newPath.add(flight);
                frontier.add(new Label(
                    flight.destination(), flight.arrivalTime(), newPath, nextCongestion));
            }
        }

        return bestPath;
    }

    private static boolean edgeHubCapacityOk(
            Flight flight,
            Airport destination,
            int batchQuantity,
            CapacityContext capacity,
            ZonedDateTime deadline) {
        Airport hub = flight.destination();
        boolean isFinalDestination = hub.equals(destination);
        ZonedDateTime holdUntil = isFinalDestination
            ? flight.arrivalTime().plus(CapacityContext.FINAL_PICKUP_WINDOW)
            : minTime(flight.arrivalTime().plus(CapacityContext.PROVISIONAL_HUB_HOLD), deadline);
        if (!isFinalDestination) {
            return capacity.hasHubCapacity(hub, batchQuantity, flight.arrivalTime(), holdUntil)
                && !capacity.isHubNearLimit(hub, batchQuantity, flight.arrivalTime(), holdUntil);
        }
        return capacity.hasHubCapacity(hub, batchQuantity, flight.arrivalTime(), holdUntil);
    }

    private static double edgeCongestionScore(
            Flight flight, int qty, Airport destination, CapacityContext capacity) {
        double flightScore = 0.0;
        int flightCap = flight.capacity();
        if (flightCap > 0) {
            flightScore = congestionCost((capacity.flightLoad(flight) + qty) / (double) flightCap);
        }
        double hubScore = 0.0;
        Airport hub = flight.destination();
        if (!hub.equals(destination)) {
            int hubCap = hub.storageCapacity();
            if (hubCap > 0) {
                hubScore = congestionCost((capacity.storageOccupancy(hub) + qty) / (double) hubCap);
            }
        }
        return CONGESTION_FLIGHT_WEIGHT * flightScore + CONGESTION_HUB_WEIGHT * hubScore;
    }

    private static ZonedDateTime minTime(ZonedDateTime a, ZonedDateTime b) {
        return a.isBefore(b) ? a : b;
    }

    /** Ratio ocupación/capacidad de almacén de un aeropuerto, para el desempate de Dijkstra. */
    private static double relativeStorageOccupancy(Airport airport, CapacityContext capacity) {
        int cap = airport.storageCapacity();
        if (cap <= 0) {
            return 0.0;
        }
        return capacity.storageOccupancy(airport) / (double) cap;
    }

    /** Amplitud del jitter aleatorio en el orden de exploración del BFS (ver findPath). */
    private static final double EXPLORATION_JITTER = 0.35;

    /**
     * Clave de orden de exploración de UN vuelo en el BFS aleatorizado: ratio de carga del
     * vuelo tras sumar el lote + ocupación relativa del almacén de llegada (salvo que sea el
     * destino final, cuya ocupación es igual para todo camino que termine ahí), más un jitter
     * uniforme en [0, {@link #EXPLORATION_JITTER}) que conserva la diversidad — dos vuelos con
     * ~35 puntos porcentuales de diferencia de congestión aún pueden intercambiar orden, pero
     * uno al 95% ya casi nunca se explora antes que uno al 5%. Menor = se explora antes.
     */
    private static double explorationCongestionKey(
            Flight flight, int quantity, Airport destination, CapacityContext capacity, Random random) {
        double flightRatio = flight.capacity() > 0
            ? (capacity.flightLoad(flight) + quantity) / (double) flight.capacity()
            : 0.0;
        double hubRatio = flight.destination().equals(destination)
            ? 0.0
            : relativeStorageOccupancy(flight.destination(), capacity);
        return CONGESTION_FLIGHT_WEIGHT * flightRatio
            + CONGESTION_HUB_WEIGHT * hubRatio
            + random.nextDouble() * EXPLORATION_JITTER;
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

        // Nivel 1: respetando el umbral SUAVE (prefiere hubs holgados y reparte carga).
        AssignedRoute strict = earliestPathRoute(batch, sla, capacity);
        if (strict != null || capacity == null) {
            return strict;
        }
        // NIVEL 2 (último recurso): relajar SOLO el suave; la capacidad DURA de almacén
        // sigue intacta, así que nunca se pasa del 100%.
        //
        // Sin este nivel bastaba con que un hub entrara en la banda 80–100% para que NINGÚN
        // lote pudiera cruzarlo: findMaxRoutableQuantity sondea con 1 maleta y también
        // fallaba, así que ni siquiera el split parcial servía y el lote entero caía a
        // reintento. El efecto neto no era "menos carga en el hub" sino backlog creciente
        // acumulado en los almacenes de ORIGEN, que también tienen capacidad.
        return earliestPathRoute(batch, sla, capacity.withHubSoftLimitRelaxed());
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
            // Aceptación final: el Dijkstra puede haber usado hold provisional ≠ layover real.
            if (capacity != null
                    && !capacity.pathFitsHardWithSoft(
                        batch, flightPath, batch.quantity(), batch.destination())) {
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

            // Llegar aquí significa que ya fallaron los dos niveles: ni los caminos cacheados
            // caben bajo el umbral suave, ni la búsqueda fresca encontró uno (ni siquiera con
            // el suave relajado — ver generateFeasibleRouteUncached, que agota ambos niveles
            // manteniendo intacta la capacidad DURA). El lote queda sin ruta este ciclo y pasa
            // a reintento.
            return null;
        }

        return generateFeasibleRouteUncached(
            batch, sla, allowedFlights, maxAttempts, capacity, preferMultiHop);
    }

    /**
     * Elige un camino entre los cacheados que caben (factibles). Antes se elegía UNIFORME AL
     * AZAR entre los factibles: la capacidad solo filtraba (caben/no caben) pero nunca guiaba
     * la elección, así que ante varios caminos igualmente factibles el generador era indiferente
     * entre uno que deja los vuelos/hubs casi llenos y otro que los deja casi vacíos — sesgo que
     * se sumaba al de {@link #findEarliestArrivalPath} (que solo mira llegada más temprana) para
     * concentrar tráfico siempre en los mismos hubs "rápidos".
     *
     * <p>Ahora, cuando hay {@link CapacityContext} (que es cuando esta elección importa: sin
     * capacidad no hay ocupación que consultar), se puntúa cada camino factible por congestión
     * (ver {@link #congestionScore}) y se prefiere el de MENOR score con probabilidad
     * {@link #CONGESTION_LEAST_LOADED_PROBABILITY} — el resto de las veces se elige al azar
     * entre todos los factibles, para no perder la diversidad de vecinos que necesita el GA/Tabú.
     */
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

        List<Flight> chosen;
        if (capacity == null || feasible.size() == 1) {
            // Sin contexto de capacidad no hay ocupación que consultar (comportamiento
            // aleatorio de siempre); con un solo factible tampoco hay nada que decidir.
            chosen = feasible.get(ThreadLocalRandom.current().nextInt(feasible.size()));
        } else {
            chosen = pickByCongestionScore(batch, feasible, capacity);
        }

        try {
            return new AssignedRoute(batch, chosen);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Elige el camino de menor congestión con probabilidad alta, o uno al azar entre todos
     * los factibles el resto de las veces (diversidad para el GA/Tabú). Ver
     * {@link #CONGESTION_LEAST_LOADED_PROBABILITY}.
     */
    private static List<Flight> pickByCongestionScore(
            ShipmentBatch batch, List<List<Flight>> feasible, CapacityContext capacity) {
        int qty = batch.quantity();
        Airport destination = batch.destination();

        List<Flight> leastLoaded = feasible.get(0);
        double bestScore = congestionScore(leastLoaded, qty, destination, capacity, batch);
        for (int i = 1; i < feasible.size(); i++) {
            List<Flight> candidate = feasible.get(i);
            double score = congestionScore(candidate, qty, destination, capacity, batch);
            if (score < bestScore) {
                bestScore = score;
                leastLoaded = candidate;
            }
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (random.nextDouble() < CONGESTION_LEAST_LOADED_PROBABILITY) {
            return leastLoaded;
        }
        return feasible.get(random.nextInt(feasible.size()));
    }

    /**
     * Score de congestión de UN camino: suma ponderada simple (0.5/0.5) de
     * <ul>
     *   <li>el PEOR (máximo) ratio de ocupación de vuelo tras sumar {@code qty}, entre todos
     *       los vuelos del camino: {@code (flightLoad(vuelo) + qty) / vuelo.capacity()}</li>
     *   <li>el PEOR (máximo) ratio de ocupación de almacén tras sumar {@code qty}, entre los
     *       hubs INTERMEDIOS del camino (se excluye el destino final: su ocupación es la misma
     *       para todo camino que termine ahí, así que no aporta señal para elegir ENTRE
     *       caminos): {@code (storageOccupancy(hub) + qty) / hub.storageCapacity()}</li>
     * </ul>
     * Menor score = camino que deja vuelos y hubs relativamente más libres. Se usa el máximo
     * (no el promedio) por tramo porque un solo vuelo/hub casi lleno en el camino ya es el
     * cuello de botella real, aunque el resto del camino esté vacío.
     */
    private static double congestionScore(
            List<Flight> path, int qty, Airport destination, CapacityContext capacity,
            ShipmentBatch batch) {
        double flightScore = 0.0;
        double hubScore = 0.0;

        for (Flight flight : path) {
            int flightCap = flight.capacity();
            if (flightCap > 0) {
                double ratio = (capacity.flightLoad(flight) + qty) / (double) flightCap;
                flightScore = Math.max(flightScore, congestionCost(ratio));
            }

            Airport hub = flight.destination();
            if (!hub.equals(destination)) {
                int hubCap = hub.storageCapacity();
                if (hubCap > 0) {
                    double ratio = (capacity.storageOccupancy(hub) + qty) / (double) hubCap;
                    hubScore = Math.max(hubScore, congestionCost(ratio));
                }
            }
        }

        return CONGESTION_FLIGHT_WEIGHT * flightScore
            + CONGESTION_HUB_WEIGHT * hubScore
            + CONGESTION_ORIGIN_WEIGHT * originWaitCost(path, qty, capacity, batch);
    }

    /**
     * Coste de dejar el lote esperando en el almacén de ORIGEN hasta su primer despegue.
     *
     * <p>El score de congestión solo miraba los vuelos y los hubs INTERMEDIOS: el origen no
     * entraba en la cuenta. Entre dos caminos factibles, uno que despega a las 08:00 y otro a
     * las 14:00 puntuaban idéntico, aunque el segundo deja las maletas seis horas más en un
     * almacén que puede estar al 70%.
     *
     * <p>Y ese es justamente el término que faltaba. Medido sobre una solución real de 10.136
     * rutas: la ocupación NO se explica por el volumen que pasa por el aeropuerto (correlación
     * 0,19) ni por el tiempo en escala (0,11; la espera mediana en escala es de 1 hora, con
     * p90 de 2,5 h — las maletas en tránsito apenas ocupan). Lo que llena un almacén son las
     * maletas esperando su PRIMER vuelo en el origen, que era lo único que la elección de
     * camino no valoraba.
     *
     * <p>El coste es ocupación-del-origen (convexa) × horas de espera, normalizado a un día:
     * esperar en un almacén vacío sigue siendo gratis, y esperar en uno lleno se encarece con
     * cada hora. Como el resto del score, solo reordena caminos YA factibles.
     */
    private static double originWaitCost(
            List<Flight> path, int qty, CapacityContext capacity, ShipmentBatch batch) {
        if (batch == null || path.isEmpty()) {
            return 0.0;
        }
        Airport origin = batch.origin();
        int cap = origin.storageCapacity();
        if (cap <= 0) {
            return 0.0;
        }
        double occupancy = congestionCost((capacity.storageOccupancy(origin) + qty) / (double) cap);
        double waitHours = Duration.between(batch.ingressTime(), path.get(0).departureTime()).toMinutes() / 60.0;
        if (waitHours <= 0) {
            return 0.0;
        }
        return occupancy * Math.min(waitHours / 24.0, 1.0);
    }

    private boolean isPathCapacityFeasible(
            List<Flight> path, ShipmentBatch batch, CapacityContext capacity) {
        if (capacity == null) {
            return true;
        }
        return capacity.pathFitsHardWithSoft(batch, path, batch.quantity(), batch.destination());
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
     * Busca una ruta factible en dos niveles, NINGUNO de los cuales relaja jamás la capacidad
     * DURA de almacén ({@link CapacityContext#hasHubCapacity}) — un almacén nunca se desborda
     * por esta vía.
     *
     * <ol>
     *   <li><b>Estricto</b>: respeta también el umbral SUAVE (80%), así que prefiere hubs
     *       holgados y reparte carga.</li>
     *   <li><b>Soft relajado</b> (último recurso): usa la banda 80–100% que sí existe, pero
     *       nunca supera el 100%.</li>
     * </ol>
     *
     * <p><b>Por qué el nivel 2 tiene que existir:</b> rechazar el lote NO evita ocupar
     * almacén — las maletas se quedan físicamente en el almacén de ORIGEN (y sin ruta, sin
     * fecha de salida). Sin este nivel, cuando los hubs entran en la banda 80–100% el
     * planificador deja de emitir rutas y el desborde simplemente se traslada a los orígenes,
     * que además acumulan backlog ciclo tras ciclo. Usar capacidad real disponible mientras
     * el tope duro del 100% siga garantizado es estrictamente mejor que no transportar.</p>
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

        // NIVEL 2: relajar SOLO el umbral suave. La capacidad dura de almacén y de vuelo
        // siguen intactas (el sobrebookeo de vuelo lo resuelve el split parcial, no esto).
        int relaxedAttempts = Math.max(2, maxAttempts / 2);
        return tryGenerateRoute(
            batch, sla, allowedFlights, relaxedAttempts,
            capacity.withHubSoftLimitRelaxed(), preferMultiHop);
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
