package com.equipo2b.scheduler.monitoring;

import com.equipo2b.scheduler.model.*;

import java.util.*;

/**
 * Monitorea ocupación de vuelos y almacenes.
 * 
 * <p>Calcula métricas de capacidad del sistema para prevención de colapso
 * y detección de cuellos de botella.</p>
 * 
 * <p><b>Requisitos implementados:</b></p>
 * <ul>
 *   <li>35.1: Cálculo de ocupación promedio de vuelos</li>
 *   <li>35.2: Cálculo de ocupación promedio de almacenes</li>
 *   <li>35.5: Identificación de cuellos de botella</li>
 * </ul>
 * 
 * @see CollapseDetector
 * @see Bottleneck
 */
public class CapacityMonitor {
    private final FlightPlan flightPlan;
    private final AirportManager airportManager;
    
    /**
     * Constructor con FlightPlan y AirportManager.
     * 
     * @param flightPlan Plan de vuelos del sistema
     * @param airportManager Gestor de aeropuertos
     * @throws NullPointerException si algún parámetro es null
     */
    public CapacityMonitor(FlightPlan flightPlan, AirportManager airportManager) {
        if (flightPlan == null) {
            throw new NullPointerException("FlightPlan cannot be null");
        }
        if (airportManager == null) {
            throw new NullPointerException("AirportManager cannot be null");
        }
        this.flightPlan = flightPlan;
        this.airportManager = airportManager;
    }
    
    /**
     * Calcula ocupación promedio de vuelos en una solución.
     * 
     * <p>Proceso:</p>
     * <ol>
     *   <li>Acumular carga de maletas por vuelo</li>
     *   <li>Calcular ocupación de cada vuelo (carga / capacidad)</li>
     *   <li>Promediar ocupación de todos los vuelos</li>
     * </ol>
     * 
     * @param solution Solución actual del sistema
     * @return Ocupación promedio como decimal (0.0 = 0%, 1.0 = 100%)
     * 
     * **Validates: Requirements 35.1**
     */
    public double calculateAverageFlightOccupancy(Solution solution) {
        Map<Flight, Integer> flightLoads = new HashMap<>();
        
        // Acumular carga de maletas por vuelo
        for (AssignedRoute route : solution.getRoutes().values()) {
            int quantity = route.getBatch().quantity();
            for (Flight flight : route.getFlights()) {
                flightLoads.merge(flight, quantity, Integer::sum);
            }
        }
        
        if (flightLoads.isEmpty()) {
            return 0.0;
        }
        
        // Calcular ocupación promedio
        double totalOccupancy = 0.0;
        for (Map.Entry<Flight, Integer> entry : flightLoads.entrySet()) {
            double occupancy = (double) entry.getValue() / entry.getKey().capacity();
            totalOccupancy += occupancy;
        }
        
        return totalOccupancy / flightLoads.size();
    }
    
    /**
     * Calcula ocupación máxima de almacenes en una solución.
     * 
     * <p>Proceso:</p>
     * <ol>
     *   <li>Recopilar todos los eventos de almacenamiento</li>
     *   <li>Ordenar eventos por timestamp</li>
     *   <li>Simular ocupación a lo largo del tiempo</li>
     *   <li>Registrar ocupación máxima por aeropuerto</li>
     *   <li>Calcular ratio ocupación/capacidad</li>
     * </ol>
     * 
     * @param solution Solución actual del sistema
     * @return Mapa de Airport a ocupación máxima (0.0 = 0%, 1.0 = 100%)
     * 
     * **Validates: Requirements 35.2**
     */
    public Map<Airport, Double> calculateStorageOccupancy(Solution solution) {
        List<StorageEvent> allEvents = new ArrayList<>();
        
        // Recopilar todos los eventos de almacenamiento
        for (AssignedRoute route : solution.getRoutes().values()) {
            allEvents.addAll(route.getStorageEvents());
        }
        
        // Ordenar eventos por timestamp
        allEvents.sort(Comparator.comparing(StorageEvent::timestamp));
        
        // Simular ocupación a lo largo del tiempo
        Map<Airport, Integer> currentOccupancy = new HashMap<>();
        Map<Airport, Integer> maxOccupancy = new HashMap<>();
        
        for (StorageEvent event : allEvents) {
            Airport airport = event.airport();
            int delta = event.type() == StorageEventType.ARRIVAL ? 
                       event.quantity() : -event.quantity();
            
            int newOccupancy = currentOccupancy.getOrDefault(airport, 0) + delta;
            currentOccupancy.put(airport, newOccupancy);
            
            maxOccupancy.merge(airport, newOccupancy, Math::max);
        }
        
        // Calcular ratios de ocupación
        Map<Airport, Double> occupancyRatios = new HashMap<>();
        for (Map.Entry<Airport, Integer> entry : maxOccupancy.entrySet()) {
            Airport airport = entry.getKey();
            double ratio = (double) entry.getValue() / airport.storageCapacity();
            occupancyRatios.put(airport, ratio);
        }
        
        return occupancyRatios;
    }
    
    /**
     * Identifica cuellos de botella (ocupación > 70%).
     * 
     * <p>Detecta recursos con alta ocupación que pueden causar problemas:</p>
     * <ul>
     *   <li>Almacenes con ocupación > 70%</li>
     *   <li>Vuelos con ocupación > 70%</li>
     * </ul>
     * 
     * @param solution Solución actual del sistema
     * @return Lista de cuellos de botella detectados
     * 
     * **Validates: Requirements 35.5**
     */
    public List<Bottleneck> identifyBottlenecks(Solution solution) {
        List<Bottleneck> bottlenecks = new ArrayList<>();
        
        // Cuellos de botella en almacenes
        Map<Airport, Double> storageOccupancy = calculateStorageOccupancy(solution);
        for (Map.Entry<Airport, Double> entry : storageOccupancy.entrySet()) {
            if (entry.getValue() > 0.7) {
                bottlenecks.add(new Bottleneck(
                    BottleneckType.STORAGE,
                    entry.getKey().id(),
                    entry.getValue()
                ));
            }
        }
        
        // Cuellos de botella en vuelos
        Map<Flight, Integer> flightLoads = new HashMap<>();
        for (AssignedRoute route : solution.getRoutes().values()) {
            int quantity = route.getBatch().quantity();
            for (Flight flight : route.getFlights()) {
                flightLoads.merge(flight, quantity, Integer::sum);
            }
        }
        
        for (Map.Entry<Flight, Integer> entry : flightLoads.entrySet()) {
            double occupancy = (double) entry.getValue() / entry.getKey().capacity();
            if (occupancy > 0.7) {
                bottlenecks.add(new Bottleneck(
                    BottleneckType.FLIGHT,
                    entry.getKey().flightId(),
                    occupancy
                ));
            }
        }
        
        return bottlenecks;
    }
    
    /**
     * Obtiene el FlightPlan asociado.
     * 
     * @return FlightPlan del sistema
     */
    public FlightPlan getFlightPlan() {
        return flightPlan;
    }
    
    /**
     * Obtiene el AirportManager asociado.
     * 
     * @return AirportManager del sistema
     */
    public AirportManager getAirportManager() {
        return airportManager;
    }
    
    /**
     * Genera reporte completo de capacidades del sistema.
     * 
     * <p>El reporte incluye:</p>
     * <ul>
     *   <li>Ocupación promedio de vuelos</li>
     *   <li>Ocupación por aeropuerto (almacenes)</li>
     *   <li>Ocupación por ruta (vuelos individuales)</li>
     *   <li>Cuellos de botella identificados</li>
     *   <li>Recomendaciones de ajuste de capacidad</li>
     * </ul>
     * 
     * <p>Las recomendaciones se generan basándose en:</p>
     * <ul>
     *   <li>Aeropuertos con ocupación > 70%: aumentar capacidad de almacén</li>
     *   <li>Vuelos con ocupación > 70%: aumentar capacidad o frecuencia</li>
     *   <li>Ocupación promedio > 60%: riesgo de colapso sistémico</li>
     * </ul>
     * 
     * @param solution Solución actual del sistema
     * @return CapacityReport con análisis completo de capacidades
     * 
     * **Validates: Requirements 35.4, 35.6**
     */
    public CapacityReport generateCapacityReport(Solution solution) {
        // Calcular métricas de ocupación
        double avgFlightOccupancy = calculateAverageFlightOccupancy(solution);
        Map<Airport, Double> storageOccupancy = calculateStorageOccupancy(solution);
        Map<Flight, Double> flightOccupancy = calculateFlightOccupancy(solution);
        List<Bottleneck> bottlenecks = identifyBottlenecks(solution);
        
        // Generar recomendaciones
        List<String> recommendations = generateRecommendations(
            avgFlightOccupancy, 
            storageOccupancy, 
            flightOccupancy, 
            bottlenecks
        );
        
        return new CapacityReport(
            avgFlightOccupancy,
            storageOccupancy,
            flightOccupancy,
            bottlenecks,
            recommendations
        );
    }
    
    /**
     * Calcula ocupación individual de cada vuelo.
     * 
     * @param solution Solución actual del sistema
     * @return Mapa de Flight a ocupación (0.0 = 0%, 1.0 = 100%)
     */
    private Map<Flight, Double> calculateFlightOccupancy(Solution solution) {
        Map<Flight, Integer> flightLoads = new HashMap<>();
        
        // Acumular carga de maletas por vuelo
        for (AssignedRoute route : solution.getRoutes().values()) {
            int quantity = route.getBatch().quantity();
            for (Flight flight : route.getFlights()) {
                flightLoads.merge(flight, quantity, Integer::sum);
            }
        }
        
        // Calcular ocupación por vuelo
        Map<Flight, Double> occupancy = new HashMap<>();
        for (Map.Entry<Flight, Integer> entry : flightLoads.entrySet()) {
            double ratio = (double) entry.getValue() / entry.getKey().capacity();
            occupancy.put(entry.getKey(), ratio);
        }
        
        return occupancy;
    }
    
    /**
     * Genera recomendaciones de ajuste de capacidad basadas en métricas.
     * 
     * @param avgFlightOccupancy Ocupación promedio de vuelos
     * @param storageOccupancy Ocupación por aeropuerto
     * @param flightOccupancy Ocupación por vuelo
     * @param bottlenecks Cuellos de botella identificados
     * @return Lista de recomendaciones
     */
    private List<String> generateRecommendations(
        double avgFlightOccupancy,
        Map<Airport, Double> storageOccupancy,
        Map<Flight, Double> flightOccupancy,
        List<Bottleneck> bottlenecks
    ) {
        List<String> recommendations = new ArrayList<>();
        
        // Recomendación por ocupación promedio alta
        if (avgFlightOccupancy > 0.6) {
            recommendations.add(String.format(
                "CRITICAL: Average flight occupancy is %.1f%%. System approaching collapse. " +
                "Consider increasing overall flight capacity or frequency.",
                avgFlightOccupancy * 100
            ));
        } else if (avgFlightOccupancy > 0.5) {
            recommendations.add(String.format(
                "WARNING: Average flight occupancy is %.1f%%. Monitor system closely " +
                "and prepare capacity expansion plans.",
                avgFlightOccupancy * 100
            ));
        }
        
        // Recomendaciones por cuellos de botella en almacenes
        for (Bottleneck bottleneck : bottlenecks) {
            if (bottleneck.type() == BottleneckType.STORAGE) {
                Airport airport = airportManager.getAirport(bottleneck.resourceId());
                if (airport != null) {
                    if (bottleneck.isCritical()) {
                        recommendations.add(String.format(
                            "URGENT: Airport %s storage at %.1f%% capacity. " +
                            "Immediate expansion required (current: %d, recommend: %d).",
                            bottleneck.resourceId(),
                            bottleneck.occupancy() * 100,
                            airport.storageCapacity(),
                            (int) (airport.storageCapacity() * 1.5)
                        ));
                    } else {
                        recommendations.add(String.format(
                            "Airport %s storage at %.1f%% capacity. " +
                            "Consider expanding from %d to %d units.",
                            bottleneck.resourceId(),
                            bottleneck.occupancy() * 100,
                            airport.storageCapacity(),
                            (int) (airport.storageCapacity() * 1.3)
                        ));
                    }
                }
            }
        }
        
        // Recomendaciones por cuellos de botella en vuelos
        Map<String, List<Bottleneck>> flightBottlenecksByRoute = new HashMap<>();
        for (Bottleneck bottleneck : bottlenecks) {
            if (bottleneck.type() == BottleneckType.FLIGHT) {
                // Agrupar por ruta (origen-destino)
                String flightId = bottleneck.resourceId();
                Flight flight = findFlightById(flightId, flightOccupancy);
                if (flight != null) {
                    String route = flight.origin().id() + "-" + flight.destination().id();
                    flightBottlenecksByRoute
                        .computeIfAbsent(route, k -> new ArrayList<>())
                        .add(bottleneck);
                }
            }
        }
        
        // Generar recomendaciones por ruta
        for (Map.Entry<String, List<Bottleneck>> entry : flightBottlenecksByRoute.entrySet()) {
            String route = entry.getKey();
            List<Bottleneck> routeBottlenecks = entry.getValue();
            
            if (routeBottlenecks.size() >= 2) {
                recommendations.add(String.format(
                    "Route %s has %d flights at high capacity. " +
                    "Consider adding additional flights or increasing aircraft capacity.",
                    route,
                    routeBottlenecks.size()
                ));
            } else {
                Bottleneck bottleneck = routeBottlenecks.get(0);
                if (bottleneck.isCritical()) {
                    recommendations.add(String.format(
                        "Flight %s on route %s at %.1f%% capacity. " +
                        "Urgent: Add parallel flight or upgrade aircraft.",
                        bottleneck.resourceId(),
                        route,
                        bottleneck.occupancy() * 100
                    ));
                } else {
                    recommendations.add(String.format(
                        "Flight %s on route %s at %.1f%% capacity. " +
                        "Consider capacity increase for this route.",
                        bottleneck.resourceId(),
                        route,
                        bottleneck.occupancy() * 100
                    ));
                }
            }
        }
        
        // Si no hay problemas, indicar estado saludable
        if (recommendations.isEmpty()) {
            recommendations.add(
                "System capacity is healthy. No immediate adjustments required."
            );
        }
        
        return recommendations;
    }
    
    /**
     * Busca un vuelo por ID en el mapa de ocupación.
     * 
     * @param flightId ID del vuelo a buscar
     * @param flightOccupancy Mapa de ocupación de vuelos
     * @return Flight encontrado o null si no existe
     */
    private Flight findFlightById(String flightId, Map<Flight, Double> flightOccupancy) {
        for (Flight flight : flightOccupancy.keySet()) {
            if (flight.flightId().equals(flightId)) {
                return flight;
            }
        }
        return null;
    }
}
