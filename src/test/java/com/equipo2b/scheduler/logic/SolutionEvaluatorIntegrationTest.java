package com.equipo2b.scheduler.logic;

import com.equipo2b.scheduler.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for SolutionEvaluator fitness calculation methods.
 * 
 * Tests verify:
 * - Flight capacity penalty calculation
 * - Storage capacity penalty calculation
 * - SLA penalty calculation
 * - Layover penalty calculation
 * - Time slack reward calculation
 * - Unused flight reward calculation
 * - Overall fitness evaluation
 */
class SolutionEvaluatorIntegrationTest {
    
    private FlightPlan flightPlan;
    private AirportManager airportManager;
    private SolutionEvaluator evaluator;
    
    private Airport jfk;
    private Airport cdg;
    private Airport lhr;
    
    @BeforeEach
    void setUp() {
        // Create test airports
        jfk = new Airport("JFK", "New York", "USA", ZoneId.of("America/New_York"), 
                         600, 40.6413, -73.7781, Continent.AMERICA);
        cdg = new Airport("CDG", "Paris", "France", ZoneId.of("Europe/Paris"), 
                         700, 49.0097, 2.5479, Continent.EUROPE);
        lhr = new Airport("LHR", "London", "UK", ZoneId.of("Europe/London"), 
                         650, 51.4700, -0.4543, Continent.EUROPE);
        
        // Create airport manager
        airportManager = new AirportManager();
        airportManager.addAirport(jfk);
        airportManager.addAirport(cdg);
        airportManager.addAirport(lhr);
        
        // Create flight plan
        flightPlan = new FlightPlan();
        
        // Create evaluator
        evaluator = new SolutionEvaluator(flightPlan, airportManager);
    }
    
    @Test
    @DisplayName("calculateFlightCapacityPenalties: no penalty when within capacity")
    void testFlightCapacityPenalties_WithinCapacity() {
        // Create a flight with capacity 200
        ZonedDateTime departure = ZonedDateTime.now(jfk.zoneId());
        ZonedDateTime arrival = departure.plusHours(24);
        Flight flight = new Flight("FL001", jfk, cdg, departure, arrival, 200, FlightType.INTERCONTINENTAL);
        flightPlan.addFlight(flight);
        
        // Create a batch with 150 bags (within capacity)
        ShipmentBatch batch = new ShipmentBatch("B001", "AB001", "C001", jfk, cdg, 150, departure.minusHours(1));
        
        // Create route with single flight
        List<Flight> flights = List.of(flight);
        AssignedRoute route = new AssignedRoute(batch, flights);
        
        // Create solution
        Solution solution = new Solution();
        solution.addRoute(route);
        
        // Calculate penalty
        double penalty = evaluator.calculateFlightCapacityPenalties(solution);
        
        assertEquals(0.0, penalty, 0.01, "No penalty when flight is within capacity");
    }
    
    @Test
    @DisplayName("calculateFlightCapacityPenalties: penalty when exceeding capacity")
    void testFlightCapacityPenalties_ExceedingCapacity() {
        // Create a flight with capacity 200
        ZonedDateTime departure = ZonedDateTime.now(jfk.zoneId());
        ZonedDateTime arrival = departure.plusHours(24);
        Flight flight = new Flight("FL001", jfk, cdg, departure, arrival, 200, FlightType.INTERCONTINENTAL);
        flightPlan.addFlight(flight);
        
        // Create two batches that together exceed capacity (150 + 100 = 250 > 200)
        ShipmentBatch batch1 = new ShipmentBatch("B001", "AB001", "C001", jfk, cdg, 150, departure.minusHours(1));
        ShipmentBatch batch2 = new ShipmentBatch("B002", "AB002", "C002", jfk, cdg, 100, departure.minusHours(1));
        
        // Create routes using the same flight
        List<Flight> flights = List.of(flight);
        AssignedRoute route1 = new AssignedRoute(batch1, flights);
        AssignedRoute route2 = new AssignedRoute(batch2, flights);
        
        // Create solution
        Solution solution = new Solution();
        solution.addRoute(route1);
        solution.addRoute(route2);
        
        // Calculate penalty
        double penalty = evaluator.calculateFlightCapacityPenalties(solution);
        
        // Excess = 250 - 200 = 50 bags
        // Penalty = 50 * 10,000 = 500,000
        double expectedPenalty = 50 * SolutionEvaluator.PENALTY_FLIGHT_CAPACITY;
        assertEquals(expectedPenalty, penalty, 0.01, "Penalty should be 50 bags * 10,000");
    }
    
    @Test
    @DisplayName("calculateStorageCapacityPenalties: no penalty when within capacity")
    void testStorageCapacityPenalties_WithinCapacity() {
        // Create flights
        ZonedDateTime dep1 = ZonedDateTime.now(jfk.zoneId());
        ZonedDateTime arr1 = dep1.plusHours(24);
        Flight flight1 = new Flight("FL001", jfk, cdg, dep1, arr1, 200, FlightType.INTERCONTINENTAL);
        
        ZonedDateTime dep2 = arr1.plusMinutes(30); // 30 min layover
        ZonedDateTime arr2 = dep2.plusHours(12);
        Flight flight2 = new Flight("FL002", cdg, lhr, dep2, arr2, 200, FlightType.INTRACONTINENTAL);
        
        flightPlan.addFlight(flight1);
        flightPlan.addFlight(flight2);
        
        // Create batch with 100 bags (well within CDG capacity of 700)
        ShipmentBatch batch = new ShipmentBatch("B001", "AB001", "C001", jfk, lhr, 100, dep1.minusHours(1));
        
        // Create route
        List<Flight> flights = List.of(flight1, flight2);
        AssignedRoute route = new AssignedRoute(batch, flights);
        
        // Create solution
        Solution solution = new Solution();
        solution.addRoute(route);
        
        // Calculate penalty
        double penalty = evaluator.calculateStorageCapacityPenalties(solution);
        
        assertEquals(0.0, penalty, 0.01, "No penalty when storage is within capacity");
    }
    
    @Test
    @DisplayName("calculateSLAPenalties: no penalty when meeting SLA")
    void testSLAPenalties_MeetingSLA() {
        // Create flight that arrives within 24 hours (intracontinental SLA)
        ZonedDateTime departure = ZonedDateTime.now(jfk.zoneId());
        ZonedDateTime arrival = departure.plusHours(12); // Well within 24h SLA
        Flight flight = new Flight("FL001", jfk, cdg, departure, arrival, 200, FlightType.INTERCONTINENTAL);
        flightPlan.addFlight(flight);
        
        // Create batch
        ShipmentBatch batch = new ShipmentBatch("B001", "AB001", "C001", jfk, cdg, 100, departure.minusHours(1));
        
        // Create route
        List<Flight> flights = List.of(flight);
        AssignedRoute route = new AssignedRoute(batch, flights);
        
        // Create solution
        Solution solution = new Solution();
        solution.addRoute(route);
        
        // Calculate penalty
        double penalty = evaluator.calculateSLAPenalties(solution);
        
        assertEquals(0.0, penalty, 0.01, "No penalty when meeting SLA");
    }
    
    @Test
    @DisplayName("calculateLayoverPenalties: no penalty with sufficient layover")
    void testLayoverPenalties_SufficientLayover() {
        // Create flights with 30 minute layover (> 10 min minimum)
        ZonedDateTime dep1 = ZonedDateTime.now(jfk.zoneId());
        ZonedDateTime arr1 = dep1.plusHours(24);
        Flight flight1 = new Flight("FL001", jfk, cdg, dep1, arr1, 200, FlightType.INTERCONTINENTAL);
        
        ZonedDateTime dep2 = arr1.plusMinutes(30); // 30 min layover
        ZonedDateTime arr2 = dep2.plusHours(12);
        Flight flight2 = new Flight("FL002", cdg, lhr, dep2, arr2, 200, FlightType.INTRACONTINENTAL);
        
        flightPlan.addFlight(flight1);
        flightPlan.addFlight(flight2);
        
        // Create batch
        ShipmentBatch batch = new ShipmentBatch("B001", "AB001", "C001", jfk, lhr, 100, dep1.minusHours(1));
        
        // Create route
        List<Flight> flights = List.of(flight1, flight2);
        AssignedRoute route = new AssignedRoute(batch, flights);
        
        // Create solution
        Solution solution = new Solution();
        solution.addRoute(route);
        
        // Calculate penalty
        double penalty = evaluator.calculateLayoverPenalties(solution);
        
        assertEquals(0.0, penalty, 0.01, "No penalty with 30 minute layover");
    }
    
    @Test
    @DisplayName("calculateTimeSlackRewards: rewards for meeting SLA with slack")
    void testTimeSlackRewards_WithSlack() {
        // Create flight that arrives well before SLA (48h for intercontinental)
        ZonedDateTime departure = ZonedDateTime.now(jfk.zoneId());
        ZonedDateTime arrival = departure.plusHours(24); // 24h before 48h SLA = 24h slack
        Flight flight = new Flight("FL001", jfk, cdg, departure, arrival, 200, FlightType.INTERCONTINENTAL);
        flightPlan.addFlight(flight);
        
        // Create batch with ingress 1 hour before departure
        ShipmentBatch batch = new ShipmentBatch("B001", "AB001", "C001", jfk, cdg, 100, departure.minusHours(1));
        
        // Create route
        List<Flight> flights = List.of(flight);
        AssignedRoute route = new AssignedRoute(batch, flights);
        
        // Create solution
        Solution solution = new Solution();
        solution.addRoute(route);
        
        // Calculate reward
        double reward = evaluator.calculateTimeSlackRewards(solution);
        
        // Transit time = 1h (before departure) + 24h (flight) = 25h
        // SLA = 48h
        // Slack = 48h - 25h = 23h
        // Reward = 23h * 100 = 2300, but capped at 500
        double expectedReward = SolutionEvaluator.REWARD_TIME_SLACK_MAX;
        assertEquals(expectedReward, reward, 0.01, "Reward should be capped at 500");
    }
    
    @Test
    @DisplayName("calculateUnusedFlightRewards: rewards for unused flights")
    void testUnusedFlightRewards() {
        // Add 5 flights to the plan
        ZonedDateTime departure = ZonedDateTime.now(jfk.zoneId());
        for (int i = 0; i < 5; i++) {
            ZonedDateTime dep = departure.plusHours(i * 24);
            ZonedDateTime arr = dep.plusHours(24);
            Flight flight = new Flight("FL00" + i, jfk, cdg, dep, arr, 200, FlightType.INTERCONTINENTAL);
            flightPlan.addFlight(flight);
        }
        
        // Use only 2 flights in the solution
        Flight usedFlight1 = flightPlan.getAllFlights().get(0);
        Flight usedFlight2 = flightPlan.getAllFlights().get(1);
        
        ShipmentBatch batch1 = new ShipmentBatch("B001", "AB001", "C001", jfk, cdg, 100, departure.minusHours(1));
        ShipmentBatch batch2 = new ShipmentBatch("B002", "AB002", "C002", jfk, cdg, 100, departure.plusHours(23));
        
        AssignedRoute route1 = new AssignedRoute(batch1, List.of(usedFlight1));
        AssignedRoute route2 = new AssignedRoute(batch2, List.of(usedFlight2));
        
        Solution solution = new Solution();
        solution.addRoute(route1);
        solution.addRoute(route2);
        
        // Calculate reward
        double reward = evaluator.calculateUnusedFlightRewards(solution);
        
        // 3 unused flights * 50 = 150
        double expectedReward = 3 * SolutionEvaluator.REWARD_UNUSED_FLIGHT;
        assertEquals(expectedReward, reward, 0.01, "Should reward 3 unused flights");
    }
    
    @Test
    @DisplayName("evaluate: calculates total fitness correctly")
    void testEvaluate_TotalFitness() {
        // Create a simple solution
        ZonedDateTime departure = ZonedDateTime.now(jfk.zoneId());
        ZonedDateTime arrival = departure.plusHours(24);
        Flight flight = new Flight("FL001", jfk, cdg, departure, arrival, 200, FlightType.INTERCONTINENTAL);
        flightPlan.addFlight(flight);
        
        ShipmentBatch batch = new ShipmentBatch("B001", "AB001", "C001", jfk, cdg, 100, departure.minusHours(1));
        AssignedRoute route = new AssignedRoute(batch, List.of(flight));
        
        Solution solution = new Solution();
        solution.addRoute(route);
        
        // Calculate fitness
        double fitness = evaluator.evaluate(solution);
        
        // Verify fitness = penalties - rewards
        double penalties = evaluator.calculateFlightCapacityPenalties(solution) +
                          evaluator.calculateStorageCapacityPenalties(solution) +
                          evaluator.calculateSLAPenalties(solution) +
                          evaluator.calculateLayoverPenalties(solution);
        
        double rewards = evaluator.calculateTimeSlackRewards(solution) +
                        evaluator.calculateUnusedFlightRewards(solution);
        
        double expectedFitness = penalties - rewards;
        
        assertEquals(expectedFitness, fitness, 0.01, "Fitness should equal penalties minus rewards");
    }
    
    @Test
    @DisplayName("evaluate: lower fitness is better")
    void testEvaluate_LowerIsBetter() {
        // Create two solutions: one good, one bad
        ZonedDateTime departure = ZonedDateTime.now(jfk.zoneId());
        
        // Good solution: within capacity, meets SLA
        ZonedDateTime arrival1 = departure.plusHours(24);
        Flight goodFlight = new Flight("FL001", jfk, cdg, departure, arrival1, 200, FlightType.INTERCONTINENTAL);
        flightPlan.addFlight(goodFlight);
        
        ShipmentBatch goodBatch = new ShipmentBatch("B001", "AB001", "C001", jfk, cdg, 100, departure.minusHours(1));
        AssignedRoute goodRoute = new AssignedRoute(goodBatch, List.of(goodFlight));
        
        Solution goodSolution = new Solution();
        goodSolution.addRoute(goodRoute);
        
        // Bad solution: exceeds capacity
        Flight badFlight = new Flight("FL002", jfk, cdg, departure.plusHours(48), departure.plusHours(72), 200, FlightType.INTERCONTINENTAL);
        flightPlan.addFlight(badFlight);
        
        ShipmentBatch badBatch1 = new ShipmentBatch("B002", "AB002", "C002", jfk, cdg, 150, departure.plusHours(47));
        ShipmentBatch badBatch2 = new ShipmentBatch("B003", "AB003", "C003", jfk, cdg, 100, departure.plusHours(47));
        
        AssignedRoute badRoute1 = new AssignedRoute(badBatch1, List.of(badFlight));
        AssignedRoute badRoute2 = new AssignedRoute(badBatch2, List.of(badFlight));
        
        Solution badSolution = new Solution();
        badSolution.addRoute(badRoute1);
        badSolution.addRoute(badRoute2);
        
        // Evaluate both
        double goodFitness = evaluator.evaluate(goodSolution);
        double badFitness = evaluator.evaluate(badSolution);
        
        assertTrue(goodFitness < badFitness, "Good solution should have lower fitness than bad solution");
    }
}
