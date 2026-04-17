package com.equipo2b.scheduler.upload;

import com.equipo2b.scheduler.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests para FlightPlanUploader.
 * 
 * Valida:
 * - Parseo correcto del formato de vuelos
 * - Construcción de ZonedDateTime con husos horarios correctos
 * - Determinación automática del tipo de vuelo según continentes
 * - Validación de formato y reporte de errores con número de línea
 */
class FlightPlanUploaderTest {
    
    private FlightPlanUploader uploader;
    private AirportManager airportManager;
    
    @TempDir
    Path tempDir;
    
    @BeforeEach
    void setUp() {
        uploader = new FlightPlanUploader();
        
        // Crear aeropuertos de prueba
        Airport skbo = new Airport("SKBO", "Bogotá", "Colombia", 
            ZoneId.of("America/Bogota"), 600, 4.7, -74.1, Continent.AMERICA);
        Airport seqm = new Airport("SEQM", "Quito", "Ecuador", 
            ZoneId.of("America/Guayaquil"), 550, -0.1, -78.4, Continent.AMERICA);
        Airport eddi = new Airport("EDDI", "Berlin", "Germany", 
            ZoneId.of("Europe/Berlin"), 700, 52.5, 13.4, Continent.EUROPE);
        Airport vidp = new Airport("VIDP", "Delhi", "India", 
            ZoneId.of("Asia/Kolkata"), 650, 28.6, 77.2, Continent.ASIA);
        
        airportManager = new AirportManager();
        airportManager.addAirport(skbo);
        airportManager.addAirport(seqm);
        airportManager.addAirport(eddi);
        airportManager.addAirport(vidp);
    }
    
    @Test
    void testLoadFlights_ValidIntracontinentalFlight() throws IOException {
        // Arrange
        Path testFile = tempDir.resolve("flights.txt");
        Files.writeString(testFile, "SKBO-SEQM-03:34-15:34-200\n");
        
        // Act
        FlightPlan flightPlan = uploader.loadFlights(testFile.toString(), airportManager);
        
        // Assert
        assertNotNull(flightPlan);
        assertEquals(1, flightPlan.getTotalFlights());
        
        Flight flight = flightPlan.getAllFlights().get(0);
        assertEquals("SKBO-SEQM-03:34", flight.flightId());
        assertEquals("SKBO", flight.origin().id());
        assertEquals("SEQM", flight.destination().id());
        assertEquals(200, flight.capacity());
        assertEquals(FlightType.INTRACONTINENTAL, flight.type());
        
        // Verificar que los tiempos usan el huso horario correcto
        assertEquals(ZoneId.of("America/Bogota"), flight.departureTime().getZone());
        assertEquals(ZoneId.of("America/Guayaquil"), flight.arrivalTime().getZone());
    }
    
    @Test
    void testLoadFlights_ValidIntercontinentalFlight() throws IOException {
        // Arrange
        Path testFile = tempDir.resolve("flights.txt");
        Files.writeString(testFile, "SKBO-EDDI-02:00-02:00-300\n");
        
        // Act
        FlightPlan flightPlan = uploader.loadFlights(testFile.toString(), airportManager);
        
        // Assert
        assertEquals(1, flightPlan.getTotalFlights());
        
        Flight flight = flightPlan.getAllFlights().get(0);
        assertEquals(FlightType.INTERCONTINENTAL, flight.type());
        assertEquals(300, flight.capacity());
    }
    
    @Test
    void testLoadFlights_MultipleFlights() throws IOException {
        // Arrange
        Path testFile = tempDir.resolve("flights.txt");
        Files.writeString(testFile, 
            "SKBO-SEQM-03:34-15:34-200\n" +
            "SEQM-SKBO-04:29-16:29-220\n" +
            "SKBO-EDDI-02:00-02:00-350\n"
        );
        
        // Act
        FlightPlan flightPlan = uploader.loadFlights(testFile.toString(), airportManager);
        
        // Assert
        assertEquals(3, flightPlan.getTotalFlights());
    }
    
    @Test
    void testLoadFlights_IgnoresBlankLines() throws IOException {
        // Arrange
        Path testFile = tempDir.resolve("flights.txt");
        Files.writeString(testFile, 
            "SKBO-SEQM-03:34-15:34-200\n" +
            "\n" +
            "SEQM-SKBO-04:29-16:29-220\n" +
            "   \n"
        );
        
        // Act
        FlightPlan flightPlan = uploader.loadFlights(testFile.toString(), airportManager);
        
        // Assert
        assertEquals(2, flightPlan.getTotalFlights());
    }
    
    @Test
    void testLoadFlights_InvalidFormat_TooFewParts() throws IOException {
        // Arrange
        Path testFile = tempDir.resolve("flights.txt");
        Files.writeString(testFile, "SKBO-SEQM-03:34-200\n");
        
        // Act & Assert
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> uploader.loadFlights(testFile.toString(), airportManager)
        );
        
        assertTrue(exception.getMessage().contains("línea 1"));
        assertTrue(exception.getMessage().contains("Formato inválido"));
    }
    
    @Test
    void testLoadFlights_InvalidFormat_UnknownOriginAirport() throws IOException {
        // Arrange
        Path testFile = tempDir.resolve("flights.txt");
        Files.writeString(testFile, "XXXX-SEQM-03:34-15:34-200\n");
        
        // Act & Assert
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> uploader.loadFlights(testFile.toString(), airportManager)
        );
        
        assertTrue(exception.getMessage().contains("línea 1"));
        assertTrue(exception.getMessage().contains("Aeropuerto origen 'XXXX' no encontrado"));
    }
    
    @Test
    void testLoadFlights_InvalidFormat_UnknownDestinationAirport() throws IOException {
        // Arrange
        Path testFile = tempDir.resolve("flights.txt");
        Files.writeString(testFile, "SKBO-XXXX-03:34-15:34-200\n");
        
        // Act & Assert
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> uploader.loadFlights(testFile.toString(), airportManager)
        );
        
        assertTrue(exception.getMessage().contains("línea 1"));
        assertTrue(exception.getMessage().contains("Aeropuerto destino 'XXXX' no encontrado"));
    }
    
    @Test
    void testLoadFlights_InvalidFormat_InvalidDepartureTime() throws IOException {
        // Arrange
        Path testFile = tempDir.resolve("flights.txt");
        Files.writeString(testFile, "SKBO-SEQM-25:99-15:34-200\n");
        
        // Act & Assert
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> uploader.loadFlights(testFile.toString(), airportManager)
        );
        
        assertTrue(exception.getMessage().contains("línea 1"));
        assertTrue(exception.getMessage().contains("Hora de salida inválida"));
    }
    
    @Test
    void testLoadFlights_InvalidFormat_InvalidArrivalTime() throws IOException {
        // Arrange
        Path testFile = tempDir.resolve("flights.txt");
        Files.writeString(testFile, "SKBO-SEQM-03:34-99:99-200\n");
        
        // Act & Assert
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> uploader.loadFlights(testFile.toString(), airportManager)
        );
        
        assertTrue(exception.getMessage().contains("línea 1"));
        assertTrue(exception.getMessage().contains("Hora de llegada inválida"));
    }
    
    @Test
    void testLoadFlights_InvalidFormat_InvalidCapacity() throws IOException {
        // Arrange
        Path testFile = tempDir.resolve("flights.txt");
        Files.writeString(testFile, "SKBO-SEQM-03:34-15:34-ABC\n");
        
        // Act & Assert
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> uploader.loadFlights(testFile.toString(), airportManager)
        );
        
        assertTrue(exception.getMessage().contains("línea 1"));
        assertTrue(exception.getMessage().contains("Capacidad inválida"));
    }
    
    @Test
    void testLoadFlights_ErrorOnLine3() throws IOException {
        // Arrange
        Path testFile = tempDir.resolve("flights.txt");
        Files.writeString(testFile, 
            "SKBO-SEQM-03:34-15:34-200\n" +
            "SEQM-SKBO-04:29-16:29-220\n" +
            "SKBO-XXXX-02:00-02:00-350\n"
        );
        
        // Act & Assert
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> uploader.loadFlights(testFile.toString(), airportManager)
        );
        
        assertTrue(exception.getMessage().contains("línea 3"));
    }
    
    @Test
    void testLoadFlights_FlightCrossingMidnight() throws IOException {
        // Arrange - vuelo que sale a las 23:00 y llega a las 01:00 (cruza medianoche)
        Path testFile = tempDir.resolve("flights.txt");
        Files.writeString(testFile, "SKBO-SEQM-23:00-11:00-200\n");
        
        // Act
        FlightPlan flightPlan = uploader.loadFlights(testFile.toString(), airportManager);
        
        // Assert
        assertEquals(1, flightPlan.getTotalFlights());
        Flight flight = flightPlan.getAllFlights().get(0);
        
        // La llegada debe ser al día siguiente
        assertTrue(flight.arrivalTime().isAfter(flight.departureTime()));
    }
}
