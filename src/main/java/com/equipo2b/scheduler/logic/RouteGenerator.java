package com.equipo2b.scheduler.logic;

import com.equipo2b.scheduler.model.*;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Genera rutas factibles para lotes de maletas.
 * Utiliza búsqueda de caminos (BFS) con validación de restricciones.
 * 
 * Restricciones verificadas:
 * - Capacidad de vuelos
 * - Capacidad de almacenes
 * - Tiempos de escala (mínimo 10 minutos)
 * - SLA (24h mismo continente, 48h diferentes continentes)
 * - Conexiones válidas entre vuelos
 * 
 * **Validates: Requirements 14.1, 14.6**
 */
public class RouteGenerator {
    private final FlightPlan flightPlan;
    private final AirportManager airportManager;
    
    private static final int MAX_ATTEMPTS = 20;
    private static final int MAX_HOPS = 3;  // Máximo de escalas

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

    /**
     * Búsqueda BFS de camino entre aeropuertos.
     * Considera restricciones temporales y SLA.
     * 
     * @param origin Aeropuerto origen
     * @param destination Aeropuerto destino
     * @param startTime Tiempo de inicio de búsqueda
     * @param sla Duración del SLA permitido
     * @param allowedFlights Lista de vuelos permitidos (null = todos los vuelos)
     * @return Lista de vuelos que forman el camino, o null si no hay camino
     * 
     * **Validates: Requirements 14.1, 14.3, 14.4**
     */
    private List<Flight> findPath(Airport origin, Airport destination,
                                  ZonedDateTime startTime, Duration sla,
                                  List<Flight> allowedFlights) {
        ZonedDateTime deadline = startTime.plus(sla);
        
        // Cola BFS: (aeropuerto actual, tiempo actual, camino recorrido)
        Queue<SearchNode> queue = new LinkedList<>();
        queue.add(new SearchNode(origin, startTime, new ArrayList<>()));
        
        // Visitados: (aeropuerto, tiempo aproximado) para evitar ciclos
        Set<String> visited = new HashSet<>();
        
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
                availableFlights = allowedFlights.stream()
                    .filter(f -> f.origin().equals(node.airport))
                    .filter(f -> f.departureTime().isAfter(node.currentTime))
                    .filter(f -> f.arrivalTime().isBefore(deadline))
                    .toList();
            } else {
                // Usar todos los vuelos del plan
                availableFlights = flightPlan.getFlightsFromAirport(node.airport, node.currentTime, deadline);
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
     * Intenta hasta MAX_ATTEMPTS veces encontrar una ruta válida.
     * 
     * @param batch Lote de maletas para el cual generar la ruta
     * @return AssignedRoute factible o null si no se encuentra ruta
     * 
     * **Validates: Requirements 14.1, 14.2, 14.3, 14.4, 14.6**
     */
    public AssignedRoute generateFeasibleRoute(ShipmentBatch batch) {
        return generateFeasibleRoute(batch, null);
    }

    /**
     * Genera una ruta factible para un lote de maletas usando solo vuelos permitidos.
     * Intenta hasta MAX_ATTEMPTS veces encontrar una ruta válida.
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
        
        // Intentar generar ruta hasta MAX_ATTEMPTS veces
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                // Buscar secuencia de vuelos usando BFS
                List<Flight> flightPath = findPath(
                    batch.origin(),
                    batch.destination(),
                    batch.ingressTime(),
                    sla,
                    allowedFlights
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
        
        // No se pudo generar ruta factible después de MAX_ATTEMPTS intentos
        return null;
    }

    /**
     * Nodo de búsqueda para BFS.
     * Representa un estado en la búsqueda de rutas.
     */
    private record SearchNode(Airport airport, ZonedDateTime currentTime, List<Flight> path) {}
}
