package com.equipo2b.scheduler.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests para la clase Solution (cromosoma).
 * 
 * **Validates: Requirements 8.1, 8.2, 8.3, 8.4, 8.5**
 */
class SolutionTest {
    
    private Airport jfk;
    private Airport cdg;
    private Airport nrt;
    private ShipmentBatch batch1;
    private ShipmentBatch batch2;
    private Flight flight1;
    private Flight flight2;
    private AssignedRoute route1;
    private AssignedRoute route2;
    
    @BeforeEach
    void setUp() {
        // Crear aeropuertos de prueba
        jfk = new Airport(
            "JFK",
            "New York",
            "USA",
            ZoneId.of("America/New_York"),
            600,
            40.6413,
            -73.7781,
            Continent.AMERICA
        );
        
        cdg = new Airport(
            "CDG",
            "Paris",
            "France",
            ZoneId.of("Europe/Paris"),
            700,
            49.0097,
            2.5479,
            Continent.EUROPE
        );
        
        nrt = new Airport(
            "NRT",
            "Tokyo",
            "Japan",
            ZoneId.of("Asia/Tokyo"),
            650,
            35.7720,
            140.3929,
            Continent.ASIA
        );
        
        // Crear lotes de prueba
        batch1 = new ShipmentBatch(
            "BATCH001",
            "JFK-001",
            "CLIENT-A",
            jfk,
            cdg,
            50,
            ZonedDateTime.now(jfk.zoneId())
        );
        
        batch2 = new ShipmentBatch(
            "BATCH002",
            "JFK-002",
            "CLIENT-B",
            jfk,
            nrt,
            75,
            ZonedDateTime.now(jfk.zoneId())
        );
        
        // Crear vuelos de prueba
        ZonedDateTime departure1 = ZonedDateTime.now(jfk.zoneId()).plusHours(2);
        flight1 = new Flight(
            "FL001",
            jfk,
            cdg,
            departure1,
            departure1.plusHours(24),
            300,
            FlightType.INTERCONTINENTAL
        );
        
        ZonedDateTime departure2 = ZonedDateTime.now(jfk.zoneId()).plusHours(3);
        flight2 = new Flight(
            "FL002",
            jfk,
            nrt,
            departure2,
            departure2.plusHours(24),
            350,
            FlightType.INTERCONTINENTAL
        );
        
        // Crear rutas asignadas de prueba
        route1 = new AssignedRoute(batch1, List.of(flight1));
        route2 = new AssignedRoute(batch2, List.of(flight2));
    }
    
    @Test
    void testEmptyConstructor() {
        Solution solution = new Solution();
        
        assertNotNull(solution);
        assertNotNull(solution.getRoutes());
        assertTrue(solution.getRoutes().isEmpty());
        assertEquals(Double.MAX_VALUE, solution.getFitness());
        assertFalse(solution.isEvaluated());
    }
    
    @Test
    void testAddRoute() {
        Solution solution = new Solution();
        
        solution.addRoute(route1);
        
        assertEquals(1, solution.getRoutes().size());
        assertSame(route1, solution.getRoute("BATCH001"));
        assertFalse(solution.isEvaluated()); // Adding route invalidates evaluation
    }
    
    @Test
    void testAddMultipleRoutes() {
        Solution solution = new Solution();
        
        solution.addRoute(route1);
        solution.addRoute(route2);
        
        assertEquals(2, solution.getRoutes().size());
        assertSame(route1, solution.getRoute("BATCH001"));
        assertSame(route2, solution.getRoute("BATCH002"));
    }
    
    @Test
    void testGetRoute() {
        Solution solution = new Solution();
        solution.addRoute(route1);
        
        AssignedRoute retrieved = solution.getRoute("BATCH001");
        
        assertNotNull(retrieved);
        assertSame(route1, retrieved);
        assertEquals("BATCH001", retrieved.getBatch().batchId());
    }
    
    @Test
    void testGetRouteNonExistent() {
        Solution solution = new Solution();
        
        AssignedRoute retrieved = solution.getRoute("NONEXISTENT");
        
        assertNull(retrieved);
    }
    
    @Test
    void testGetRoutesReturnsUnmodifiableMap() {
        Solution solution = new Solution();
        solution.addRoute(route1);
        
        Map<String, AssignedRoute> routes = solution.getRoutes();
        
        assertThrows(UnsupportedOperationException.class, () -> {
            routes.put("BATCH999", route2);
        });
    }
    
    @Test
    void testFitnessGetterSetter() {
        Solution solution = new Solution();
        
        assertEquals(Double.MAX_VALUE, solution.getFitness());
        assertFalse(solution.isEvaluated());
        
        solution.setFitness(1234.56);
        
        assertEquals(1234.56, solution.getFitness());
        assertTrue(solution.isEvaluated());
    }
    
    @Test
    void testAddRouteInvalidatesEvaluation() {
        Solution solution = new Solution();
        solution.setFitness(100.0);
        
        assertTrue(solution.isEvaluated());
        
        solution.addRoute(route1);
        
        assertFalse(solution.isEvaluated());
        assertEquals(100.0, solution.getFitness()); // Fitness value preserved but marked as invalid
    }
    
    @Test
    void testDeepCopyConstructor() {
        Solution original = new Solution();
        original.addRoute(route1);
        original.addRoute(route2);
        original.setFitness(500.0);
        
        Solution copy = new Solution(original);
        
        // Verify copy has same data
        assertEquals(original.getRoutes().size(), copy.getRoutes().size());
        assertEquals(original.getFitness(), copy.getFitness());
        assertEquals(original.isEvaluated(), copy.isEvaluated());
        
        // Verify deep copy - routes map is different instance
        assertNotSame(original.getRoutes(), copy.getRoutes());
        
        // Verify routes are copied (different instances)
        AssignedRoute originalRoute1 = original.getRoute("BATCH001");
        AssignedRoute copiedRoute1 = copy.getRoute("BATCH001");
        assertNotNull(originalRoute1);
        assertNotNull(copiedRoute1);
        assertNotSame(originalRoute1, copiedRoute1);
        
        // Verify route data is the same
        assertEquals(originalRoute1.getBatch().batchId(), copiedRoute1.getBatch().batchId());
    }
    
    @Test
    void testDeepCopyIndependence() {
        Solution original = new Solution();
        original.addRoute(route1);
        original.setFitness(500.0);
        
        Solution copy = new Solution(original);
        
        // Modify copy
        copy.addRoute(route2);
        copy.setFitness(600.0);
        
        // Verify original is unchanged
        assertEquals(1, original.getRoutes().size());
        assertEquals(500.0, original.getFitness());
        
        // Verify copy has changes
        assertEquals(2, copy.getRoutes().size());
        assertEquals(600.0, copy.getFitness());
    }
    
    @Test
    void testGetUsedFlights() {
        Solution solution = new Solution();
        solution.addRoute(route1);
        solution.addRoute(route2);
        
        Set<Flight> usedFlights = solution.getUsedFlights();
        
        assertNotNull(usedFlights);
        assertEquals(2, usedFlights.size());
        assertTrue(usedFlights.contains(flight1));
        assertTrue(usedFlights.contains(flight2));
    }
    
    @Test
    void testGetUsedFlightsEmpty() {
        Solution solution = new Solution();
        
        Set<Flight> usedFlights = solution.getUsedFlights();
        
        assertNotNull(usedFlights);
        assertTrue(usedFlights.isEmpty());
    }
    
    @Test
    void testGetTotalBags() {
        Solution solution = new Solution();
        solution.addRoute(route1); // 50 bags
        solution.addRoute(route2); // 75 bags
        
        int totalBags = solution.getTotalBags();
        
        assertEquals(125, totalBags);
    }
    
    @Test
    void testGetTotalBagsEmpty() {
        Solution solution = new Solution();
        
        int totalBags = solution.getTotalBags();
        
        assertEquals(0, totalBags);
    }
    
    @Test
    void testReplaceRoute() {
        Solution solution = new Solution();
        solution.addRoute(route1);
        
        // Create a new route for the same batch
        ZonedDateTime newDeparture = ZonedDateTime.now(jfk.zoneId()).plusHours(5);
        Flight newFlight = new Flight(
            "FL003",
            jfk,
            cdg,
            newDeparture,
            newDeparture.plusHours(24),
            300,
            FlightType.INTERCONTINENTAL
        );
        AssignedRoute newRoute = new AssignedRoute(batch1, List.of(newFlight));
        
        solution.addRoute(newRoute);
        
        // Verify route was replaced
        assertEquals(1, solution.getRoutes().size());
        AssignedRoute retrieved = solution.getRoute("BATCH001");
        assertSame(newRoute, retrieved);
        assertEquals("FL003", retrieved.getFlights().get(0).flightId());
    }
    
    @Test
    void testAccessTimeComplexity() {
        // This test verifies O(1) access by using the map structure
        Solution solution = new Solution();
        
        // Add many routes
        for (int i = 0; i < 1000; i++) {
            ShipmentBatch batch = new ShipmentBatch(
                "BATCH" + i,
                "JFK-" + i,
                "CLIENT-" + i,
                jfk,
                cdg,
                10,
                ZonedDateTime.now(jfk.zoneId())
            );
            
            ZonedDateTime departure = ZonedDateTime.now(jfk.zoneId()).plusHours(i);
            Flight flight = new Flight(
                "FL" + i,
                jfk,
                cdg,
                departure,
                departure.plusHours(24),
                300,
                FlightType.INTERCONTINENTAL
            );
            
            AssignedRoute route = new AssignedRoute(batch, List.of(flight));
            solution.addRoute(route);
        }
        
        // Access should be O(1) regardless of size
        long startTime = System.nanoTime();
        AssignedRoute route = solution.getRoute("BATCH500");
        long endTime = System.nanoTime();
        
        assertNotNull(route);
        assertEquals("BATCH500", route.getBatch().batchId());
        
        // Access time should be very fast (< 1ms even for 1000 elements)
        long accessTime = endTime - startTime;
        assertTrue(accessTime < 1_000_000, "Access time should be O(1): " + accessTime + " ns");
    }
}
