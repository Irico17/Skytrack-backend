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
 * Test de integración para TrafficLightIndicator.
 * 
 * Demuestra el uso completo del sistema de indicadores semáforo
 * con escenarios realistas de operación.
 */
class TrafficLightIntegrationTest {
    
    private FlightPlan flightPlan;
    private AirportManager airportManager;
    private CapacityMonitor monitor;
    private TrafficLightIndicator indicator;
    
    private Airport jfk;
    private Airport lhr;
    private Airport cdg;
    private Airport nrt;
    
    @BeforeEach
    void setUp() {
        // Crear aeropuertos en diferentes continentes
        jfk = new Airport("JFK", "New York", "USA", 
                         ZoneId.of("America/New_York"), 600, 
                         40.6413, -73.7781, Continent.AMERICA);
        lhr = new Airport("LHR", "London", "UK", 
                         ZoneId.of("Europe/London"), 700, 
                         51.4700, -0.4543, Continent.EUROPE);
        cdg = new Airport("CDG", "Paris", "France", 
                         ZoneId.of("Europe/Paris"), 650, 
                         49.0097, 2.5479, Continent.EUROPE);
        nrt = new Airport("NRT", "Tokyo", "Japan", 
                         ZoneId.of("Asia/Tokyo"), 800, 
                         35.7720, 140.3929, Continent.ASIA);
        
        List<Airport> airports = List.of(jfk, lhr, cdg, nrt);
        airportManager = new AirportManager(airports);
        
        flightPlan = new FlightPlan();
        monitor = new CapacityMonitor(flightPlan, airportManager);
        
        // Crear indicador con umbrales personalizados
        indicator = new TrafficLightIndicator(0.6, 0.8); // 60% verde, 80% ámbar
    }
    
    @Test
    void testNormalOperationScenario() {
        System.out.println("\n=== ESCENARIO: Operación Normal ===");
        
        // Crear vuelos con capacidad adecuada
        ZonedDateTime now = ZonedDateTime.now(jfk.zoneId());
        
        Flight fl1 = new Flight("FL001", jfk, lhr, 
            now, now.plusHours(24), 300, FlightType.INTERCONTINENTAL);
        Flight fl2 = new Flight("FL002", lhr, cdg, 
            now.plusHours(25), now.plusHours(37), 200, FlightType.INTRACONTINENTAL);
        Flight fl3 = new Flight("FL003", lhr, nrt, 
            now.plusHours(26), now.plusHours(50), 350, FlightType.INTERCONTINENTAL);
        
        flightPlan.addFlight(fl1);
        flightPlan.addFlight(fl2);
        flightPlan.addFlight(fl3);
        
        // Crear lotes con ocupación moderada
        List<ShipmentBatch> batches = new ArrayList<>();
        batches.add(new ShipmentBatch("B001", "A001", "C001", jfk, lhr, 120, now));
        batches.add(new ShipmentBatch("B002", "A002", "C002", lhr, cdg, 80, now.plusHours(25)));
        batches.add(new ShipmentBatch("B003", "A003", "C003", lhr, nrt, 150, now.plusHours(26)));
        
        // Crear solución
        Solution solution = new Solution();
        solution.addRoute(new AssignedRoute(batches.get(0), List.of(fl1)));
        solution.addRoute(new AssignedRoute(batches.get(1), List.of(fl2)));
        solution.addRoute(new AssignedRoute(batches.get(2), List.of(fl3)));
        
        // Generar reporte
        TrafficLightReport report = indicator.generateReport(solution, monitor);
        
        System.out.println(report.getSummary());
        
        // Verificar que todo está en verde o ámbar
        assertNotEquals(TrafficLightColor.RED, report.overallColor());
        assertTrue(report.slaCompliance() == 1.0);
    }
    
    @Test
    void testHighLoadScenario() {
        System.out.println("\n=== ESCENARIO: Alta Carga ===");
        
        // Crear vuelos con capacidad limitada
        ZonedDateTime now = ZonedDateTime.now(jfk.zoneId());
        
        Flight fl1 = new Flight("FL001", jfk, lhr, 
            now, now.plusHours(24), 200, FlightType.INTERCONTINENTAL);
        Flight fl2 = new Flight("FL002", lhr, cdg, 
            now.plusHours(25), now.plusHours(37), 150, FlightType.INTRACONTINENTAL);
        
        flightPlan.addFlight(fl1);
        flightPlan.addFlight(fl2);
        
        // Crear lotes con alta ocupación (cerca del límite)
        List<ShipmentBatch> batches = new ArrayList<>();
        batches.add(new ShipmentBatch("B001", "A001", "C001", jfk, lhr, 180, now));
        batches.add(new ShipmentBatch("B002", "A002", "C002", lhr, cdg, 140, now.plusHours(25)));
        
        // Crear solución
        Solution solution = new Solution();
        solution.addRoute(new AssignedRoute(batches.get(0), List.of(fl1)));
        solution.addRoute(new AssignedRoute(batches.get(1), List.of(fl2)));
        
        // Generar reporte
        TrafficLightReport report = indicator.generateReport(solution, monitor);
        
        System.out.println(report.getSummary());
        
        // Verificar que hay indicadores en rojo o ámbar
        assertTrue(report.flightOccupancy() > 0.8);
        assertTrue(report.flightColor() == TrafficLightColor.RED || 
                   report.flightColor() == TrafficLightColor.AMBER);
    }
    
    @Test
    void testCustomThresholdsScenario() {
        System.out.println("\n=== ESCENARIO: Umbrales Personalizados ===");
        
        // Crear indicador muy estricto (30% verde, 50% ámbar)
        TrafficLightIndicator strictIndicator = new TrafficLightIndicator(0.3, 0.5);
        
        // Crear vuelo con ocupación moderada
        ZonedDateTime now = ZonedDateTime.now(jfk.zoneId());
        Flight fl1 = new Flight("FL001", jfk, lhr, 
            now, now.plusHours(24), 200, FlightType.INTERCONTINENTAL);
        flightPlan.addFlight(fl1);
        
        // Lote con 40% de ocupación
        ShipmentBatch batch = new ShipmentBatch("B001", "A001", "C001", jfk, lhr, 80, now);
        Solution solution = new Solution();
        solution.addRoute(new AssignedRoute(batch, List.of(fl1)));
        
        // Con indicador estricto, 40% debería ser AMBER
        TrafficLightReport strictReport = strictIndicator.generateReport(solution, monitor);
        System.out.println("Indicador Estricto (30%/50%):");
        System.out.println(strictReport.getSummary());
        assertEquals(TrafficLightColor.AMBER, strictReport.flightColor());
        
        // Con indicador normal (60%/80%), 40% debería ser GREEN
        TrafficLightReport normalReport = indicator.generateReport(solution, monitor);
        System.out.println("\nIndicador Normal (60%/80%):");
        System.out.println(normalReport.getSummary());
        assertEquals(TrafficLightColor.GREEN, normalReport.flightColor());
    }
    
    @Test
    void testReportMessageContent() {
        // Crear escenario con problemas mixtos
        ZonedDateTime now = ZonedDateTime.now(jfk.zoneId());
        
        Flight fl1 = new Flight("FL001", jfk, lhr, 
            now, now.plusHours(24), 200, FlightType.INTERCONTINENTAL);
        flightPlan.addFlight(fl1);
        
        // Alta ocupación de vuelos
        ShipmentBatch batch = new ShipmentBatch("B001", "A001", "C001", jfk, lhr, 190, now);
        Solution solution = new Solution();
        solution.addRoute(new AssignedRoute(batch, List.of(fl1)));
        
        TrafficLightReport report = indicator.generateReport(solution, monitor);
        
        // Verificar que el mensaje contiene información útil
        String message = report.message();
        assertNotNull(message);
        assertFalse(message.isEmpty());
        
        System.out.println("\n=== Mensaje del Reporte ===");
        System.out.println(message);
        
        // El mensaje debe mencionar el estado crítico
        assertTrue(message.toLowerCase().contains("crítico") || 
                   message.toLowerCase().contains("sobrecargado"));
    }
}
