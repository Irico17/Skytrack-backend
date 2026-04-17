package com.equipo2b.scheduler.logic;

import com.equipo2b.scheduler.model.FlightPlan;
import com.equipo2b.scheduler.model.AirportManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitarios para SolutionEvaluator.
 * 
 * Esta suite verifica:
 * - Constantes de penalización y premio tienen los valores correctos
 * - Constructor valida parámetros null
 * - Dependencias se almacenan correctamente
 */
class SolutionEvaluatorTest {
    
    @Test
    @DisplayName("Penalty constants have correct values")
    void testPenaltyConstants() {
        assertEquals(10_000.0, SolutionEvaluator.PENALTY_FLIGHT_CAPACITY, 
                    "Flight capacity penalty should be 10,000 points per bag");
        
        assertEquals(15_000.0, SolutionEvaluator.PENALTY_STORAGE_CAPACITY,
                    "Storage capacity penalty should be 15,000 points per bag");
        
        assertEquals(20_000.0, SolutionEvaluator.PENALTY_SLA_VIOLATION,
                    "SLA violation penalty should be 20,000 points per hour");
        
        assertEquals(5_000.0, SolutionEvaluator.PENALTY_LAYOVER_VIOLATION,
                    "Layover violation penalty should be 5,000 points per violation");
    }
    
    @Test
    @DisplayName("Reward constants have correct values")
    void testRewardConstants() {
        assertEquals(100.0, SolutionEvaluator.REWARD_TIME_SLACK_PER_HOUR,
                    "Time slack reward should be 100 points per hour");
        
        assertEquals(500.0, SolutionEvaluator.REWARD_TIME_SLACK_MAX,
                    "Maximum time slack reward should be 500 points per route");
        
        assertEquals(50.0, SolutionEvaluator.REWARD_UNUSED_FLIGHT,
                    "Unused flight reward should be 50 points per flight");
    }
    
    @Test
    @DisplayName("Penalty hierarchy is correct")
    void testPenaltyHierarchy() {
        // SLA violation should be the highest penalty
        assertTrue(SolutionEvaluator.PENALTY_SLA_VIOLATION > SolutionEvaluator.PENALTY_STORAGE_CAPACITY,
                  "SLA violation penalty should be higher than storage capacity penalty");
        
        assertTrue(SolutionEvaluator.PENALTY_SLA_VIOLATION > SolutionEvaluator.PENALTY_FLIGHT_CAPACITY,
                  "SLA violation penalty should be higher than flight capacity penalty");
        
        // Storage capacity penalty should be higher than flight capacity penalty
        assertTrue(SolutionEvaluator.PENALTY_STORAGE_CAPACITY > SolutionEvaluator.PENALTY_FLIGHT_CAPACITY,
                  "Storage capacity penalty should be higher than flight capacity penalty");
        
        // All penalties should be significantly higher than rewards
        assertTrue(SolutionEvaluator.PENALTY_LAYOVER_VIOLATION > SolutionEvaluator.REWARD_TIME_SLACK_MAX,
                  "Even the lowest penalty should be higher than the maximum reward");
    }
    
    @Test
    @DisplayName("Constructor throws NullPointerException when FlightPlan is null")
    void testConstructorNullFlightPlan() {
        AirportManager airportManager = new AirportManager();
        
        NullPointerException exception = assertThrows(NullPointerException.class, () -> {
            new SolutionEvaluator(null, airportManager);
        });
        
        assertEquals("FlightPlan cannot be null", exception.getMessage());
    }
    
    @Test
    @DisplayName("Constructor throws NullPointerException when AirportManager is null")
    void testConstructorNullAirportManager() {
        FlightPlan flightPlan = new FlightPlan();
        
        NullPointerException exception = assertThrows(NullPointerException.class, () -> {
            new SolutionEvaluator(flightPlan, null);
        });
        
        assertEquals("AirportManager cannot be null", exception.getMessage());
    }
    
    @Test
    @DisplayName("Constructor succeeds with valid parameters")
    void testConstructorValid() {
        FlightPlan flightPlan = new FlightPlan();
        AirportManager airportManager = new AirportManager();
        
        assertDoesNotThrow(() -> {
            SolutionEvaluator evaluator = new SolutionEvaluator(flightPlan, airportManager);
            assertNotNull(evaluator, "Evaluator should be created successfully");
        });
    }
    
    @Test
    @DisplayName("Penalty values are appropriate for optimization")
    void testPenaltyValuesAreAppropriate() {
        // Penalties should be large enough to guide the algorithm
        // but not so large as to cause numerical overflow
        assertTrue(SolutionEvaluator.PENALTY_FLIGHT_CAPACITY >= 1_000.0,
                  "Penalties should be significant");
        
        assertTrue(SolutionEvaluator.PENALTY_SLA_VIOLATION <= 1_000_000.0,
                  "Penalties should not be excessively large");
    }
    
    @Test
    @DisplayName("Reward values are appropriate for optimization")
    void testRewardValuesAreAppropriate() {
        // Rewards should be positive but smaller than penalties
        assertTrue(SolutionEvaluator.REWARD_TIME_SLACK_PER_HOUR > 0,
                  "Rewards should be positive");
        
        assertTrue(SolutionEvaluator.REWARD_TIME_SLACK_MAX < SolutionEvaluator.PENALTY_LAYOVER_VIOLATION,
                  "Maximum reward should be less than minimum penalty");
    }
    
    @Test
    @DisplayName("Time slack reward cap is reasonable")
    void testTimeSlackRewardCap() {
        // Maximum reward should be 5 hours worth of slack
        double expectedMaxHours = SolutionEvaluator.REWARD_TIME_SLACK_MAX / 
                                 SolutionEvaluator.REWARD_TIME_SLACK_PER_HOUR;
        
        assertEquals(5.0, expectedMaxHours, 0.01,
                    "Maximum time slack reward should cap at 5 hours");
    }
    
    // ==================== Tests for Fitness Calculation Methods ====================
    
    @Test
    @DisplayName("calculateFlightCapacityPenalties returns zero for empty solution")
    void testCalculateFlightCapacityPenalties_EmptySolution() {
        FlightPlan flightPlan = new FlightPlan();
        AirportManager airportManager = new AirportManager();
        SolutionEvaluator evaluator = new SolutionEvaluator(flightPlan, airportManager);
        
        com.equipo2b.scheduler.model.Solution solution = new com.equipo2b.scheduler.model.Solution();
        
        double penalty = evaluator.calculateFlightCapacityPenalties(solution);
        
        assertEquals(0.0, penalty, 0.01, "Empty solution should have zero flight capacity penalty");
    }
    
    @Test
    @DisplayName("calculateStorageCapacityPenalties returns zero for empty solution")
    void testCalculateStorageCapacityPenalties_EmptySolution() {
        FlightPlan flightPlan = new FlightPlan();
        AirportManager airportManager = new AirportManager();
        SolutionEvaluator evaluator = new SolutionEvaluator(flightPlan, airportManager);
        
        com.equipo2b.scheduler.model.Solution solution = new com.equipo2b.scheduler.model.Solution();
        
        double penalty = evaluator.calculateStorageCapacityPenalties(solution);
        
        assertEquals(0.0, penalty, 0.01, "Empty solution should have zero storage capacity penalty");
    }
    
    @Test
    @DisplayName("calculateSLAPenalties returns zero for empty solution")
    void testCalculateSLAPenalties_EmptySolution() {
        FlightPlan flightPlan = new FlightPlan();
        AirportManager airportManager = new AirportManager();
        SolutionEvaluator evaluator = new SolutionEvaluator(flightPlan, airportManager);
        
        com.equipo2b.scheduler.model.Solution solution = new com.equipo2b.scheduler.model.Solution();
        
        double penalty = evaluator.calculateSLAPenalties(solution);
        
        assertEquals(0.0, penalty, 0.01, "Empty solution should have zero SLA penalty");
    }
    
    @Test
    @DisplayName("calculateLayoverPenalties returns zero for empty solution")
    void testCalculateLayoverPenalties_EmptySolution() {
        FlightPlan flightPlan = new FlightPlan();
        AirportManager airportManager = new AirportManager();
        SolutionEvaluator evaluator = new SolutionEvaluator(flightPlan, airportManager);
        
        com.equipo2b.scheduler.model.Solution solution = new com.equipo2b.scheduler.model.Solution();
        
        double penalty = evaluator.calculateLayoverPenalties(solution);
        
        assertEquals(0.0, penalty, 0.01, "Empty solution should have zero layover penalty");
    }
    
    @Test
    @DisplayName("calculateTimeSlackRewards returns zero for empty solution")
    void testCalculateTimeSlackRewards_EmptySolution() {
        FlightPlan flightPlan = new FlightPlan();
        AirportManager airportManager = new AirportManager();
        SolutionEvaluator evaluator = new SolutionEvaluator(flightPlan, airportManager);
        
        com.equipo2b.scheduler.model.Solution solution = new com.equipo2b.scheduler.model.Solution();
        
        double reward = evaluator.calculateTimeSlackRewards(solution);
        
        assertEquals(0.0, reward, 0.01, "Empty solution should have zero time slack reward");
    }
    
    @Test
    @DisplayName("calculateUnusedFlightRewards returns correct value when all flights unused")
    void testCalculateUnusedFlightRewards_AllFlightsUnused() {
        FlightPlan flightPlan = new FlightPlan();
        // Add 10 flights to the plan
        for (int i = 0; i < 10; i++) {
            com.equipo2b.scheduler.model.Airport origin = createTestAirport("ORG" + i, com.equipo2b.scheduler.model.Continent.AMERICA);
            com.equipo2b.scheduler.model.Airport dest = createTestAirport("DST" + i, com.equipo2b.scheduler.model.Continent.EUROPE);
            com.equipo2b.scheduler.model.Flight flight = createTestFlight("FL" + i, origin, dest);
            flightPlan.addFlight(flight);
        }
        
        AirportManager airportManager = new AirportManager();
        SolutionEvaluator evaluator = new SolutionEvaluator(flightPlan, airportManager);
        
        com.equipo2b.scheduler.model.Solution solution = new com.equipo2b.scheduler.model.Solution();
        
        double reward = evaluator.calculateUnusedFlightRewards(solution);
        
        double expectedReward = 10 * SolutionEvaluator.REWARD_UNUSED_FLIGHT;
        assertEquals(expectedReward, reward, 0.01, 
                    "Should reward all 10 unused flights");
    }
    
    @Test
    @DisplayName("evaluate returns correct fitness for empty solution")
    void testEvaluate_EmptySolution() {
        FlightPlan flightPlan = new FlightPlan();
        AirportManager airportManager = new AirportManager();
        SolutionEvaluator evaluator = new SolutionEvaluator(flightPlan, airportManager);
        
        com.equipo2b.scheduler.model.Solution solution = new com.equipo2b.scheduler.model.Solution();
        
        double fitness = evaluator.evaluate(solution);
        
        // Empty solution: 0 penalties - 0 rewards = 0
        assertEquals(0.0, fitness, 0.01, "Empty solution should have zero fitness");
    }
    
    @Test
    @DisplayName("evaluate calculates fitness as penalties minus rewards")
    void testEvaluate_FitnessFormula() {
        FlightPlan flightPlan = new FlightPlan();
        AirportManager airportManager = new AirportManager();
        SolutionEvaluator evaluator = new SolutionEvaluator(flightPlan, airportManager);
        
        com.equipo2b.scheduler.model.Solution solution = new com.equipo2b.scheduler.model.Solution();
        
        double fitness = evaluator.evaluate(solution);
        
        // Verify fitness = penalties - rewards
        double penalties = evaluator.calculateFlightCapacityPenalties(solution) +
                          evaluator.calculateStorageCapacityPenalties(solution) +
                          evaluator.calculateSLAPenalties(solution) +
                          evaluator.calculateLayoverPenalties(solution);
        
        double rewards = evaluator.calculateTimeSlackRewards(solution) +
                        evaluator.calculateUnusedFlightRewards(solution);
        
        double expectedFitness = penalties - rewards;
        
        assertEquals(expectedFitness, fitness, 0.01, 
                    "Fitness should equal penalties minus rewards");
    }
    
    // ==================== Helper Methods ====================
    
    private com.equipo2b.scheduler.model.Airport createTestAirport(String id, com.equipo2b.scheduler.model.Continent continent) {
        return new com.equipo2b.scheduler.model.Airport(
            id,
            "City",
            "Country",
            java.time.ZoneId.of("UTC"),
            600,  // storage capacity
            0.0,  // latitude
            0.0,  // longitude
            continent
        );
    }
    
    private com.equipo2b.scheduler.model.Flight createTestFlight(String id, 
                                                                  com.equipo2b.scheduler.model.Airport origin, 
                                                                  com.equipo2b.scheduler.model.Airport dest) {
        java.time.ZonedDateTime departure = java.time.ZonedDateTime.now();
        java.time.ZonedDateTime arrival = departure.plusHours(24);
        
        return new com.equipo2b.scheduler.model.Flight(
            id,
            origin,
            dest,
            departure,
            arrival,
            200,  // capacity
            com.equipo2b.scheduler.model.FlightType.INTERCONTINENTAL
        );
    }
}
