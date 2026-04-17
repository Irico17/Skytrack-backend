package com.equipo2b.scheduler.util;

import com.equipo2b.scheduler.model.*;
import com.equipo2b.scheduler.monitoring.CollapseStatus;
import com.equipo2b.scheduler.monitoring.CollapseLevel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitarios para PlanningLogger.
 * 
 * <p><strong>Validates: Requirements 16.3, 16.4</strong>
 */
class PlanningLoggerTest {
    
    private TestLogHandler logHandler;
    private Logger logger;
    
    /**
     * Handler personalizado para capturar logs en tests.
     */
    private static class TestLogHandler extends Handler {
        private final List<LogRecord> records = new ArrayList<>();
        
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }
        
        @Override
        public void flush() {}
        
        @Override
        public void close() throws SecurityException {}
        
        public List<LogRecord> getRecords() {
            return records;
        }
        
        public void clear() {
            records.clear();
        }
    }
    
    @BeforeEach
    void setUp() {
        logger = Logger.getLogger("TasfB2B");
        logHandler = new TestLogHandler();
        logger.addHandler(logHandler);
        logger.setLevel(Level.ALL);
        logHandler.setLevel(Level.ALL);
    }
    
    @AfterEach
    void tearDown() {
        logger.removeHandler(logHandler);
    }
    
    @Test
    void testLogInfo() {
        String message = "Test info message";
        PlanningLogger.logInfo(message);
        
        List<LogRecord> records = logHandler.getRecords();
        assertEquals(1, records.size());
        assertEquals(Level.INFO, records.get(0).getLevel());
        assertEquals(message, records.get(0).getMessage());
    }
    
    @Test
    void testLogWarning() {
        String message = "Test warning message";
        PlanningLogger.logWarning(message);
        
        List<LogRecord> records = logHandler.getRecords();
        assertEquals(1, records.size());
        assertEquals(Level.WARNING, records.get(0).getLevel());
        assertEquals(message, records.get(0).getMessage());
    }
    
    @Test
    void testLogError() {
        String message = "Test error message";
        Exception exception = new RuntimeException("Test exception");
        PlanningLogger.logError(message, exception);
        
        List<LogRecord> records = logHandler.getRecords();
        assertEquals(1, records.size());
        assertEquals(Level.SEVERE, records.get(0).getLevel());
        assertEquals(message, records.get(0).getMessage());
        assertEquals(exception, records.get(0).getThrown());
    }
    
    @Test
    void testLogErrorWithNullException() {
        String message = "Test error without exception";
        PlanningLogger.logError(message, null);
        
        List<LogRecord> records = logHandler.getRecords();
        assertEquals(1, records.size());
        assertEquals(Level.SEVERE, records.get(0).getLevel());
        assertNull(records.get(0).getThrown());
    }
    
    @Test
    void testLogPlanningCycleStart() {
        int cycleNumber = 5;
        ZonedDateTime currentTime = ZonedDateTime.of(2025, 1, 15, 10, 30, 0, 0, ZoneId.of("UTC"));
        int batchCount = 42;
        
        PlanningLogger.logPlanningCycleStart(cycleNumber, currentTime, batchCount);
        
        List<LogRecord> records = logHandler.getRecords();
        assertEquals(1, records.size());
        assertEquals(Level.INFO, records.get(0).getLevel());
        
        String message = records.get(0).getMessage();
        assertTrue(message.contains("CICLO DE PLANIFICACIÓN 5 INICIADO"));
        assertTrue(message.contains("Lotes: 42"));
    }
    
    @Test
    void testLogPlanningCycleEnd() {
        int cycleNumber = 5;
        double fitness = 12345.67;
        long executionTimeMs = 1500;
        
        PlanningLogger.logPlanningCycleEnd(cycleNumber, fitness, executionTimeMs);
        
        List<LogRecord> records = logHandler.getRecords();
        assertEquals(1, records.size());
        assertEquals(Level.INFO, records.get(0).getLevel());
        
        String message = records.get(0).getMessage();
        assertTrue(message.contains("CICLO DE PLANIFICACIÓN 5 COMPLETADO"));
        assertTrue(message.contains("Fitness: 12345.67"));
        assertTrue(message.contains("Tiempo: 1500 ms"));
    }
    
    @Test
    void testLogFitnessImprovement() {
        int generation = 10;
        double oldFitness = 10000.0;
        double newFitness = 8000.0;
        
        PlanningLogger.logFitnessImprovement(generation, oldFitness, newFitness);
        
        List<LogRecord> records = logHandler.getRecords();
        assertEquals(1, records.size());
        assertEquals(Level.INFO, records.get(0).getLevel());
        
        String message = records.get(0).getMessage();
        assertTrue(message.contains("Generación 10"));
        assertTrue(message.contains("10000.00"));
        assertTrue(message.contains("8000.00"));
        assertTrue(message.contains("mejora: 2000.00"));
        assertTrue(message.contains("20.0%"));
    }
    
    @Test
    void testLogCancellation() {
        Airport jfk = new Airport("JFK", "New York", "USA", ZoneId.of("America/New_York"),
                                  600, 40.6413, -73.7781, Continent.AMERICA);
        Airport cdg = new Airport("CDG", "Paris", "France", ZoneId.of("Europe/Paris"),
                                  700, 49.0097, 2.5479, Continent.EUROPE);
        
        ZonedDateTime departure = ZonedDateTime.of(2025, 1, 15, 10, 0, 0, 0, jfk.zoneId());
        ZonedDateTime arrival = departure.plusHours(24);
        
        Flight flight = new Flight("AA100", jfk, cdg, departure, arrival, 300, FlightType.INTERCONTINENTAL);
        
        PlanningLogger.logCancellation(flight, 5);
        
        List<LogRecord> records = logHandler.getRecords();
        assertEquals(1, records.size());
        assertEquals(Level.INFO, records.get(0).getLevel());
        
        String message = records.get(0).getMessage();
        assertTrue(message.contains("CANCELACIÓN"));
        assertTrue(message.contains("AA100"));
        assertTrue(message.contains("JFK"));
        assertTrue(message.contains("CDG"));
        assertTrue(message.contains("5 lotes afectados"));
    }
    
    @Test
    void testLogReplanningStart() {
        String flightId = "AA100";
        int affectedBatchCount = 7;
        
        PlanningLogger.logReplanningStart(flightId, affectedBatchCount);
        
        List<LogRecord> records = logHandler.getRecords();
        assertEquals(1, records.size());
        assertEquals(Level.INFO, records.get(0).getLevel());
        
        String message = records.get(0).getMessage();
        assertTrue(message.contains("REPLANIFICACIÓN INICIADA"));
        assertTrue(message.contains("AA100"));
        assertTrue(message.contains("7"));
    }
    
    @Test
    void testLogReplanningResultSuccess() {
        PlanningLogger.logReplanningResult(7, 0);
        
        List<LogRecord> records = logHandler.getRecords();
        assertEquals(1, records.size());
        assertEquals(Level.INFO, records.get(0).getLevel());
        
        String message = records.get(0).getMessage();
        assertTrue(message.contains("REPLANIFICACIÓN EXITOSA"));
        assertTrue(message.contains("7 lotes replanificados"));
    }
    
    @Test
    void testLogReplanningResultPartial() {
        PlanningLogger.logReplanningResult(5, 2);
        
        List<LogRecord> records = logHandler.getRecords();
        assertEquals(1, records.size());
        assertEquals(Level.WARNING, records.get(0).getLevel());
        
        String message = records.get(0).getMessage();
        assertTrue(message.contains("REPLANIFICACIÓN PARCIAL"));
        assertTrue(message.contains("5 lotes replanificados"));
        assertTrue(message.contains("2 no replanificables"));
    }
    
    @Test
    void testLogRouteGenerationFailure() {
        Airport jfk = new Airport("JFK", "New York", "USA", ZoneId.of("America/New_York"),
                                  600, 40.6413, -73.7781, Continent.AMERICA);
        Airport lax = new Airport("LAX", "Los Angeles", "USA", ZoneId.of("America/Los_Angeles"),
                                  650, 33.9416, -118.4085, Continent.AMERICA);
        
        ZonedDateTime ingressTime = ZonedDateTime.of(2025, 1, 15, 8, 0, 0, 0, jfk.zoneId());
        ShipmentBatch batch = new ShipmentBatch("BATCH001", "JFK-001", "CLIENT1",
                                                jfk, lax, 50, ingressTime);
        
        PlanningLogger.logRouteGenerationFailure(batch, 3);
        
        List<LogRecord> records = logHandler.getRecords();
        assertEquals(1, records.size());
        assertEquals(Level.WARNING, records.get(0).getLevel());
        
        String message = records.get(0).getMessage();
        assertTrue(message.contains("FALLO GENERACIÓN RUTA"));
        assertTrue(message.contains("BATCH001"));
        assertTrue(message.contains("3 intentos"));
        assertTrue(message.contains("JFK"));
        assertTrue(message.contains("LAX"));
        assertTrue(message.contains("50"));
    }
    
    @Test
    void testLogCollapseDetection() {
        CollapseStatus status = new CollapseStatus(CollapseLevel.WARNING, 75.0, 15.0, "Test collapse");
        
        PlanningLogger.logCollapseDetection(status);
        
        List<LogRecord> records = logHandler.getRecords();
        assertEquals(1, records.size());
        assertEquals(Level.WARNING, records.get(0).getLevel());
        
        String message = records.get(0).getMessage();
        assertTrue(message.contains("COLAPSO DETECTADO"));
        assertTrue(message.contains("WARNING"));
        assertTrue(message.contains("75.0%"));
        assertTrue(message.contains("15.0%"));
    }
}
