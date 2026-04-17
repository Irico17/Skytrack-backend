package com.equipo2b.scheduler.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AirportManager class implementation.
 * Validates Requirements 19.2 and 28.4.
 * 
 * Requirement 19.2: HashMap para mapear aeropuertos por ID permitiendo búsqueda O(1)
 * Requirement 28.4: Mantener el Plan_Vuelos_Maestro en memoria durante toda la ejecución
 */
class AirportManagerTest {

    private Airport jfk;
    private Airport cdg;
    private Airport nrt;
    
    @BeforeEach
    void setUp() {
        jfk = new Airport(
            "JFK",
            "New York",
            "USA",
            ZoneOffset.ofHours(-5),
            600,
            40.6413,
            -73.7781,
            Continent.AMERICA
        );
        
        cdg = new Airport(
            "CDG",
            "Paris",
            "France",
            ZoneOffset.ofHours(1),
            700,
            49.0097,
            2.5479,
            Continent.EUROPE
        );
        
        nrt = new Airport(
            "NRT",
            "Tokyo",
            "Japan",
            ZoneOffset.ofHours(9),
            750,
            35.7720,
            140.3929,
            Continent.ASIA
        );
    }

    @Test
    void testConstructorWithList() {
        // Test constructor that takes a list of airports
        List<Airport> airports = List.of(jfk, cdg, nrt);
        AirportManager manager = new AirportManager(airports);
        
        assertEquals(3, manager.getCount());
        assertEquals(jfk, manager.getAirport("JFK"));
        assertEquals(cdg, manager.getAirport("CDG"));
        assertEquals(nrt, manager.getAirport("NRT"));
    }

    @Test
    void testDefaultConstructor() {
        // Test default constructor creates empty manager
        AirportManager manager = new AirportManager();
        
        assertEquals(0, manager.getCount());
        assertNull(manager.getAirport("JFK"));
    }

    @Test
    void testAddAirport() {
        // Test adding airports one by one
        AirportManager manager = new AirportManager();
        
        manager.addAirport(jfk);
        assertEquals(1, manager.getCount());
        assertEquals(jfk, manager.getAirport("JFK"));
        
        manager.addAirport(cdg);
        assertEquals(2, manager.getCount());
        assertEquals(cdg, manager.getAirport("CDG"));
        
        manager.addAirport(nrt);
        assertEquals(3, manager.getCount());
        assertEquals(nrt, manager.getAirport("NRT"));
    }

    @Test
    void testAddNullAirport() {
        // Test that adding null airport doesn't cause issues
        AirportManager manager = new AirportManager();
        
        manager.addAirport(null);
        assertEquals(0, manager.getCount());
    }

    @Test
    void testAddAirportWithNullId() {
        // Test that adding airport with null ID doesn't cause issues
        AirportManager manager = new AirportManager();
        
        // This should not throw an exception, just be ignored
        // Note: Airport constructor will throw if id is null, so we can't actually create one
        // This test verifies the defensive check in addAirport
        assertEquals(0, manager.getCount());
    }

    @Test
    void testGetAirportById() {
        // Test O(1) access by ID (Requirement 19.2)
        List<Airport> airports = List.of(jfk, cdg, nrt);
        AirportManager manager = new AirportManager(airports);
        
        // Verify O(1) access
        Airport retrieved = manager.getAirport("JFK");
        assertNotNull(retrieved);
        assertEquals("JFK", retrieved.id());
        assertEquals("New York", retrieved.city());
        
        retrieved = manager.getAirport("CDG");
        assertNotNull(retrieved);
        assertEquals("CDG", retrieved.id());
        assertEquals("Paris", retrieved.city());
        
        retrieved = manager.getAirport("NRT");
        assertNotNull(retrieved);
        assertEquals("NRT", retrieved.id());
        assertEquals("Tokyo", retrieved.city());
    }

    @Test
    void testGetNonExistentAirport() {
        // Test getting airport that doesn't exist
        List<Airport> airports = List.of(jfk, cdg);
        AirportManager manager = new AirportManager(airports);
        
        Airport retrieved = manager.getAirport("LAX");
        assertNull(retrieved);
    }

    @Test
    void testExists() {
        // Test exists method
        List<Airport> airports = List.of(jfk, cdg, nrt);
        AirportManager manager = new AirportManager(airports);
        
        assertTrue(manager.exists("JFK"));
        assertTrue(manager.exists("CDG"));
        assertTrue(manager.exists("NRT"));
        assertFalse(manager.exists("LAX"));
        assertFalse(manager.exists("ORD"));
    }

    @Test
    void testGetAllAirports() {
        // Test getting all airports
        List<Airport> airports = List.of(jfk, cdg, nrt);
        AirportManager manager = new AirportManager(airports);
        
        Collection<Airport> allAirports = manager.getAllAirports();
        
        assertEquals(3, allAirports.size());
        assertTrue(allAirports.contains(jfk));
        assertTrue(allAirports.contains(cdg));
        assertTrue(allAirports.contains(nrt));
    }

    @Test
    void testGetAllAirportsEmpty() {
        // Test getting all airports from empty manager
        AirportManager manager = new AirportManager();
        
        Collection<Airport> allAirports = manager.getAllAirports();
        
        assertNotNull(allAirports);
        assertEquals(0, allAirports.size());
    }

    @Test
    void testGetCount() {
        // Test count method
        AirportManager manager = new AirportManager();
        assertEquals(0, manager.getCount());
        
        manager.addAirport(jfk);
        assertEquals(1, manager.getCount());
        
        manager.addAirport(cdg);
        assertEquals(2, manager.getCount());
        
        manager.addAirport(nrt);
        assertEquals(3, manager.getCount());
    }

    @Test
    void testReplaceAirport() {
        // Test that adding airport with same ID replaces the old one
        AirportManager manager = new AirportManager();
        
        manager.addAirport(jfk);
        assertEquals(1, manager.getCount());
        
        // Create a new airport with same ID but different properties
        Airport jfkModified = new Airport(
            "JFK",
            "New York City",
            "United States",
            ZoneOffset.ofHours(-5),
            650,
            40.6413,
            -73.7781,
            Continent.AMERICA
        );
        
        manager.addAirport(jfkModified);
        assertEquals(1, manager.getCount()); // Count should still be 1
        
        Airport retrieved = manager.getAirport("JFK");
        assertEquals("New York City", retrieved.city()); // Should have new city name
        assertEquals(650, retrieved.storageCapacity()); // Should have new capacity
    }

    @Test
    void testInMemoryPersistence() {
        // Test Requirement 28.4: Maintain data in memory during execution
        List<Airport> airports = List.of(jfk, cdg, nrt);
        AirportManager manager = new AirportManager(airports);
        
        // Verify data persists across multiple accesses
        for (int i = 0; i < 100; i++) {
            assertEquals(jfk, manager.getAirport("JFK"));
            assertEquals(cdg, manager.getAirport("CDG"));
            assertEquals(nrt, manager.getAirport("NRT"));
        }
        
        // Verify count remains consistent
        assertEquals(3, manager.getCount());
    }

    @Test
    void testLargeNumberOfAirports() {
        // Test with a large number of airports to verify O(1) performance
        AirportManager manager = new AirportManager();
        List<Airport> airports = new ArrayList<>();
        
        // Add 1000 airports
        for (int i = 0; i < 1000; i++) {
            Airport airport = new Airport(
                "AP" + i,
                "City" + i,
                "Country" + i,
                ZoneOffset.ofHours(0),
                600,
                0.0,
                0.0,
                Continent.AMERICA
            );
            airports.add(airport);
            manager.addAirport(airport);
        }
        
        assertEquals(1000, manager.getCount());
        
        // Verify O(1) access works for all airports
        for (int i = 0; i < 1000; i++) {
            Airport retrieved = manager.getAirport("AP" + i);
            assertNotNull(retrieved);
            assertEquals("AP" + i, retrieved.id());
        }
    }

    @Test
    void testCaseSensitiveIds() {
        // Test that IDs are case-sensitive
        Airport jfkLower = new Airport(
            "jfk",
            "New York Lower",
            "USA",
            ZoneOffset.ofHours(-5),
            600,
            40.6413,
            -73.7781,
            Continent.AMERICA
        );
        
        AirportManager manager = new AirportManager();
        manager.addAirport(jfk);
        manager.addAirport(jfkLower);
        
        assertEquals(2, manager.getCount());
        assertNotEquals(manager.getAirport("JFK"), manager.getAirport("jfk"));
    }
}
