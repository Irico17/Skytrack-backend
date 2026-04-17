package com.equipo2b.scheduler.monitoring;

import com.equipo2b.scheduler.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests para CapacityMonitor.
 * 
 * Valida:
 * - Cálculo de ocupación promedio de vuelos
 * - Cálculo de ocupación de almacenes
 * - Identificación de cuellos de botella
 */
class CapacityMonitorTest {
    
    private FlightPlan flightPlan;
    private AirportManager airportManager;
    private CapacityMonitor monitor;
    
    private Airport jfk;
    private Airport lhr;
    private Airport cdg;
    
    @BeforeEach
    void setUp() {
        // Crear aeropuertos
        jfk = new Airport("JFK", "New York", "USA", 
                         ZoneId.of("America/New_York"), 600, 
                         40.6413, -73.7781, Continent.AMERICA);
        lhr = new Airport("LHR", "London", "UK", 
                         ZoneId.of("Europe/London"), 700, 
                         51.4700, -0.4543, Continent.EUROPE);
        cdg = new Airport("CDG", "Paris", "France", 
                         ZoneId.of("Europe/Paris"), 650, 
                         49.0097, 2.5479, Continent.EUROPE);
        
        List<Airport> airports = List.of(jfk, lhr, cdg);
        airportManager = new AirportManager(airports);
        
        // Crear plan de vuelos
        flightPlan = new FlightPlan();
        
        // Inicializar monitor
        monitor = new CapacityMonitor(flightPlan, airportManager);
    }
    
    @Test
    void testConstructorWithNullFlightPlan() {
        assertThrows(NullPointerException.class, 
            () -> new CapacityMonitor(null, airportManager));
    }
    
    @Test
    void testConstructorWithNullAirportManager() {
        assertThrows(NullPointerException.class, 
            () -> new CapacityMonitor(flightPlan, null));
    }
    
    @Test
    void testCalculateAverageFlightOccupancyEmptySolution() {
        Solution solution = new Solution();
        double occupancy = monitor.calculateAverageFlightOccupancy(solution);
        assertEquals(0.0, occupancy, 0.001);
    }
    
    @Test
    void testCalculateAverageFlightOccupancyWithRoutes() {
        // Crear vuelo con capacidad 200
        ZonedDateTime departure = ZonedDateTime.of(2026, 1, 1, 10, 0, 0, 0, jfk.zoneId());
        ZonedDateTime arrival = departure.plusHours(24);
        Flight flight = new Flight("FL001", jfk, lhr, departure, arrival, 200, FlightType.INTERCONTINENTAL);
        
        // Crear lote con 100 maletas (50% de ocupación)
        ShipmentBatch batch = new ShipmentBatch(
            "B001", "AB001", "CLIENT1", jfk, lhr, 100, departure.minusHours(1)
        );
        
        // Crear ruta asignada
        List<Flight> flights = List.of(flight);
        AssignedRoute route = new AssignedRoute(batch, flights);
        
        // Crear solución
        Solution solution = new Solution();
        solution.addRoute(route);
        
        // Calcular ocupación
        double occupancy = monitor.calculateAverageFlightOccupancy(solution);
        assertEquals(0.5, occupancy, 0.001); // 100/200 = 0.5
    }
    
    @Test
    void testCalculateStorageOccupancyEmptySolution() {
        Solution solution = new Solution();
        Map<Airport, Double> occupancy = monitor.calculateStorageOccupancy(solution);
        assertTrue(occupancy.isEmpty());
    }
    
    @Test
    void testCalculateStorageOccupancyWithRoutes() {
        // Crear vuelo
        ZonedDateTime departure = ZonedDateTime.of(2026, 1, 1, 10, 0, 0, 0, jfk.zoneId());
        ZonedDateTime arrival = departure.plusHours(24);
        Flight flight = new Flight("FL001", jfk, lhr, departure, arrival, 200, FlightType.INTERCONTINENTAL);
        
        // Crear lote con 300 maletas
        ShipmentBatch batch = new ShipmentBatch(
            "B001", "AB001", "CLIENT1", jfk, lhr, 300, departure.minusHours(1)
        );
        
        // Crear ruta asignada
        List<Flight> flights = List.of(flight);
        AssignedRoute route = new AssignedRoute(batch, flights);
        
        // Crear solución
        Solution solution = new Solution();
        solution.addRoute(route);
        
        // Calcular ocupación de almacenes
        Map<Airport, Double> occupancy = monitor.calculateStorageOccupancy(solution);
        
        // LHR debería tener ocupación de 300/700 = 0.428
        assertTrue(occupancy.containsKey(lhr));
        assertEquals(0.428, occupancy.get(lhr), 0.01);
    }
    
    @Test
    void testIdentifyBottlenecksNoBottlenecks() {
        // Crear vuelo con baja ocupación
        ZonedDateTime departure = ZonedDateTime.of(2026, 1, 1, 10, 0, 0, 0, jfk.zoneId());
        ZonedDateTime arrival = departure.plusHours(24);
        Flight flight = new Flight("FL001", jfk, lhr, departure, arrival, 200, FlightType.INTERCONTINENTAL);
        
        // Crear lote con 50 maletas (25% de ocupación)
        ShipmentBatch batch = new ShipmentBatch(
            "B001", "AB001", "CLIENT1", jfk, lhr, 50, departure.minusHours(1)
        );
        
        // Crear ruta asignada
        List<Flight> flights = List.of(flight);
        AssignedRoute route = new AssignedRoute(batch, flights);
        
        // Crear solución
        Solution solution = new Solution();
        solution.addRoute(route);
        
        // Identificar cuellos de botella
        List<Bottleneck> bottlenecks = monitor.identifyBottlenecks(solution);
        assertTrue(bottlenecks.isEmpty());
    }
    
    @Test
    void testIdentifyBottlenecksWithFlightBottleneck() {
        // Crear vuelo con alta ocupación
        ZonedDateTime departure = ZonedDateTime.of(2026, 1, 1, 10, 0, 0, 0, jfk.zoneId());
        ZonedDateTime arrival = departure.plusHours(24);
        Flight flight = new Flight("FL001", jfk, lhr, departure, arrival, 200, FlightType.INTERCONTINENTAL);
        
        // Crear lote con 150 maletas (75% de ocupación - cuello de botella)
        ShipmentBatch batch = new ShipmentBatch(
            "B001", "AB001", "CLIENT1", jfk, lhr, 150, departure.minusHours(1)
        );
        
        // Crear ruta asignada
        List<Flight> flights = List.of(flight);
        AssignedRoute route = new AssignedRoute(batch, flights);
        
        // Crear solución
        Solution solution = new Solution();
        solution.addRoute(route);
        
        // Identificar cuellos de botella
        List<Bottleneck> bottlenecks = monitor.identifyBottlenecks(solution);
        
        // Debería haber un cuello de botella de tipo FLIGHT
        assertEquals(1, bottlenecks.size());
        assertEquals(BottleneckType.FLIGHT, bottlenecks.get(0).type());
        assertEquals("FL001", bottlenecks.get(0).resourceId());
        assertEquals(0.75, bottlenecks.get(0).occupancy(), 0.001);
    }
    
    @Test
    void testIdentifyBottlenecksWithStorageBottleneck() {
        // Crear vuelo
        ZonedDateTime departure = ZonedDateTime.of(2026, 1, 1, 10, 0, 0, 0, jfk.zoneId());
        ZonedDateTime arrival = departure.plusHours(24);
        Flight flight = new Flight("FL001", jfk, lhr, departure, arrival, 400, FlightType.INTERCONTINENTAL);
        
        // Crear lote con 500 maletas (71% de ocupación en LHR - cuello de botella)
        ShipmentBatch batch = new ShipmentBatch(
            "B001", "AB001", "CLIENT1", jfk, lhr, 500, departure.minusHours(1)
        );
        
        // Crear ruta asignada
        List<Flight> flights = List.of(flight);
        AssignedRoute route = new AssignedRoute(batch, flights);
        
        // Crear solución
        Solution solution = new Solution();
        solution.addRoute(route);
        
        // Identificar cuellos de botella
        List<Bottleneck> bottlenecks = monitor.identifyBottlenecks(solution);
        
        // Debería haber un cuello de botella de tipo STORAGE
        boolean hasStorageBottleneck = bottlenecks.stream()
            .anyMatch(b -> b.type() == BottleneckType.STORAGE && b.resourceId().equals("LHR"));
        assertTrue(hasStorageBottleneck);
    }
    
    @Test
    void testGetFlightPlan() {
        assertEquals(flightPlan, monitor.getFlightPlan());
    }
    
    @Test
    void testGetAirportManager() {
        assertEquals(airportManager, monitor.getAirportManager());
    }
    
    @Test
    void testGenerateCapacityReportEmptySolution() {
        Solution solution = new Solution();
        CapacityReport report = monitor.generateCapacityReport(solution);
        
        assertNotNull(report);
        assertEquals(0.0, report.getAverageFlightOccupancy(), 0.001);
        assertTrue(report.getStorageOccupancy().isEmpty());
        assertTrue(report.getFlightOccupancy().isEmpty());
        assertTrue(report.getBottlenecks().isEmpty());
        assertFalse(report.getRecommendations().isEmpty()); // Should have "healthy" message
    }
    
    @Test
    void testGenerateCapacityReportWithHealthySystem() {
        // Crear vuelo con baja ocupación
        ZonedDateTime departure = ZonedDateTime.of(2026, 1, 1, 10, 0, 0, 0, jfk.zoneId());
        ZonedDateTime arrival = departure.plusHours(24);
        Flight flight = new Flight("FL001", jfk, lhr, departure, arrival, 200, FlightType.INTERCONTINENTAL);
        
        // Crear lote con 50 maletas (25% de ocupación)
        ShipmentBatch batch = new ShipmentBatch(
            "B001", "AB001", "CLIENT1", jfk, lhr, 50, departure.minusHours(1)
        );
        
        // Crear ruta asignada
        List<Flight> flights = List.of(flight);
        AssignedRoute route = new AssignedRoute(batch, flights);
        
        // Crear solución
        Solution solution = new Solution();
        solution.addRoute(route);
        
        // Generar reporte
        CapacityReport report = monitor.generateCapacityReport(solution);
        
        assertNotNull(report);
        assertEquals(0.25, report.getAverageFlightOccupancy(), 0.001);
        assertFalse(report.getStorageOccupancy().isEmpty());
        assertFalse(report.getFlightOccupancy().isEmpty());
        assertTrue(report.getBottlenecks().isEmpty());
        
        // Debería indicar sistema saludable
        boolean hasHealthyMessage = report.getRecommendations().stream()
            .anyMatch(rec -> rec.contains("healthy"));
        assertTrue(hasHealthyMessage);
    }
    
    @Test
    void testGenerateCapacityReportWithBottlenecks() {
        // Crear vuelo con alta ocupación
        ZonedDateTime departure = ZonedDateTime.of(2026, 1, 1, 10, 0, 0, 0, jfk.zoneId());
        ZonedDateTime arrival = departure.plusHours(24);
        Flight flight = new Flight("FL001", jfk, lhr, departure, arrival, 200, FlightType.INTERCONTINENTAL);
        
        // Crear lote con 150 maletas (75% de ocupación - cuello de botella)
        ShipmentBatch batch = new ShipmentBatch(
            "B001", "AB001", "CLIENT1", jfk, lhr, 150, departure.minusHours(1)
        );
        
        // Crear ruta asignada
        List<Flight> flights = List.of(flight);
        AssignedRoute route = new AssignedRoute(batch, flights);
        
        // Crear solución
        Solution solution = new Solution();
        solution.addRoute(route);
        
        // Generar reporte
        CapacityReport report = monitor.generateCapacityReport(solution);
        
        assertNotNull(report);
        assertEquals(0.75, report.getAverageFlightOccupancy(), 0.001);
        assertFalse(report.getBottlenecks().isEmpty());
        
        // Debería tener recomendaciones de ajuste
        assertFalse(report.getRecommendations().isEmpty());
        boolean hasCapacityRecommendation = report.getRecommendations().stream()
            .anyMatch(rec -> rec.contains("capacity") || rec.contains("Flight"));
        assertTrue(hasCapacityRecommendation);
    }
    
    @Test
    void testGenerateCapacityReportWithStorageBottleneck() {
        // Crear vuelo
        ZonedDateTime departure = ZonedDateTime.of(2026, 1, 1, 10, 0, 0, 0, jfk.zoneId());
        ZonedDateTime arrival = departure.plusHours(24);
        Flight flight = new Flight("FL001", jfk, lhr, departure, arrival, 400, FlightType.INTERCONTINENTAL);
        
        // Crear lote con 500 maletas (71% de ocupación en LHR - cuello de botella)
        ShipmentBatch batch = new ShipmentBatch(
            "B001", "AB001", "CLIENT1", jfk, lhr, 500, departure.minusHours(1)
        );
        
        // Crear ruta asignada
        List<Flight> flights = List.of(flight);
        AssignedRoute route = new AssignedRoute(batch, flights);
        
        // Crear solución
        Solution solution = new Solution();
        solution.addRoute(route);
        
        // Generar reporte
        CapacityReport report = monitor.generateCapacityReport(solution);
        
        assertNotNull(report);
        
        // Debería tener cuello de botella de almacén
        boolean hasStorageBottleneck = report.getBottlenecks().stream()
            .anyMatch(b -> b.type() == BottleneckType.STORAGE);
        assertTrue(hasStorageBottleneck);
        
        // Debería tener recomendación de expansión de almacén
        boolean hasStorageRecommendation = report.getRecommendations().stream()
            .anyMatch(rec -> rec.contains("storage") || rec.contains("Airport"));
        assertTrue(hasStorageRecommendation);
    }
    
    @Test
    void testGenerateCapacityReportWithHighAverageOccupancy() {
        // Crear múltiples vuelos con alta ocupación
        List<Flight> flights = new ArrayList<>();
        List<ShipmentBatch> batches = new ArrayList<>();
        
        for (int i = 0; i < 5; i++) {
            ZonedDateTime departure = ZonedDateTime.of(2026, 1, 1, 10 + i, 0, 0, 0, jfk.zoneId());
            ZonedDateTime arrival = departure.plusHours(24);
            Flight flight = new Flight("FL00" + i, jfk, lhr, departure, arrival, 200, FlightType.INTERCONTINENTAL);
            flights.add(flight);
            
            // 65% de ocupación promedio
            ShipmentBatch batch = new ShipmentBatch(
                "B00" + i, "AB00" + i, "CLIENT1", jfk, lhr, 130, departure.minusHours(1)
            );
            batches.add(batch);
        }
        
        // Crear solución
        Solution solution = new Solution();
        for (int i = 0; i < flights.size(); i++) {
            AssignedRoute route = new AssignedRoute(batches.get(i), List.of(flights.get(i)));
            solution.addRoute(route);
        }
        
        // Generar reporte
        CapacityReport report = monitor.generateCapacityReport(solution);
        
        assertNotNull(report);
        assertEquals(0.65, report.getAverageFlightOccupancy(), 0.01);
        
        // Debería tener advertencia de ocupación alta
        boolean hasHighOccupancyWarning = report.getRecommendations().stream()
            .anyMatch(rec -> rec.contains("CRITICAL") || rec.contains("collapse"));
        assertTrue(hasHighOccupancyWarning);
    }
    
    @Test
    void testCapacityReportToString() {
        // Crear vuelo con ocupación moderada
        ZonedDateTime departure = ZonedDateTime.of(2026, 1, 1, 10, 0, 0, 0, jfk.zoneId());
        ZonedDateTime arrival = departure.plusHours(24);
        Flight flight = new Flight("FL001", jfk, lhr, departure, arrival, 200, FlightType.INTERCONTINENTAL);
        
        ShipmentBatch batch = new ShipmentBatch(
            "B001", "AB001", "CLIENT1", jfk, lhr, 100, departure.minusHours(1)
        );
        
        AssignedRoute route = new AssignedRoute(batch, List.of(flight));
        Solution solution = new Solution();
        solution.addRoute(route);
        
        // Generar reporte
        CapacityReport report = monitor.generateCapacityReport(solution);
        String reportString = report.toString();
        
        // Verificar que contiene secciones esperadas
        assertTrue(reportString.contains("CAPACITY REPORT"));
        assertTrue(reportString.contains("Average Flight Occupancy"));
        assertTrue(reportString.contains("Storage Occupancy by Airport"));
        assertTrue(reportString.contains("Flight Occupancy"));
        assertTrue(reportString.contains("Bottlenecks Identified"));
        assertTrue(reportString.contains("Capacity Adjustment Recommendations"));
    }
}
