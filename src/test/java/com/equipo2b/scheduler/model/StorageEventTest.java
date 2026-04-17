package com.equipo2b.scheduler.model;

import org.junit.jupiter.api.Test;
import java.time.ZonedDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the StorageEvent record.
 */
class StorageEventTest {

    @Test
    void testStorageEventCreation() {
        // Arrange
        Airport airport = new Airport(
            "JFK",
            "New York",
            "USA",
            ZoneId.of("America/New_York"),
            600,
            40.6413,
            -73.7781,
            Continent.AMERICA
        );
        ZonedDateTime timestamp = ZonedDateTime.now(ZoneId.of("America/New_York"));
        int quantity = 50;
        StorageEventType type = StorageEventType.ARRIVAL;

        // Act
        StorageEvent event = new StorageEvent(airport, timestamp, quantity, type);

        // Assert
        assertNotNull(event);
        assertEquals(airport, event.airport());
        assertEquals(timestamp, event.timestamp());
        assertEquals(quantity, event.quantity());
        assertEquals(type, event.type());
    }

    @Test
    void testStorageEventWithDeparture() {
        // Arrange
        Airport airport = new Airport(
            "CDG",
            "Paris",
            "France",
            ZoneId.of("Europe/Paris"),
            700,
            49.0097,
            2.5479,
            Continent.EUROPE
        );
        ZonedDateTime timestamp = ZonedDateTime.now(ZoneId.of("Europe/Paris"));
        int quantity = 100;
        StorageEventType type = StorageEventType.DEPARTURE;

        // Act
        StorageEvent event = new StorageEvent(airport, timestamp, quantity, type);

        // Assert
        assertNotNull(event);
        assertEquals(airport, event.airport());
        assertEquals(timestamp, event.timestamp());
        assertEquals(quantity, event.quantity());
        assertEquals(StorageEventType.DEPARTURE, event.type());
    }

    @Test
    void testStorageEventNullAirportThrowsException() {
        // Arrange
        ZonedDateTime timestamp = ZonedDateTime.now();
        int quantity = 50;
        StorageEventType type = StorageEventType.ARRIVAL;

        // Act & Assert
        assertThrows(NullPointerException.class, () -> {
            new StorageEvent(null, timestamp, quantity, type);
        });
    }

    @Test
    void testStorageEventNullTimestampThrowsException() {
        // Arrange
        Airport airport = new Airport(
            "JFK",
            "New York",
            "USA",
            ZoneId.of("America/New_York"),
            600,
            40.6413,
            -73.7781,
            Continent.AMERICA
        );
        int quantity = 50;
        StorageEventType type = StorageEventType.ARRIVAL;

        // Act & Assert
        assertThrows(NullPointerException.class, () -> {
            new StorageEvent(airport, null, quantity, type);
        });
    }

    @Test
    void testStorageEventNullTypeThrowsException() {
        // Arrange
        Airport airport = new Airport(
            "JFK",
            "New York",
            "USA",
            ZoneId.of("America/New_York"),
            600,
            40.6413,
            -73.7781,
            Continent.AMERICA
        );
        ZonedDateTime timestamp = ZonedDateTime.now();
        int quantity = 50;

        // Act & Assert
        assertThrows(NullPointerException.class, () -> {
            new StorageEvent(airport, timestamp, quantity, null);
        });
    }

    @Test
    void testStorageEventZeroQuantityThrowsException() {
        // Arrange
        Airport airport = new Airport(
            "JFK",
            "New York",
            "USA",
            ZoneId.of("America/New_York"),
            600,
            40.6413,
            -73.7781,
            Continent.AMERICA
        );
        ZonedDateTime timestamp = ZonedDateTime.now();
        StorageEventType type = StorageEventType.ARRIVAL;

        // Act & Assert
        assertThrows(IllegalArgumentException.class, () -> {
            new StorageEvent(airport, timestamp, 0, type);
        });
    }

    @Test
    void testStorageEventNegativeQuantityThrowsException() {
        // Arrange
        Airport airport = new Airport(
            "JFK",
            "New York",
            "USA",
            ZoneId.of("America/New_York"),
            600,
            40.6413,
            -73.7781,
            Continent.AMERICA
        );
        ZonedDateTime timestamp = ZonedDateTime.now();
        StorageEventType type = StorageEventType.ARRIVAL;

        // Act & Assert
        assertThrows(IllegalArgumentException.class, () -> {
            new StorageEvent(airport, timestamp, -10, type);
        });
    }
}
