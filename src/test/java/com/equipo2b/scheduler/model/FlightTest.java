package com.equipo2b.scheduler.model;

import org.junit.jupiter.api.Test;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Flight record.
 * Tests validations for flight type, capacity, and duration.
 */
class FlightTest {

    // Helper method to create a test airport
    private Airport createAirport(String id, Continent continent) {
        return new Airport(
            id,
            "Test City",
            "Test Country",
            ZoneId.of("UTC"),
            600,
            0.0,
            0.0,
            continent
        );
    }

    @Test
    void testValidIntraContinentalFlight() {
        Airport jfk = createAirport("JFK", Continent.AMERICA);
        Airport lax = createAirport("LAX", Continent.AMERICA);
        
        ZonedDateTime departure = ZonedDateTime.parse("2024-01-15T10:00:00Z");
        ZonedDateTime arrival = departure.plusHours(12);
        
        Flight flight = new Flight(
            "FL001",
            jfk,
            lax,
            departure,
            arrival,
            200,
            FlightType.INTRACONTINENTAL
        );
        
        assertEquals("FL001", flight.flightId());
        assertEquals(jfk, flight.origin());
        assertEquals(lax, flight.destination());
        assertEquals(200, flight.capacity());
        assertEquals(FlightType.INTRACONTINENTAL, flight.type());
    }

    @Test
    void testValidInterContinentalFlight() {
        Airport jfk = createAirport("JFK", Continent.AMERICA);
        Airport cdg = createAirport("CDG", Continent.EUROPE);
        
        ZonedDateTime departure = ZonedDateTime.parse("2024-01-15T10:00:00Z");
        ZonedDateTime arrival = departure.plusHours(24);
        
        Flight flight = new Flight(
            "FL002",
            jfk,
            cdg,
            departure,
            arrival,
            350,
            FlightType.INTERCONTINENTAL
        );
        
        assertEquals("FL002", flight.flightId());
        assertEquals(jfk, flight.origin());
        assertEquals(cdg, flight.destination());
        assertEquals(350, flight.capacity());
        assertEquals(FlightType.INTERCONTINENTAL, flight.type());
    }

    @Test
    void testFlightTypeMismatch_SameContinentButInterContinental() {
        Airport jfk = createAirport("JFK", Continent.AMERICA);
        Airport lax = createAirport("LAX", Continent.AMERICA);
        
        ZonedDateTime departure = ZonedDateTime.parse("2024-01-15T10:00:00Z");
        ZonedDateTime arrival = departure.plusHours(24);
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new Flight(
                "FL003",
                jfk,
                lax,
                departure,
                arrival,
                350,
                FlightType.INTERCONTINENTAL
            )
        );
        
        assertTrue(exception.getMessage().contains("Flight type mismatch"));
    }

    @Test
    void testFlightTypeMismatch_DifferentContinentButIntraContinental() {
        Airport jfk = createAirport("JFK", Continent.AMERICA);
        Airport cdg = createAirport("CDG", Continent.EUROPE);
        
        ZonedDateTime departure = ZonedDateTime.parse("2024-01-15T10:00:00Z");
        ZonedDateTime arrival = departure.plusHours(12);
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new Flight(
                "FL004",
                jfk,
                cdg,
                departure,
                arrival,
                200,
                FlightType.INTRACONTINENTAL
            )
        );
        
        assertTrue(exception.getMessage().contains("Flight type mismatch"));
    }

    @Test
    void testFlightCapacityZero() {
        Airport jfk = createAirport("JFK", Continent.AMERICA);
        Airport lax = createAirport("LAX", Continent.AMERICA);
        
        ZonedDateTime departure = ZonedDateTime.parse("2024-01-15T10:00:00Z");
        ZonedDateTime arrival = departure.plusHours(12);
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new Flight(
                "FL005",
                jfk,
                lax,
                departure,
                arrival,
                0,
                FlightType.INTRACONTINENTAL
            )
        );
        
        assertTrue(exception.getMessage().contains("positive"));
    }

    @Test
    void testFlightCapacityNegative() {
        Airport jfk = createAirport("JFK", Continent.AMERICA);
        Airport cdg = createAirport("CDG", Continent.EUROPE);
        
        ZonedDateTime departure = ZonedDateTime.parse("2024-01-15T10:00:00Z");
        ZonedDateTime arrival = departure.plusHours(24);
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new Flight(
                "FL006",
                jfk,
                cdg,
                departure,
                arrival,
                -10,
                FlightType.INTERCONTINENTAL
            )
        );
        
        assertTrue(exception.getMessage().contains("positive"));
    }

    @Test
    void testFlightArrivalBeforeDeparture() {
        Airport jfk = createAirport("JFK", Continent.AMERICA);
        Airport lax = createAirport("LAX", Continent.AMERICA);
        
        ZonedDateTime departure = ZonedDateTime.parse("2024-01-15T10:00:00Z");
        ZonedDateTime arrival = departure.minusHours(1); // Arrival before departure
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new Flight(
                "FL007",
                jfk,
                lax,
                departure,
                arrival,
                200,
                FlightType.INTRACONTINENTAL
            )
        );
        
        assertTrue(exception.getMessage().contains("Arrival time must be after departure"));
    }

    @Test
    void testFlightAnyCapacityAccepted() {
        // Verify that any positive capacity is accepted (no range restrictions)
        Airport jfk = createAirport("JFK", Continent.AMERICA);
        Airport lax = createAirport("LAX", Continent.AMERICA);
        
        ZonedDateTime departure = ZonedDateTime.parse("2024-01-15T10:00:00Z");
        ZonedDateTime arrival = departure.plusHours(6);
        
        // Small capacity
        Flight flight1 = new Flight("FL008", jfk, lax, departure, arrival, 1, FlightType.INTRACONTINENTAL);
        assertEquals(1, flight1.capacity());
        
        // Very large capacity
        Flight flight2 = new Flight("FL009", jfk, lax, departure, arrival, 5000, FlightType.INTRACONTINENTAL);
        assertEquals(5000, flight2.capacity());
    }

    @Test
    void testFlightAnyDurationAccepted() {
        // Verify that any duration is accepted (no 12h/24h restrictions)
        Airport jfk = createAirport("JFK", Continent.AMERICA);
        Airport lax = createAirport("LAX", Continent.AMERICA);
        
        ZonedDateTime departure = ZonedDateTime.parse("2024-01-15T10:00:00Z");
        
        // 2-hour flight (was invalid before)
        Flight flight1 = new Flight("FL010", jfk, lax, departure, departure.plusHours(2), 200, FlightType.INTRACONTINENTAL);
        assertEquals(200, flight1.capacity());
        
        // 48-hour flight
        Airport cdg = createAirport("CDG", Continent.EUROPE);
        Flight flight2 = new Flight("FL011", jfk, cdg, departure, departure.plusHours(48), 350, FlightType.INTERCONTINENTAL);
        assertEquals(350, flight2.capacity());
    }

    @Test
    void testAvailableCapacity() {
        Airport jfk = createAirport("JFK", Continent.AMERICA);
        Airport lax = createAirport("LAX", Continent.AMERICA);
        
        ZonedDateTime departure = ZonedDateTime.parse("2024-01-15T10:00:00Z");
        ZonedDateTime arrival = departure.plusHours(12);
        
        Flight flight = new Flight(
            "FL011",
            jfk,
            lax,
            departure,
            arrival,
            200,
            FlightType.INTRACONTINENTAL
        );
        
        assertEquals(200, flight.availableCapacity(0));
        assertEquals(150, flight.availableCapacity(50));
        assertEquals(0, flight.availableCapacity(200));
        assertEquals(-10, flight.availableCapacity(210));
    }

    @Test
    void testNullFlightId() {
        Airport jfk = createAirport("JFK", Continent.AMERICA);
        Airport lax = createAirport("LAX", Continent.AMERICA);
        
        ZonedDateTime departure = ZonedDateTime.parse("2024-01-15T10:00:00Z");
        ZonedDateTime arrival = departure.plusHours(12);
        
        assertThrows(
            NullPointerException.class,
            () -> new Flight(
                null,
                jfk,
                lax,
                departure,
                arrival,
                200,
                FlightType.INTRACONTINENTAL
            )
        );
    }

    @Test
    void testNullOrigin() {
        Airport lax = createAirport("LAX", Continent.AMERICA);
        
        ZonedDateTime departure = ZonedDateTime.parse("2024-01-15T10:00:00Z");
        ZonedDateTime arrival = departure.plusHours(12);
        
        assertThrows(
            NullPointerException.class,
            () -> new Flight(
                "FL012",
                null,
                lax,
                departure,
                arrival,
                200,
                FlightType.INTRACONTINENTAL
            )
        );
    }

    @Test
    void testNullDestination() {
        Airport jfk = createAirport("JFK", Continent.AMERICA);
        
        ZonedDateTime departure = ZonedDateTime.parse("2024-01-15T10:00:00Z");
        ZonedDateTime arrival = departure.plusHours(12);
        
        assertThrows(
            NullPointerException.class,
            () -> new Flight(
                "FL013",
                jfk,
                null,
                departure,
                arrival,
                200,
                FlightType.INTRACONTINENTAL
            )
        );
    }

    @Test
    void testIntraContinentalBoundaryCapacities() {
        Airport jfk = createAirport("JFK", Continent.AMERICA);
        Airport lax = createAirport("LAX", Continent.AMERICA);
        
        ZonedDateTime departure = ZonedDateTime.parse("2024-01-15T10:00:00Z");
        ZonedDateTime arrival = departure.plusHours(12);
        
        // Test minimum capacity (150)
        Flight flight1 = new Flight(
            "FL014",
            jfk,
            lax,
            departure,
            arrival,
            150,
            FlightType.INTRACONTINENTAL
        );
        assertEquals(150, flight1.capacity());
        
        // Test maximum capacity (250)
        Flight flight2 = new Flight(
            "FL015",
            jfk,
            lax,
            departure,
            arrival,
            250,
            FlightType.INTRACONTINENTAL
        );
        assertEquals(250, flight2.capacity());
    }

    @Test
    void testInterContinentalBoundaryCapacities() {
        Airport jfk = createAirport("JFK", Continent.AMERICA);
        Airport cdg = createAirport("CDG", Continent.EUROPE);
        
        ZonedDateTime departure = ZonedDateTime.parse("2024-01-15T10:00:00Z");
        ZonedDateTime arrival = departure.plusHours(24);
        
        // Test minimum capacity (150)
        Flight flight1 = new Flight(
            "FL016",
            jfk,
            cdg,
            departure,
            arrival,
            150,
            FlightType.INTERCONTINENTAL
        );
        assertEquals(150, flight1.capacity());
        
        // Test maximum capacity (400)
        Flight flight2 = new Flight(
            "FL017",
            jfk,
            cdg,
            departure,
            arrival,
            400,
            FlightType.INTERCONTINENTAL
        );
        assertEquals(400, flight2.capacity());
    }
}
