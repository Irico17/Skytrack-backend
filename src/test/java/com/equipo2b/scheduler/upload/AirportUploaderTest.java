package com.equipo2b.scheduler.upload;

import com.equipo2b.scheduler.model.Airport;
import com.equipo2b.scheduler.model.Continent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests para AirportUploader.
 * Valida la carga de aeropuertos desde archivo de texto.
 */
class AirportUploaderTest {

    @Test
    void testLoadAirports_ValidFile() throws IOException {
        AirportUploader uploader = new AirportUploader();
        List<Airport> airports = uploader.loadAirports("data/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt");
        
        assertNotNull(airports);
        assertFalse(airports.isEmpty());
        
        // Verify we loaded 30 airports (10 America + 10 Europe + 10 Asia)
        assertEquals(30, airports.size());
    }

    @Test
    void testLoadAirports_ParsesCorrectly() throws IOException {
        AirportUploader uploader = new AirportUploader();
        List<Airport> airports = uploader.loadAirports("data/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt");
        
        // Verify first airport (Bogota)
        Airport bogota = airports.get(0);
        assertEquals("SKBO", bogota.id());
        assertEquals("Bogota", bogota.city());
        assertEquals("Colombia", bogota.country());
        assertEquals(ZoneOffset.ofHours(-5), bogota.zoneId());
        assertEquals(430, bogota.storageCapacity());
        assertEquals(Continent.AMERICA, bogota.continent());
        
        // Verify latitude and longitude are parsed (approximate values)
        assertTrue(bogota.latitude() > 4.0 && bogota.latitude() < 5.0);
        assertTrue(bogota.longitude() < -74.0 && bogota.longitude() > -75.0);
    }

    @Test
    void testLoadAirports_ParsesContinentsCorrectly() throws IOException {
        AirportUploader uploader = new AirportUploader();
        List<Airport> airports = uploader.loadAirports("data/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt");
        
        // First 10 should be America
        for (int i = 0; i < 10; i++) {
            assertEquals(Continent.AMERICA, airports.get(i).continent(), 
                "Airport " + airports.get(i).id() + " should be in AMERICA");
        }
        
        // Next 10 should be Europe
        for (int i = 10; i < 20; i++) {
            assertEquals(Continent.EUROPE, airports.get(i).continent(),
                "Airport " + airports.get(i).id() + " should be in EUROPE");
        }
        
        // Last 10 should be Asia
        for (int i = 20; i < 30; i++) {
            assertEquals(Continent.ASIA, airports.get(i).continent(),
                "Airport " + airports.get(i).id() + " should be in ASIA");
        }
    }

    @Test
    void testLoadAirports_ValidatesStorageCapacity() throws IOException {
        AirportUploader uploader = new AirportUploader();
        List<Airport> airports = uploader.loadAirports("data/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt");
        
        // All airports should have positive capacity
        for (Airport airport : airports) {
            assertTrue(airport.storageCapacity() > 0,
                "Airport " + airport.id() + " has invalid capacity: " + airport.storageCapacity());
        }
    }

    @Test
    void testLoadAirports_FileNotFound() {
        AirportUploader uploader = new AirportUploader();
        
        assertThrows(IOException.class, () -> {
            uploader.loadAirports("nonexistent_file.txt");
        });
    }

    @Test
    void testLoadAirports_ConvertsGMTOffsetToZoneId() throws IOException {
        AirportUploader uploader = new AirportUploader();
        List<Airport> airports = uploader.loadAirports("data/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt");
        
        // Verify different GMT offsets are converted correctly
        Airport bogota = airports.stream()
            .filter(a -> a.id().equals("SKBO"))
            .findFirst()
            .orElseThrow();
        assertEquals(ZoneOffset.ofHours(-5), bogota.zoneId());
        
        Airport berlin = airports.stream()
            .filter(a -> a.id().equals("EDDI"))
            .findFirst()
            .orElseThrow();
        assertEquals(ZoneOffset.ofHours(2), berlin.zoneId());
        
        Airport delhi = airports.stream()
            .filter(a -> a.id().equals("VIDP"))
            .findFirst()
            .orElseThrow();
        assertEquals(ZoneOffset.ofHours(5), delhi.zoneId());
    }
}
