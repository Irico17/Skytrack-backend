package com.equipo2b.scheduler.monitoring;

import com.equipo2b.scheduler.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests para TrafficLightIndicator.
 * 
 * Verifica:
 * - Creación con umbrales parametrizables
 * - Evaluación de colores según ocupación
 * - Generación de reportes completos
 * - Validación de umbrales
 */
class TrafficLightIndicatorTest {
    
    private TrafficLightIndicator indicator;
    private FlightPlan flightPlan;
    private AirportManager airportManager;
    private CapacityMonitor monitor;
    
    private Airport jfk;
    private Airport lhr;
    private Airport cdg;
    
    @BeforeEach
    void setUp() {
        // Crear indicador con umbrales por defecto (50%, 70%)
        indicator = new TrafficLightIndicator();
        
        // Crear aeropuertos
        jfk = new Airport("JFK", "New York", "USA", 
                         ZoneId.of("America/New_York"), 600, 
                         40.6413, -73.7781, Continent.AMERICA);
        lhr = new Airport("LHR", "London", "UK", 
                         ZoneId.of("Europe/London"), 700, 
                         51.4700, -0.4543, Continent.EUROPE);
        cdg = new Airport("CDG", "Paris", "France", 
                         ZoneId.of("Europe/Paris"), 650, 
                         49.0097, 2.5479, Continent.EUROPE);
        
        List<Airport> airports = List.of(jfk, lhr, cdg);
        airportManager = new AirportManager(airports);
        
        // Crear plan de vuelos
        flightPlan = new FlightPlan();
        
        // Crear monitor
        monitor = new CapacityMonitor(flightPlan, airportManager);
    }
    
    // ========== Tests de Construcción ==========
    
    @Test
    void testConstructorWithDefaultThresholds() {
        TrafficLightIndicator defaultIndicator = new TrafficLightIndicator();
        assertEquals(0.5, defaultIndicator.getGreenThreshold(), 0.001);
        assertEquals(0.7, defaultIndicator.getAmberThreshold(), 0.001);
    }
    
    @Test
    void testConstructorWithCustomThresholds() {
        TrafficLightIndicator customIndicator = new TrafficLightIndicator(0.4, 0.8);
        assertEquals(0.4, customIndicator.getGreenThreshold(), 0.001);
        assertEquals(0.8, customIndicator.getAmberThreshold(), 0.001);
    }
    
    @Test
    void testConstructorRejectsInvalidThresholds() {
        // Green >= Amber
        assertThrows(IllegalArgumentException.class, 
            () -> new TrafficLightIndicator(0.7, 0.5));
        
        // Green == Amber
        assertThrows(IllegalArgumentException.class, 
            () -> new TrafficLightIndicator(0.6, 0.6));
        
        // Green < 0
        assertThrows(IllegalArgumentException.class, 
            () -> new TrafficLightIndicator(-0.1, 0.7));
        
        // Green > 1
        assertThrows(IllegalArgumentException.class, 
            () -> new TrafficLightIndicator(1.1, 1.5));
        
        // Amber < 0
        assertThrows(IllegalArgumentException.class, 
            () -> new TrafficLightIndicator(0.3, -0.1));
        
        // Amber > 1
        assertThrows(IllegalArgumentException.class, 
            () -> new TrafficLightIndicator(0.3, 1.5));
    }
    
    // ========== Tests de Evaluación de Color ==========
    
    @Test
    void testEvaluateGreen() {
        // Ocupación <= 50% debe ser verde
        assertEquals(TrafficLightColor.GREEN, indicator.evaluate(0.0));
        assertEquals(TrafficLightColor.GREEN, indicator.evaluate(0.25));
        assertEquals(TrafficLightColor.GREEN, indicator.evaluate(0.5));
    }
    
    @Test
    void testEvaluateAmber() {
        // Ocupación entre 50% y 70% debe ser ámbar
        assertEquals(TrafficLightColor.AMBER, indicator.evaluate(0.51));
        assertEquals(TrafficLightColor.AMBER, indicator.evaluate(0.6));
        assertEquals(TrafficLightColor.AMBER, indicator.evaluate(0.7));
    }
    
    @Test
    void testEvaluateRed() {
        // Ocupación > 70% debe ser rojo
        assertEquals(TrafficLightColor.RED, indicator.evaluate(0.71));
        assertEquals(TrafficLightColor.RED, indicator.evaluate(0.85));
        assertEquals(TrafficLightColor.RED, indicator.evaluate(1.0));
    }
    
    @Test
    void testEvaluateWithCustomThresholds() {
        TrafficLightIndicator customIndicator = new TrafficLightIndicator(0.3, 0.6);
        
        assertEquals(TrafficLightColor.GREEN, customIndicator.evaluate(0.3));
        assertEquals(TrafficLightColor.AMBER, customIndicator.evaluate(0.45));
        assertEquals(TrafficLightColor.RED, customIndicator.evaluate(0.75));
    }
    
    // ========== Tests de Generación de Reportes ==========
    
    @Test
    void testGenerateReportAllGreen() {
        // Crear vuelos con baja ocupación
        ZonedDateTime now = ZonedDateTime.now(jfk.zoneId());
        
        Flight flight1 = new Flight("FL001", jfk, lhr, 
            now, now.plusHours(24), 200, FlightType.INTERCONTINENTAL);
        Flight flight2 = new Flight("FL002", lhr, cdg, 
            now.plusHours(25), now.plusHours(37), 200, FlightType.INTRACONTINENTAL);
        
        flightPlan.addFlight(flight1);
        flightPlan.addFlight(flight2);
        
        // Crear lotes pequeños que cumplen SLA
        ShipmentBatch batch1 = new ShipmentBatch("B001", "A001", "C001", 
            jfk, lhr, 50, now); // 50 maletas de 200 = 25%
        ShipmentBatch batch2 = new ShipmentBatch("B002", "A002", "C002", 
            lhr, cdg, 60, now.plusHours(25)); // 60 maletas de 200 = 30%
        
        // Crear rutas
        AssignedRoute route1 = new AssignedRoute(batch1, List.of(flight1));
        AssignedRoute route2 = new AssignedRoute(batch2, List.of(flight2));
        
        Solution solution = new Solution();
        solution.addRoute(route1);
        solution.addRoute(route2);
        
        TrafficLightReport report = indicator.generateReport(solution, monitor);
        
        assertEquals(TrafficLightColor.GREEN, report.flightColor());
        assertEquals(TrafficLightColor.GREEN, report.storageColor());
        assertEquals(TrafficLightColor.GREEN, report.slaColor());
        assertEquals(TrafficLightColor.GREEN, report.overallColor());
        assertTrue(report.flightOccupancy() < 0.5);
        assertEquals(1.0, report.slaCompliance(), 0.001);
    }
    
    @Test
    void testGenerateReportHighOccupancy() {
        // Crear vuelos con alta ocupación
        ZonedDateTime now = ZonedDateTime.now(jfk.zoneId());
        
        Flight flight1 = new Flight("FL001", jfk, lhr, 
            now, now.plusHours(24), 200, FlightType.INTERCONTINENTAL);
        
        flightPlan.addFlight(flight1);
        
        // Crear lote grande (alta ocupación)
        ShipmentBatch batch1 = new ShipmentBatch("B001", "A001", "C001", 
            jfk, lhr, 180, now); // 180 maletas de 200 = 90%
        
        AssignedRoute route1 = new AssignedRoute(batch1, List.of(flight1));
        
        Solution solution = new Solution();
        solution.addRoute(route1);
        
        TrafficLightReport report = indicator.generateReport(solution, monitor);
        
        assertEquals(TrafficLightColor.RED, report.flightColor());
        assertTrue(report.flightOccupancy() > 0.7);
    }
    
    @Test
    void testGenerateReportEmptySolution() {
        Solution solution = new Solution();
        
        TrafficLightReport report = indicator.generateReport(solution, monitor);
        
        assertEquals(TrafficLightColor.GREEN, report.flightColor());
        assertEquals(TrafficLightColor.GREEN, report.storageColor());
        assertEquals(TrafficLightColor.GREEN, report.slaColor());
        assertEquals(1.0, report.slaCompliance(), 0.001);
    }
    
    // ========== Tests de TrafficLightReport ==========
    
    @Test
    void testReportOverallColorCalculation() {
        // RED tiene prioridad
        TrafficLightReport report1 = new TrafficLightReport(
            TrafficLightColor.GREEN, TrafficLightColor.AMBER, TrafficLightColor.RED,
            0.3, 0.6, 0.5
        );
        assertEquals(TrafficLightColor.RED, report1.overallColor());
        
        // AMBER tiene prioridad sobre GREEN
        TrafficLightReport report2 = new TrafficLightReport(
            TrafficLightColor.GREEN, TrafficLightColor.AMBER, TrafficLightColor.GREEN,
            0.3, 0.6, 0.9
        );
        assertEquals(TrafficLightColor.AMBER, report2.overallColor());
        
        // Todo GREEN
        TrafficLightReport report3 = new TrafficLightReport(
            TrafficLightColor.GREEN, TrafficLightColor.GREEN, TrafficLightColor.GREEN,
            0.3, 0.4, 0.95
        );
        assertEquals(TrafficLightColor.GREEN, report3.overallColor());
    }
    
    @Test
    void testReportMessageGeneration() {
        TrafficLightReport report = new TrafficLightReport(
            TrafficLightColor.RED, TrafficLightColor.AMBER, TrafficLightColor.GREEN,
            0.85, 0.65, 0.95
        );
        
        String message = report.message();
        assertNotNull(message);
        assertTrue(message.contains("crítico") || message.contains("critical"));
    }
    
    @Test
    void testReportSummaryFormat() {
        TrafficLightReport report = new TrafficLightReport(
            TrafficLightColor.GREEN, TrafficLightColor.AMBER, TrafficLightColor.GREEN,
            0.4, 0.6, 0.92
        );
        
        String summary = report.getSummary();
        assertNotNull(summary);
        assertTrue(summary.contains("TRAFFIC LIGHT"));
        assertTrue(summary.contains("40.0%")); // Flight occupancy
        assertTrue(summary.contains("60.0%")); // Storage occupancy
        assertTrue(summary.contains("92.0%")); // SLA compliance
    }
}
