package com.equipo2b.scheduler.exception;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitarios para la clase PlanningException.
 */
class PlanningExceptionTest {
    
    @Test
    void testConstructorWithMessage() {
        String message = "Error de planificación";
        PlanningException exception = new PlanningException(message);
        
        assertEquals(message, exception.getMessage());
        assertNull(exception.getCause());
    }
    
    @Test
    void testConstructorWithMessageAndCause() {
        String message = "Error de planificación";
        Throwable cause = new RuntimeException("Causa raíz");
        PlanningException exception = new PlanningException(message, cause);
        
        assertEquals(message, exception.getMessage());
        assertEquals(cause, exception.getCause());
    }
    
    @Test
    void testConstructorWithCause() {
        Throwable cause = new RuntimeException("Causa raíz");
        PlanningException exception = new PlanningException(cause);
        
        assertEquals(cause, exception.getCause());
        assertTrue(exception.getMessage().contains("RuntimeException"));
    }
    
    @Test
    void testExceptionIsCheckedException() {
        // PlanningException debe ser una checked exception (extends Exception)
        assertTrue(Exception.class.isAssignableFrom(PlanningException.class));
        assertFalse(RuntimeException.class.isAssignableFrom(PlanningException.class));
    }
}
