package com.equipo2b.scheduler.model;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ShipmentBatch record.
 * Tests validation, SLA calculation, and SLA compliance checking.
 */
class ShipmentBatchTest {

    private Airport createAirport(String id, Continent continent) {
        return new Airport(
            id,
            "City",
            "Country",
            ZoneId.of("UTC"),
            600,
            0.0,
            0.0,
            continent
        );
    }

    @Test
    void testValidShipmentBatchCreation() {
        Airport origin = createAirport("JFK", Continent.AMERICA);
        Airport destination = createAirport("LAX", Continent.AMERICA);
        ZonedDateTime ingressTime = ZonedDateTime.now();

        ShipmentBatch batch = new ShipmentBatch(
            "BATCH-001",
            "AIRPORT-BATCH-001",
            "CLIENT-001",
            origin,
            destination,
            100,
            ingressTime
        );

        assertEquals("BATCH-001", batch.batchId());
        assertEquals("AIRPORT-BATCH-001", batch.airportBatchId());
        assertEquals("CLIENT-001", batch.clientId());
        assertEquals(origin, batch.origin());
        assertEquals(destination, batch.destination());
        assertEquals(100, batch.quantity());
        assertEquals(ingressTime, batch.ingressTime());
    }

    @Test
    void testNullBatchIdThrowsException() {
        Airport origin = createAirport("JFK", Continent.AMERICA);
        Airport destination = createAirport("LAX", Continent.AMERICA);

        assertThrows(NullPointerException.class, () -> {
            new ShipmentBatch(
                null,
                "AIRPORT-BATCH-001",
                "CLIENT-001",
                origin,
                destination,
                100,
                ZonedDateTime.now()
            );
        });
    }

    @Test
    void testNullClientIdThrowsException() {
        Airport origin = createAirport("JFK", Continent.AMERICA);
        Airport destination = createAirport("LAX", Continent.AMERICA);

        assertThrows(NullPointerException.class, () -> {
            new ShipmentBatch(
                "BATCH-001",
                "AIRPORT-BATCH-001",
                null,
                origin,
                destination,
                100,
                ZonedDateTime.now()
            );
        });
    }

    @Test
    void testNullOriginThrowsException() {
        Airport destination = createAirport("LAX", Continent.AMERICA);

        assertThrows(NullPointerException.class, () -> {
            new ShipmentBatch(
                "BATCH-001",
                "AIRPORT-BATCH-001",
                "CLIENT-001",
                null,
                destination,
                100,
                ZonedDateTime.now()
            );
        });
    }

    @Test
    void testNullDestinationThrowsException() {
        Airport origin = createAirport("JFK", Continent.AMERICA);

        assertThrows(NullPointerException.class, () -> {
            new ShipmentBatch(
                "BATCH-001",
                "AIRPORT-BATCH-001",
                "CLIENT-001",
                origin,
                null,
                100,
                ZonedDateTime.now()
            );
        });
    }

    @Test
    void testNullIngressTimeThrowsException() {
        Airport origin = createAirport("JFK", Continent.AMERICA);
        Airport destination = createAirport("LAX", Continent.AMERICA);

        assertThrows(NullPointerException.class, () -> {
            new ShipmentBatch(
                "BATCH-001",
                "AIRPORT-BATCH-001",
                "CLIENT-001",
                origin,
                destination,
                100,
                null
            );
        });
    }

    @Test
    void testZeroQuantityThrowsException() {
        Airport origin = createAirport("JFK", Continent.AMERICA);
        Airport destination = createAirport("LAX", Continent.AMERICA);

        assertThrows(IllegalArgumentException.class, () -> {
            new ShipmentBatch(
                "BATCH-001",
                "AIRPORT-BATCH-001",
                "CLIENT-001",
                origin,
                destination,
                0,
                ZonedDateTime.now()
            );
        });
    }

    @Test
    void testNegativeQuantityThrowsException() {
        Airport origin = createAirport("JFK", Continent.AMERICA);
        Airport destination = createAirport("LAX", Continent.AMERICA);

        assertThrows(IllegalArgumentException.class, () -> {
            new ShipmentBatch(
                "BATCH-001",
                "AIRPORT-BATCH-001",
                "CLIENT-001",
                origin,
                destination,
                -10,
                ZonedDateTime.now()
            );
        });
    }

    @Test
    void testCalculateSLA_SameContinent_Returns24Hours() {
        Airport origin = createAirport("JFK", Continent.AMERICA);
        Airport destination = createAirport("LAX", Continent.AMERICA);

        ShipmentBatch batch = new ShipmentBatch(
            "BATCH-001",
            "AIRPORT-BATCH-001",
            "CLIENT-001",
            origin,
            destination,
            100,
            ZonedDateTime.now()
        );

        Duration sla = batch.calculateSLA();
        assertEquals(Duration.ofHours(24), sla);
    }

    @Test
    void testCalculateSLA_DifferentContinents_Returns48Hours() {
        Airport origin = createAirport("JFK", Continent.AMERICA);
        Airport destination = createAirport("CDG", Continent.EUROPE);

        ShipmentBatch batch = new ShipmentBatch(
            "BATCH-001",
            "AIRPORT-BATCH-001",
            "CLIENT-001",
            origin,
            destination,
            100,
            ZonedDateTime.now()
        );

        Duration sla = batch.calculateSLA();
        assertEquals(Duration.ofHours(48), sla);
    }

    @Test
    void testMeetsSLA_WithinSLA_ReturnsTrue() {
        Airport origin = createAirport("JFK", Continent.AMERICA);
        Airport destination = createAirport("LAX", Continent.AMERICA);
        ZonedDateTime ingressTime = ZonedDateTime.now();

        ShipmentBatch batch = new ShipmentBatch(
            "BATCH-001",
            "AIRPORT-BATCH-001",
            "CLIENT-001",
            origin,
            destination,
            100,
            ingressTime
        );

        // Arrival 20 hours after ingress (within 24h SLA)
        ZonedDateTime arrivalTime = ingressTime.plusHours(20);
        assertTrue(batch.meetsSLA(arrivalTime));
    }

    @Test
    void testMeetsSLA_ExactlySLA_ReturnsTrue() {
        Airport origin = createAirport("JFK", Continent.AMERICA);
        Airport destination = createAirport("LAX", Continent.AMERICA);
        ZonedDateTime ingressTime = ZonedDateTime.now();

        ShipmentBatch batch = new ShipmentBatch(
            "BATCH-001",
            "AIRPORT-BATCH-001",
            "CLIENT-001",
            origin,
            destination,
            100,
            ingressTime
        );

        // Arrival exactly 24 hours after ingress
        ZonedDateTime arrivalTime = ingressTime.plusHours(24);
        assertTrue(batch.meetsSLA(arrivalTime));
    }

    @Test
    void testMeetsSLA_ExceedsSLA_ReturnsFalse() {
        Airport origin = createAirport("JFK", Continent.AMERICA);
        Airport destination = createAirport("LAX", Continent.AMERICA);
        ZonedDateTime ingressTime = ZonedDateTime.now();

        ShipmentBatch batch = new ShipmentBatch(
            "BATCH-001",
            "AIRPORT-BATCH-001",
            "CLIENT-001",
            origin,
            destination,
            100,
            ingressTime
        );

        // Arrival 25 hours after ingress (exceeds 24h SLA)
        ZonedDateTime arrivalTime = ingressTime.plusHours(25);
        assertFalse(batch.meetsSLA(arrivalTime));
    }

    @Test
    void testMeetsSLA_IntercontinentalWithinSLA_ReturnsTrue() {
        Airport origin = createAirport("JFK", Continent.AMERICA);
        Airport destination = createAirport("CDG", Continent.EUROPE);
        ZonedDateTime ingressTime = ZonedDateTime.now();

        ShipmentBatch batch = new ShipmentBatch(
            "BATCH-001",
            "AIRPORT-BATCH-001",
            "CLIENT-001",
            origin,
            destination,
            100,
            ingressTime
        );

        // Arrival 40 hours after ingress (within 48h SLA)
        ZonedDateTime arrivalTime = ingressTime.plusHours(40);
        assertTrue(batch.meetsSLA(arrivalTime));
    }

    @Test
    void testMeetsSLA_IntercontinentalExceedsSLA_ReturnsFalse() {
        Airport origin = createAirport("JFK", Continent.AMERICA);
        Airport destination = createAirport("CDG", Continent.EUROPE);
        ZonedDateTime ingressTime = ZonedDateTime.now();

        ShipmentBatch batch = new ShipmentBatch(
            "BATCH-001",
            "AIRPORT-BATCH-001",
            "CLIENT-001",
            origin,
            destination,
            100,
            ingressTime
        );

        // Arrival 50 hours after ingress (exceeds 48h SLA)
        ZonedDateTime arrivalTime = ingressTime.plusHours(50);
        assertFalse(batch.meetsSLA(arrivalTime));
    }
}
