package com.equipo2b.scheduler.logic;

import com.equipo2b.scheduler.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests específicos para el método calculateStorageCapacityPenalties.
 * 
 * Verifica que:
 * - Se recopilan todos los StorageEvent de todas las rutas
 * - Se ordenan eventos por timestamp
 * - Se simula ocupación de cada aeropuerto a lo largo del tiempo
 * - Se calcula exceso en cada instante: max(0, ocupación - capacidad)
 * - Se aplica penalización: exceso × PENALTY_STORAGE_CAPACITY
 * 
 * <strong>Validates: Requirements 3.2, 3.3, 9.3</strong>
 */
class StorageCapacityPenaltiesTest {
    
    private FlightPlan flightPlan;
    private AirportManager airportManager;
    private SolutionEvaluator evaluator;
    
    private Airport jfk;
    private Airport cdg;
    private Airport nrt;
    
    @BeforeEach
    void setUp() {
        flightPlan = new FlightPlan();
        airportManager = new AirportManager();
        
        // Create test airports with storage capacity of 600
        jfk = new Airport("JFK", "New York", "USA", ZoneId.of("America/New_York"), 
                         600, 40.6413, -73.7781, Continent.AMERICA);
        cdg = new Airport("CDG", "Paris", "France", ZoneId.of("Europe/Paris"), 
                         600, 49.0097, 2.5479, Continent.EUROPE);
        nrt = new Airport("NRT", "Tokyo", "Japan", ZoneId.of("Asia/Tokyo"), 
                         600, 35.7720, 140.3929, Continent.ASIA);
        
        airportManager.addAirport(jfk);
        airportManager.addAirport(cdg);
        airportManager.addAirport(nrt);
        
        evaluator = new SolutionEvaluator(flightPlan, airportManager);
    }
    
    @Test
    @DisplayName("calculateStorageCapacityPenalties returns zero when no storage exceeds capacity")
    void testNoStorageExcess() {
        // Create a solution with routes that don't exceed storage capacity
        Solution solution = new Solution();
        
        // Create a batch of 100 bags (well below capacity of 600)
        ShipmentBatch batch = createBatch("B1", jfk, cdg, 100);
        
        // Create flights
        ZonedDateTime dep1 = ZonedDateTime.now(jfk.zoneId());
        ZonedDateTime arr1 = dep1.plusHours(24);
        Flight flight1 = new Flight("FL1", jfk, cdg, dep1, arr1, 200, FlightType.INTERCONTINENTAL);
        
        // Create route
        AssignedRoute route = new AssignedRoute(batch, List.of(flight1));
        solution.addRoute(route);
        
        // Calculate penalty
        double penalty = evaluator.calculateStorageCapacityPenalties(solution);
        
        assertEquals(0.0, penalty, 0.01, 
                    "No penalty when storage doesn't exceed capacity");
    }
    
    @Test
    @DisplayName("calculateStorageCapacityPenalties calculates penalty when storage exceeds capacity")
    void testStorageExcess() {
        Solution solution = new Solution();
        
        // Create a batch of 700 bags (exceeds capacity of 600 by 100)
        ShipmentBatch batch = createBatch("B1", jfk, cdg, 700);
        
        // Create flights
        ZonedDateTime dep1 = ZonedDateTime.now(jfk.zoneId());
        ZonedDateTime arr1 = dep1.plusHours(24);
        Flight flight1 = new Flight("FL1", jfk, cdg, dep1, arr1, 800, FlightType.INTERCONTINENTAL);
        
        // Create route
        AssignedRoute route = new AssignedRoute(batch, List.of(flight1));
        solution.addRoute(route);
        
        // Calculate penalty
        double penalty = evaluator.calculateStorageCapacityPenalties(solution);
        
        // Expected: 100 bags excess × 15,000 points = 1,500,000
        double expectedPenalty = 100 * SolutionEvaluator.PENALTY_STORAGE_CAPACITY;
        assertEquals(expectedPenalty, penalty, 0.01, 
                    "Should penalize 100 bags excess at CDG");
    }
    
    @Test
    @DisplayName("calculateStorageCapacityPenalties handles multiple batches arriving at same airport")
    void testMultipleBatchesSameAirport() {
        Solution solution = new Solution();
        
        // Create two batches of 400 bags each, both arriving at CDG
        // Total: 800 bags, exceeds capacity of 600 by 200
        ShipmentBatch batch1 = createBatch("B1", jfk, cdg, 400);
        ShipmentBatch batch2 = createBatch("B2", nrt, cdg, 400);
        
        ZonedDateTime dep1 = ZonedDateTime.now(jfk.zoneId());
        ZonedDateTime arr1 = dep1.plusHours(24);
        Flight flight1 = new Flight("FL1", jfk, cdg, dep1, arr1, 400, FlightType.INTERCONTINENTAL);
        
        ZonedDateTime dep2 = ZonedDateTime.now(nrt.zoneId());
        ZonedDateTime arr2 = dep2.plusHours(24);
        Flight flight2 = new Flight("FL2", nrt, cdg, dep2, arr2, 400, FlightType.INTERCONTINENTAL);
        
        AssignedRoute route1 = new AssignedRoute(batch1, List.of(flight1));
        AssignedRoute route2 = new AssignedRoute(batch2, List.of(flight2));
        
        solution.addRoute(route1);
        solution.addRoute(route2);
        
        // Calculate penalty
        double penalty = evaluator.calculateStorageCapacityPenalties(solution);
        
        // Expected: 200 bags excess × 15,000 points = 3,000,000
        double expectedPenalty = 200 * SolutionEvaluator.PENALTY_STORAGE_CAPACITY;
        assertEquals(expectedPenalty, penalty, 0.01, 
                    "Should penalize 200 bags excess when two batches arrive at CDG");
    }
    
    @Test
    @DisplayName("calculateStorageCapacityPenalties handles arrivals and departures correctly")
    void testArrivalsAndDepartures() {
        Solution solution = new Solution();
        
        // Create a batch that arrives at CDG (400 bags) then departs to NRT
        ShipmentBatch batch = createBatch("B1", jfk, nrt, 400);
        
        ZonedDateTime dep1 = ZonedDateTime.now(jfk.zoneId());
        ZonedDateTime arr1 = dep1.plusHours(24);
        Flight flight1 = new Flight("FL1", jfk, cdg, dep1, arr1, 400, FlightType.INTERCONTINENTAL);
        
        ZonedDateTime dep2 = arr1.plusHours(2); // 2 hour layover
        ZonedDateTime arr2 = dep2.plusHours(24);
        Flight flight2 = new Flight("FL2", cdg, nrt, dep2, arr2, 400, FlightType.INTERCONTINENTAL);
        
        AssignedRoute route = new AssignedRoute(batch, List.of(flight1, flight2));
        solution.addRoute(route);
        
        // Calculate penalty
        double penalty = evaluator.calculateStorageCapacityPenalties(solution);
        
        // No excess: 400 bags arrive at CDG, then depart, never exceeding 600 capacity
        assertEquals(0.0, penalty, 0.01, 
                    "No penalty when bags arrive and depart without exceeding capacity");
    }
    
    @Test
    @DisplayName("calculateStorageCapacityPenalties simulates occupancy over time correctly")
    void testOccupancySimulation() {
        Solution solution = new Solution();
        
        // Scenario: 
        // - Batch1 (500 bags) arrives at CDG at T0
        // - Batch2 (300 bags) arrives at CDG at T1 (total: 800, excess: 200)
        // - Batch1 departs at T2 (total: 300, no excess)
        // - Batch2 departs at T3 (total: 0, no excess)
        
        ShipmentBatch batch1 = createBatch("B1", jfk, nrt, 500);
        ShipmentBatch batch2 = createBatch("B2", jfk, nrt, 300);
        
        ZonedDateTime t0 = ZonedDateTime.now(jfk.zoneId());
        ZonedDateTime t1 = t0.plusHours(1);
        ZonedDateTime t2 = t0.plusHours(3);
        ZonedDateTime t3 = t0.plusHours(4);
        
        // Batch1: JFK -> CDG (arrives T0) -> NRT (departs T2)
        Flight fl1a = new Flight("FL1A", jfk, cdg, t0.minusHours(24), t0, 500, FlightType.INTERCONTINENTAL);
        Flight fl1b = new Flight("FL1B", cdg, nrt, t2, t2.plusHours(24), 500, FlightType.INTERCONTINENTAL);
        AssignedRoute route1 = new AssignedRoute(batch1, List.of(fl1a, fl1b));
        
        // Batch2: JFK -> CDG (arrives T1) -> NRT (departs T3)
        Flight fl2a = new Flight("FL2A", jfk, cdg, t1.minusHours(24), t1, 300, FlightType.INTERCONTINENTAL);
        Flight fl2b = new Flight("FL2B", cdg, nrt, t3, t3.plusHours(24), 300, FlightType.INTERCONTINENTAL);
        AssignedRoute route2 = new AssignedRoute(batch2, List.of(fl2a, fl2b));
        
        solution.addRoute(route1);
        solution.addRoute(route2);
        
        // Calculate penalty
        double penalty = evaluator.calculateStorageCapacityPenalties(solution);
        
        // Expected: Between T1 and T2, there's an excess of 200 bags
        // Penalty = 200 × 15,000 = 3,000,000
        double expectedPenalty = 200 * SolutionEvaluator.PENALTY_STORAGE_CAPACITY;
        assertEquals(expectedPenalty, penalty, 0.01, 
                    "Should penalize 200 bags excess during overlap period");
    }
    
    @Test
    @DisplayName("calculateStorageCapacityPenalties handles multiple airports independently")
    void testMultipleAirportsIndependently() {
        Solution solution = new Solution();
        
        // Create excess at CDG (700 bags, excess: 100)
        ShipmentBatch batch1 = createBatch("B1", jfk, cdg, 700);
        ZonedDateTime dep1 = ZonedDateTime.now(jfk.zoneId());
        ZonedDateTime arr1 = dep1.plusHours(24);
        Flight flight1 = new Flight("FL1", jfk, cdg, dep1, arr1, 800, FlightType.INTERCONTINENTAL);
        AssignedRoute route1 = new AssignedRoute(batch1, List.of(flight1));
        
        // Create excess at NRT (650 bags, excess: 50)
        ShipmentBatch batch2 = createBatch("B2", jfk, nrt, 650);
        ZonedDateTime dep2 = ZonedDateTime.now(jfk.zoneId());
        ZonedDateTime arr2 = dep2.plusHours(24);
        Flight flight2 = new Flight("FL2", jfk, nrt, dep2, arr2, 700, FlightType.INTERCONTINENTAL);
        AssignedRoute route2 = new AssignedRoute(batch2, List.of(flight2));
        
        solution.addRoute(route1);
        solution.addRoute(route2);
        
        // Calculate penalty
        double penalty = evaluator.calculateStorageCapacityPenalties(solution);
        
        // Expected: (100 + 50) × 15,000 = 2,250,000
        double expectedPenalty = (100 + 50) * SolutionEvaluator.PENALTY_STORAGE_CAPACITY;
        assertEquals(expectedPenalty, penalty, 0.01, 
                    "Should penalize excess at both CDG and NRT independently");
    }
    
    @Test
    @DisplayName("calculateStorageCapacityPenalties uses correct penalty constant")
    void testCorrectPenaltyConstant() {
        Solution solution = new Solution();
        
        // Create a batch with 1 bag excess
        ShipmentBatch batch = createBatch("B1", jfk, cdg, 601);
        
        ZonedDateTime dep1 = ZonedDateTime.now(jfk.zoneId());
        ZonedDateTime arr1 = dep1.plusHours(24);
        Flight flight1 = new Flight("FL1", jfk, cdg, dep1, arr1, 700, FlightType.INTERCONTINENTAL);
        
        AssignedRoute route = new AssignedRoute(batch, List.of(flight1));
        solution.addRoute(route);
        
        // Calculate penalty
        double penalty = evaluator.calculateStorageCapacityPenalties(solution);
        
        // Expected: 1 bag × 15,000 points = 15,000
        assertEquals(15_000.0, penalty, 0.01, 
                    "Should use PENALTY_STORAGE_CAPACITY constant (15,000 points per bag)");
    }
    
    // ==================== Helper Methods ====================
    
    private ShipmentBatch createBatch(String id, Airport origin, Airport destination, int quantity) {
        return new ShipmentBatch(
            id,
            id + "_airport",
            "CLIENT1",
            origin,
            destination,
            quantity,
            ZonedDateTime.now(origin.zoneId())
        );
    }
}
