package com.equipo2b.scheduler.model;

import org.junit.jupiter.api.Test;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Airport record implementation.
 * Validates Requirements 1.1 and 3.1.
 */
class AirportTest {

    @Test
    void testAirportCreationWithValidCapacity() {
        // Test with minimum valid capacity (500)
        Airport airport1 = new Airport(
            "JFK",
            "New York",
            "USA",
            ZoneOffset.ofHours(-5),
            500,
            40.6413,
            -73.7781,
            Continent.AMERICA
        );
        
        assertEquals("JFK", airport1.id());
        assertEquals("New York", airport1.city());
        assertEquals("USA", airport1.country());
        assertEquals(500, airport1.storageCapacity());
        assertEquals(Continent.AMERICA, airport1.continent());
        
        // Test with maximum valid capacity (800)
        Airport airport2 = new Airport(
            "CDG",
            "Paris",
            "France",
            ZoneOffset.ofHours(1),
            800,
            49.0097,
            2.5479,
            Continent.EUROPE
        );
        
        assertEquals(800, airport2.storageCapacity());
    }

    @Test
    void testAirportCreationWithInvalidCapacityZero() {
        // Test with capacity of 0 (invalid)
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new Airport(
                "JFK",
                "New York",
                "USA",
                ZoneOffset.ofHours(-5),
                0,
                40.6413,
                -73.7781,
                Continent.AMERICA
            )
        );
        
        assertTrue(exception.getMessage().contains("positive"));
    }

    @Test
    void testAirportCreationWithInvalidCapacityNegative() {
        // Test with negative capacity
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new Airport(
                "JFK",
                "New York",
                "USA",
                ZoneOffset.ofHours(-5),
                -1,
                40.6413,
                -73.7781,
                Continent.AMERICA
            )
        );
        
        assertTrue(exception.getMessage().contains("positive"));
    }

    @Test
    void testAirportCreationWithNullId() {
        NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> new Airport(
                null,
                "New York",
                "USA",
                ZoneOffset.ofHours(-5),
                500,
                40.6413,
                -73.7781,
                Continent.AMERICA
            )
        );
        
        assertEquals("Airport ID cannot be null", exception.getMessage());
    }

    @Test
    void testAirportCreationWithNullZoneId() {
        NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> new Airport(
                "JFK",
                "New York",
                "USA",
                null,
                500,
                40.6413,
                -73.7781,
                Continent.AMERICA
            )
        );
        
        assertEquals("ZoneId cannot be null", exception.getMessage());
    }

    @Test
    void testAirportCreationWithNullContinent() {
        NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> new Airport(
                "JFK",
                "New York",
                "USA",
                ZoneOffset.ofHours(-5),
                500,
                40.6413,
                -73.7781,
                null
            )
        );
        
        assertEquals("Continent cannot be null", exception.getMessage());
    }

    @Test
    void testAirportImmutability() {
        // Records are immutable by default
        Airport airport = new Airport(
            "JFK",
            "New York",
            "USA",
            ZoneOffset.ofHours(-5),
            500,
            40.6413,
            -73.7781,
            Continent.AMERICA
        );
        
        // Verify all fields are accessible
        assertNotNull(airport.id());
        assertNotNull(airport.city());
        assertNotNull(airport.country());
        assertNotNull(airport.zoneId());
        assertTrue(airport.storageCapacity() > 0);
        assertNotNull(airport.continent());
    }

    @Test
    void testAirportEquality() {
        // Records automatically implement equals() based on all fields
        Airport airport1 = new Airport(
            "JFK",
            "New York",
            "USA",
            ZoneOffset.ofHours(-5),
            500,
            40.6413,
            -73.7781,
            Continent.AMERICA
        );
        
        Airport airport2 = new Airport(
            "JFK",
            "New York",
            "USA",
            ZoneOffset.ofHours(-5),
            500,
            40.6413,
            -73.7781,
            Continent.AMERICA
        );
        
        assertEquals(airport1, airport2);
        assertEquals(airport1.hashCode(), airport2.hashCode());
    }
}
