package com.equipo2b.scheduler.exception;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitarios para la clase RouteGenerationException.
 */
class RouteGenerationExceptionTest {
    
    @Test
    void testConstructorWithMessage() {
        String message = "No se pudo generar ruta factible";
        RouteGenerationException exception = new RouteGenerationException(message);
        
        assertEquals(message, exception.getMessage());
        assertNull(exception.getCause());
        assertNull(exception.getBatchId());
        assertNull(exception.getOrigin());
        assertNull(exception.getDestination());
    }
    
    @Test
    void testConstructorWithMessageAndCause() {
        String message = "Error en búsqueda de ruta";
        Throwable cause = new IllegalStateException("No flights available");
        RouteGenerationException exception = new RouteGenerationException(message, cause);
        
        assertEquals(message, exception.getMessage());
        assertEquals(cause, exception.getCause());
        assertNull(exception.getBatchId());
        assertNull(exception.getOrigin());
        assertNull(exception.getDestination());
    }
    
    @Test
    void testConstructorWithBatchInfo() {
        String message = "No existen vuelos disponibles";
        String batchId = "BATCH001";
        String origin = "JFK";
        String destination = "CDG";
        
        RouteGenerationException exception = new RouteGenerationException(
            message, batchId, origin, destination
        );
        
        assertTrue(exception.getMessage().contains(message));
        assertTrue(exception.getMessage().contains(batchId));
        assertTrue(exception.getMessage().contains(origin));
        assertTrue(exception.getMessage().contains(destination));
        assertEquals(batchId, exception.getBatchId());
        assertEquals(origin, exception.getOrigin());
        assertEquals(destination, exception.getDestination());
    }
    
    @Test
    void testConstructorWithBatchInfoAndCause() {
        String message = "Todos los vuelos a capacidad máxima";
        String batchId = "BATCH002";
        String origin = "LAX";
        String destination = "NRT";
        Throwable cause = new IllegalStateException("Capacity exceeded");
        
        RouteGenerationException exception = new RouteGenerationException(
            message, batchId, origin, destination, cause
        );
        
        assertTrue(exception.getMessage().contains(message));
        assertTrue(exception.getMessage().contains(batchId));
        assertTrue(exception.getMessage().contains(origin));
        assertTrue(exception.getMessage().contains(destination));
        assertEquals(batchId, exception.getBatchId());
        assertEquals(origin, exception.getOrigin());
        assertEquals(destination, exception.getDestination());
        assertEquals(cause, exception.getCause());
    }
    
    @Test
    void testExceptionExtendsPlanningException() {
        RouteGenerationException exception = new RouteGenerationException("Test");
        assertTrue(exception instanceof PlanningException);
    }
    
    @Test
    void testRouteGenerationErrorScenario() {
        // Simular error de generación de ruta (Requisito 20.1)
        String batchId = "BATCH123";
        String origin = "JFK";
        String destination = "UNKNOWN";
        
        RouteGenerationException exception = new RouteGenerationException(
            "No existen vuelos disponibles entre origen y destino",
            batchId,
            origin,
            destination
        );
        
        assertNotNull(exception.getMessage());
        assertEquals(batchId, exception.getBatchId());
        assertEquals(origin, exception.getOrigin());
        assertEquals(destination, exception.getDestination());
    }
    
    @Test
    void testCapacityExceededScenario() {
        // Simular error por capacidad excedida (Requisito 20.2)
        String batchId = "BATCH456";
        String origin = "LAX";
        String destination = "SYD";
        
        RouteGenerationException exception = new RouteGenerationException(
            "Todos los vuelos están a capacidad máxima",
            batchId,
            origin,
            destination
        );
        
        assertTrue(exception.getMessage().contains("capacidad"));
        assertEquals(batchId, exception.getBatchId());
    }
}
