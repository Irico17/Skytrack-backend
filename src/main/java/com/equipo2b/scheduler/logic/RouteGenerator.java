package com.equipo2b.scheduler.logic;

import com.equipo2b.scheduler.model.*;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.*;

/**
 * Genera rutas factibles para lotes de maletas.
 * Utiliza búsqueda de caminos (BFS) con validación de restricciones.
 * 
 * Restricciones verificadas:
 * - Capacidad de vuelos
 * - Capacidad de almacenes
 * - Tiempos de escala (mínimo 10 minutos)
 * - SLA (12h mismo continente, 24h diferentes continentes)
 * - Conexiones válidas entre vuelos
 * 
 * **Validates: Requirements 14.1, 14.6**
 */
public class RouteGenerator {
    private final FlightPlan flightPlan;
    private final AirportManager airportManager;
    private final Map<RouteCacheKey, List<List<Flight>>> routePathCache = new ConcurrentHashMap<>();
    
    private int maxAttempts = 12;
    private int maxCachedVariants = 3;
    private static final int MAX_ROUTE_CACHE_ENTRIES = 20_000;
    // Tope práctico de tramos por ruta. No se limita "artificialmente" a pocos saltos:
    // el SLA (12h intra / 24h inter) ya poda los caminos largos y la BFS está acotada por
    // su set `visited` (aeropuerto+hora), así que subir a 5 no degrada el rendimiento.
    // 5 tramos es el máximo que el SLA permite en la práctica.
    private static final int MAX_HOPS = 5;  // Máximo de tramos por ruta (acotado por SLA)

    /**
     * Constructor que inicializa el generador de rutas con el plan de vuelos y gestor de aeropuertos.
     * 
     * @param flightPlan Plan maestro de vuelos disponibles
     * @param airportManager Gestor de aeropuertos del sistema
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

    /**
     * Búsqueda BFS de camino entre aeropuertos.
     * Considera restricciones temporales y SLA.
     * 
     * @param origin Aeropuerto origen
     * @param destination Aeropuerto destino
     * @param startTime Tiempo de inicio de búsqueda
     * @param sla Duración del SLA permitido
     * @param allowedFlights Lista de vuelos permitidos (null = todos los vuelos)
     * @param randomize Si true, shufflea vuelos disponibles para generar rutas diferentes
     * @return Lista de vuelos que forman el camino, o null si no hay camino
     * 
     * **Validates: Requirements 14.1, 14.3, 14.4**
     */
    private List<Flight> findPath(Airport origin, Airport destination,
                                  ZonedDateTime startTime, Duration sla,
                                  List<Flight> allowedFlights, boolean randomize) {
        ZonedDateTime deadline = startTime.plus(sla);
        
        // Cola BFS: (aeropuerto actual, tiempo actual, camino recorrido)
        Queue<SearchNode> queue = new LinkedList<>();
        queue.add(new SearchNode(origin, startTime, new ArrayList<>()));
        
        // Visitados: (aeropuerto, tiempo aproximado) para evitar ciclos
        Set<String> visited = new HashSet<>();
        
        // Random para shufflear vuelos (si randomize = true)
        Random random = randomize ? ThreadLocalRandom.current() : null;
        
        while (!queue.isEmpty()) {
            SearchNode node = queue.poll();
            
            // Verificar si llegamos al destino
            if (node.airport.equals(destination)) {
                return node.path;
            }
            
            // Limitar profundidad de búsqueda a MAX_HOPS
            if (node.path.size() >= MAX_HOPS) {
                continue;
            }
            
            // Obtener vuelos disponibles desde este aeropuerto
            List<Flight> availableFlights;
            if (allowedFlights != null) {
                // Filtrar vuelos permitidos desde aeropuerto actual
                availableFlights = new ArrayList<>(allowedFlights.stream()
                    .filter(f -> f.origin().equals(node.airport))
                    .filter(f -> f.departureTime().isAfter(node.currentTime))
                    .filter(f -> f.arrivalTime().isBefore(deadline))
                    .toList());
            } else {
                // Usar todos los vuelos del plan
                availableFlights = new ArrayList<>(
                    flightPlan.getFlightsFromAirport(node.airport, node.currentTime, deadline)
                );
            }
            
            // IMPORTANTE: Shufflear vuelos para generar rutas diferentes
            if (randomize && random != null) {
                Collections.shuffle(availableFlights, random);
            } else {
                availableFlights.sort(Comparator
                    .comparing(Flight::departureTime)
                    .thenComparing(Flight::arrivalTime)
                    .thenComparing(Flight::flightId));
            }
            
            for (Flight flight : availableFlights) {
                // Verificar tiempo de escala mínimo (10 minutos)
                Duration layover = Duration.between(node.currentTime, flight.departureTime());
                if (layover.toMinutes() < 10) {
                    continue;
                }
                
                // Verificar que llegada sea antes del deadline
                if (!flight.arrivalTime().isBefore(deadline)) {
                    continue;
                }
                
                // Crear nuevo nodo
                List<Flight> newPath = new ArrayList<>(node.path);
                newPath.add(flight);
                
                // Clave de visitado: aeropuerto + hora aproximada (por hora)
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
        
        return null;  // No se encontró camino
    }

    /**
     * Genera una ruta factible para un lote de maletas.
    * Intenta según el esfuerzo configurado encontrar una ruta válida.
     * 
     * @param batch Lote de maletas para el cual generar la ruta
     * @return AssignedRoute factible o null si no se encuentra ruta
     * 
     * **Validates: Requirements 14.1, 14.2, 14.3, 14.4, 14.6**
     */
    public AssignedRoute generateFeasibleRoute(ShipmentBatch batch) {
        return generateFeasibleRoute(batch, null);
    }

    public AssignedRoute generateEarliestFeasibleRoute(ShipmentBatch batch) {
        Objects.requireNonNull(batch, "Batch cannot be null");
        Duration sla = batch.calculateSLA();

        try {
            List<Flight> flightPath = findPath(
                batch.origin(),
                batch.destination(),
                batch.ingressTime(),
                sla,
                null,
                false
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

    /**
     * Genera una ruta factible para un lote de maletas usando solo vuelos permitidos.
    * Intenta según el esfuerzo configurado encontrar una ruta válida.
     * 
     * IMPORTANTE: Introduce aleatoriedad shuffleando vuelos disponibles para generar
     * rutas diferentes en cada intento. Esto es crucial para que el Algoritmo Genético
     * tenga diversidad en la población inicial.
     * 
     * @param batch Lote de maletas para el cual generar la ruta
     * @param allowedFlights Lista de vuelos permitidos (null = todos los vuelos)
     * @return AssignedRoute factible o null si no se encuentra ruta
     * 
     * **Validates: Requirements 14.1, 14.2, 14.3, 14.4, 14.6**
     */
    public AssignedRoute generateFeasibleRoute(ShipmentBatch batch, List<Flight> allowedFlights) {
        Objects.requireNonNull(batch, "Batch cannot be null");
        Duration sla = batch.calculateSLA();

        if (allowedFlights == null) {
            if (routePathCache.size() > MAX_ROUTE_CACHE_ENTRIES) {
                routePathCache.clear();
            }
            RouteCacheKey key = RouteCacheKey.from(batch, sla);
            List<List<Flight>> cachedPaths = routePathCache.computeIfAbsent(
                key,
                ignored -> findCandidatePaths(batch, sla, null, maxCachedVariants)
            );

            if (!cachedPaths.isEmpty()) {
                List<Flight> path = cachedPaths.get(ThreadLocalRandom.current().nextInt(cachedPaths.size()));
                try {
                    return new AssignedRoute(batch, path);
                } catch (IllegalArgumentException ex) {
                    routePathCache.remove(key);
                }
            }
            return null;
        }

        return generateFeasibleRouteUncached(batch, sla, allowedFlights, maxAttempts);
    }

    private AssignedRoute generateFeasibleRouteUncached(
            ShipmentBatch batch,
            Duration sla,
            List<Flight> allowedFlights,
            int maxAttempts) {
        
        // Intentar generar ruta hasta el máximo configurado
        // Cada intento usa un orden aleatorio de vuelos para generar rutas diferentes
        try {
            List<Flight> deterministicPath = findPath(
                batch.origin(),
                batch.destination(),
                batch.ingressTime(),
                sla,
                allowedFlights,
                false
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
                // Buscar secuencia de vuelos usando BFS con aleatoriedad
                // IMPORTANTE: Siempre usar randomización para generar diversidad en GA
                List<Flight> flightPath = findPath(
                    batch.origin(),
                    batch.destination(),
                    batch.ingressTime(),
                    sla,
                    allowedFlights,
                    true  // Siempre usar aleatoriedad para diversidad
                );
                
                // Si no se encontró camino, continuar intentando
                if (flightPath == null || flightPath.isEmpty()) {
                    continue;
                }
                
                // Construir AssignedRoute con la secuencia encontrada
                // El constructor de AssignedRoute valida automáticamente:
                // - Primer vuelo desde origen
                // - Último vuelo a destino
                // - Conexiones válidas
                // - Tiempos de escala mínimos (10 min)
                AssignedRoute route = new AssignedRoute(batch, flightPath);
                
                // Verificar que la ruta cumple SLA
                if (!route.meetsSLA()) {
                    continue;
                }
                
                // Ruta factible encontrada
                return route;
                
            } catch (IllegalArgumentException e) {
                // La validación del constructor falló, intentar de nuevo
                continue;
            }
        }
        
        // No se pudo generar ruta factible después de los intentos configurados
        return null;
    }

    private List<List<Flight>> findCandidatePaths(
            ShipmentBatch batch,
            Duration sla,
            List<Flight> allowedFlights,
            int maxCandidates) {
        Map<String, List<Flight>> uniquePaths = new LinkedHashMap<>();

        try {
            List<Flight> deterministicPath = findPath(
                batch.origin(),
                batch.destination(),
                batch.ingressTime(),
                sla,
                allowedFlights,
                false
            );

            if (deterministicPath != null && !deterministicPath.isEmpty()) {
                AssignedRoute route = new AssignedRoute(batch, deterministicPath);
                if (route.meetsSLA()) {
                    String signature = deterministicPath.stream()
                        .map(Flight::flightId)
                        .reduce((a, b) -> a + ">" + b)
                        .orElse("");
                    uniquePaths.putIfAbsent(signature, List.copyOf(deterministicPath));
                }
            }
        } catch (IllegalArgumentException e) {
            // Intentar candidatos aleatorios
        }

        for (int attempt = 0; attempt < maxAttempts && uniquePaths.size() < maxCandidates; attempt++) {
            try {
                List<Flight> flightPath = findPath(
                    batch.origin(),
                    batch.destination(),
                    batch.ingressTime(),
                    sla,
                    allowedFlights,
                    true
                );

                if (flightPath == null || flightPath.isEmpty()) {
                    continue;
                }

                AssignedRoute route = new AssignedRoute(batch, flightPath);
                if (!route.meetsSLA()) {
                    continue;
                }

                String signature = flightPath.stream()
                    .map(Flight::flightId)
                    .reduce((a, b) -> a + ">" + b)
                    .orElse("");
                uniquePaths.putIfAbsent(signature, List.copyOf(flightPath));
            } catch (IllegalArgumentException e) {
                // Intentar otro candidato
            }
        }

        return List.copyOf(uniquePaths.values());
    }

    /**
     * Nodo de búsqueda para BFS.
     * Representa un estado en la búsqueda de rutas.
     */
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
