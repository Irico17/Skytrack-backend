package com.equipo2b.scheduler.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FlightPlan class.
 * 
 * Tests:
 * - Constructor initialization
 * - addFlight method
 * - getFlightsFromAirport with time window filtering
 * - getAllFlights method
 * - Index by origin airport functionality
 */
class FlightPlanTest {

    private Airport jfk;
    private Airport cdg;
    private Airport nrt;
    private Flight flight1;
    private Flight flight2;
    private Flight flight3;

    @BeforeEach
    void setUp() {
        // Create test airports
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

        // Create test flights
        ZonedDateTime baseTime = ZonedDateTime.of(2024, 1, 15, 10, 0, 0, 0, jfk.zoneId());
        
        flight1 = new Flight(
            "AA100",
            jfk,
            cdg,
            baseTime,
            baseTime.plusHours(24),
            300,
            FlightType.INTERCONTINENTAL
        );

        flight2 = new Flight(
            "AA200",
            jfk,
            nrt,
            baseTime.plusHours(2),
            baseTime.plusHours(26),
            350,
            FlightType.INTERCONTINENTAL
        );

        flight3 = new Flight(
            "AF300",
            cdg,
            nrt,
            baseTime.plusHours(5),
            baseTime.plusHours(29),
            320,
            FlightType.INTERCONTINENTAL
        );
    }

    @Test
    void testEmptyConstructor() {
        FlightPlan plan = new FlightPlan();
        
        assertNotNull(plan);
        assertEquals(0, plan.getTotalFlights());
        assertTrue(plan.getAllFlights().isEmpty());
    }

    @Test
    void testConstructorWithFlights() {
        List<Flight> flights = List.of(flight1, flight2, flight3);
        FlightPlan plan = new FlightPlan(flights);
        
        assertEquals(3, plan.getTotalFlights());
        assertEquals(3, plan.getAllFlights().size());
    }

    @Test
    void testAddFlight() {
        FlightPlan plan = new FlightPlan();
        
        plan.addFlight(flight1);
        assertEquals(1, plan.getTotalFlights());
        
        plan.addFlight(flight2);
        assertEquals(2, plan.getTotalFlights());
        
        plan.addFlight(flight3);
        assertEquals(3, plan.getTotalFlights());
    }

    @Test
    void testGetFlightsFromAirport_WithinTimeWindow() {
        FlightPlan plan = new FlightPlan();
        plan.addFlight(flight1);
        plan.addFlight(flight2);
        plan.addFlight(flight3);

        ZonedDateTime start = ZonedDateTime.of(2024, 1, 15, 9, 0, 0, 0, jfk.zoneId());
        ZonedDateTime end = ZonedDateTime.of(2024, 1, 15, 15, 0, 0, 0, jfk.zoneId());

        List<Flight> jfkFlights = plan.getFlightsFromAirport(jfk, start, end);
        
        assertEquals(2, jfkFlights.size());
        assertTrue(jfkFlights.contains(flight1));
        assertTrue(jfkFlights.contains(flight2));
    }

    @Test
    void testGetFlightsFromAirport_NarrowTimeWindow() {
        FlightPlan plan = new FlightPlan();
        plan.addFlight(flight1);
        plan.addFlight(flight2);

        // Window that only includes flight1
        ZonedDateTime start = ZonedDateTime.of(2024, 1, 15, 9, 0, 0, 0, jfk.zoneId());
        ZonedDateTime end = ZonedDateTime.of(2024, 1, 15, 11, 0, 0, 0, jfk.zoneId());

        List<Flight> jfkFlights = plan.getFlightsFromAirport(jfk, start, end);
        
        assertEquals(1, jfkFlights.size());
        assertEquals(flight1, jfkFlights.get(0));
    }

    @Test
    void testGetFlightsFromAirport_NoFlightsInWindow() {
        FlightPlan plan = new FlightPlan();
        plan.addFlight(flight1);
        plan.addFlight(flight2);

        // Window before any flights
        ZonedDateTime start = ZonedDateTime.of(2024, 1, 15, 5, 0, 0, 0, jfk.zoneId());
        ZonedDateTime end = ZonedDateTime.of(2024, 1, 15, 8, 0, 0, 0, jfk.zoneId());

        List<Flight> jfkFlights = plan.getFlightsFromAirport(jfk, start, end);
        
        assertTrue(jfkFlights.isEmpty());
    }

    @Test
    void testGetFlightsFromAirport_NonExistentAirport() {
        FlightPlan plan = new FlightPlan();
        plan.addFlight(flight1);

        Airport lax = new Airport(
            "LAX",
            "Los Angeles",
            "USA",
            ZoneId.of("America/Los_Angeles"),
            600,
            33.9416,
            -118.4085,
            Continent.AMERICA
        );

        ZonedDateTime start = ZonedDateTime.of(2024, 1, 15, 0, 0, 0, 0, lax.zoneId());
        ZonedDateTime end = ZonedDateTime.of(2024, 1, 15, 23, 59, 59, 0, lax.zoneId());

        List<Flight> laxFlights = plan.getFlightsFromAirport(lax, start, end);
        
        assertTrue(laxFlights.isEmpty());
    }

    @Test
    void testGetAllFlights_ReturnsImmutableList() {
        FlightPlan plan = new FlightPlan();
        plan.addFlight(flight1);
        plan.addFlight(flight2);

        List<Flight> allFlights = plan.getAllFlights();
        
        assertEquals(2, allFlights.size());
        
        // Verify list is immutable
        assertThrows(UnsupportedOperationException.class, () -> {
            allFlights.add(flight3);
        });
    }

    @Test
    void testIndexByOrigin_MultipleFlightsFromSameAirport() {
        FlightPlan plan = new FlightPlan();
        plan.addFlight(flight1);
        plan.addFlight(flight2);
        plan.addFlight(flight3);

        ZonedDateTime start = ZonedDateTime.of(2024, 1, 15, 0, 0, 0, 0, jfk.zoneId());
        ZonedDateTime end = ZonedDateTime.of(2024, 1, 16, 0, 0, 0, 0, jfk.zoneId());

        List<Flight> jfkFlights = plan.getFlightsFromAirport(jfk, start, end);
        
        assertEquals(2, jfkFlights.size());
        assertTrue(jfkFlights.contains(flight1));
        assertTrue(jfkFlights.contains(flight2));
    }

    @Test
    void testGetFlightsFromAirport_ExactBoundaries() {
        FlightPlan plan = new FlightPlan();
        plan.addFlight(flight1);

        // Start exactly at departure time
        ZonedDateTime start = flight1.departureTime();
        ZonedDateTime end = flight1.departureTime().plusHours(1);

        List<Flight> flights = plan.getFlightsFromAirport(jfk, start, end);
        
        assertEquals(1, flights.size());
        assertEquals(flight1, flights.get(0));
    }
}
