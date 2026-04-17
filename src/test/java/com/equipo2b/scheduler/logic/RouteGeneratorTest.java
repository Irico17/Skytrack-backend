package com.equipo2b.scheduler.logic;

import com.equipo2b.scheduler.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RouteGenerator class.
 * Validates task 4.3: generateFeasibleRoute method.
 */
class RouteGeneratorTest {
    
    private AirportManager airportManager;
    private FlightPlan flightPlan;
    private RouteGenerator routeGenerator;
    
    private Airport jfk;
    private Airport cdg;
    private Airport nrt;
    
    @BeforeEach
    void setUp() {
        // Create airports
        airportManager = new AirportManager();
        
        jfk = new Airport("JFK", "New York", "USA", 
                         ZoneId.of("America/New_York"), 600, 40.6413, -73.7781, Continent.AMERICA);
        cdg = new Airport("CDG", "Paris", "France", 
                         ZoneId.of("Europe/Paris"), 700, 49.0097, 2.5479, Continent.EUROPE);
        nrt = new Airport("NRT", "Tokyo", "Japan", 
                         ZoneId.of("Asia/Tokyo"), 650, 35.7720, 140.3929, Continent.ASIA);
        
        airportManager.addAirport(jfk);
        airportManager.addAirport(cdg);
        airportManager.addAirport(nrt);
        
        // Create flight plan
        flightPlan = new FlightPlan();
        
        // Add flights
        ZonedDateTime baseTime = ZonedDateTime.of(2024, 1, 1, 10, 0, 0, 0, ZoneId.of("America/New_York"));
        
        // JFK -> CDG (intercontinental, 24h)
        Flight flight1 = new Flight("AA100", jfk, cdg, 
                                    baseTime, 
                                    baseTime.plusHours(24), 
                                    300, FlightType.INTERCONTINENTAL);
        flightPlan.addFlight(flight1);
        
        // CDG -> NRT (intercontinental, 24h)
        Flight flight2 = new Flight("AF200", cdg, nrt, 
                                    baseTime.plusHours(25), 
                                    baseTime.plusHours(49), 
                                    350, FlightType.INTERCONTINENTAL);
        flightPlan.addFlight(flight2);
        
        routeGenerator = new RouteGenerator(flightPlan, airportManager);
    }
    
    @Test
    void testGenerateFeasibleRoute_DirectFlight() {
        // Create a batch from JFK to CDG
        ShipmentBatch batch = new ShipmentBatch(
            "BATCH001", "JFK001", "CLIENT001", 
            jfk, cdg, 50, 
            ZonedDateTime.of(2024, 1, 1, 9, 0, 0, 0, ZoneId.of("America/New_York"))
        );
        
        // Generate route
        AssignedRoute route = routeGenerator.generateFeasibleRoute(batch);
        
        // Verify route was generated
        assertNotNull(route, "Route should be generated");
        assertEquals(1, route.getFlights().size(), "Route should have 1 flight");
        assertEquals("AA100", route.getFlights().get(0).flightId());
        assertTrue(route.meetsSLA(), "Route should meet SLA");
    }
    
    @Test
    void testGenerateFeasibleRoute_WithConnection() {
        // Create a batch from JFK to NRT (requires connection through CDG)
        ShipmentBatch batch = new ShipmentBatch(
            "BATCH002", "JFK002", "CLIENT001", 
            jfk, nrt, 30, 
            ZonedDateTime.of(2024, 1, 1, 9, 0, 0, 0, ZoneId.of("America/New_York"))
        );
        
        // Generate route
        AssignedRoute route = routeGenerator.generateFeasibleRoute(batch);
        
        // Verify route was generated
        assertNotNull(route, "Route should be generated");
        assertEquals(2, route.getFlights().size(), "Route should have 2 flights");
        assertEquals("AA100", route.getFlights().get(0).flightId());
        assertEquals("AF200", route.getFlights().get(1).flightId());
        assertTrue(route.meetsSLA(), "Route should meet SLA (48h for intercontinental)");
    }
    
    @Test
    void testGenerateFeasibleRoute_NoRouteAvailable() {
        // Create airports with no connecting flights
        Airport lax = new Airport("LAX", "Los Angeles", "USA", 
                                 ZoneId.of("America/Los_Angeles"), 600, 33.9416, -118.4085, Continent.AMERICA);
        Airport syd = new Airport("SYD", "Sydney", "Australia", 
                                 ZoneId.of("Australia/Sydney"), 650, -33.9461, 151.1772, Continent.ASIA);
        
        airportManager.addAirport(lax);
        airportManager.addAirport(syd);
        
        // Create a batch with no available route
        ShipmentBatch batch = new ShipmentBatch(
            "BATCH003", "LAX001", "CLIENT001", 
            lax, syd, 40, 
            ZonedDateTime.of(2024, 1, 1, 9, 0, 0, 0, ZoneId.of("America/Los_Angeles"))
        );
        
        // Generate route
        AssignedRoute route = routeGenerator.generateFeasibleRoute(batch);
        
        // Verify no route was generated
        assertNull(route, "Route should be null when no path exists");
    }
    
    @Test
    void testGenerateFeasibleRoute_WithAllowedFlights() {
        // Create a batch from JFK to CDG
        ShipmentBatch batch = new ShipmentBatch(
            "BATCH004", "JFK004", "CLIENT001", 
            jfk, cdg, 50, 
            ZonedDateTime.of(2024, 1, 1, 9, 0, 0, 0, ZoneId.of("America/New_York"))
        );
        
        // Get the flight
        Flight flight = flightPlan.getFlightsFromAirport(jfk, 
            batch.ingressTime(), 
            batch.ingressTime().plus(batch.calculateSLA())).get(0);
        
        // Generate route with allowed flights
        AssignedRoute route = routeGenerator.generateFeasibleRoute(batch, List.of(flight));
        
        // Verify route was generated
        assertNotNull(route, "Route should be generated with allowed flights");
        assertEquals(1, route.getFlights().size(), "Route should have 1 flight");
        assertEquals("AA100", route.getFlights().get(0).flightId());
    }
}
