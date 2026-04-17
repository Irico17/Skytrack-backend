package com.equipo2b.scheduler.exception;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitarios para la clase InvalidConfigurationException.
 */
class InvalidConfigurationExceptionTest {
    
    @Test
    void testConstructorWithMessage() {
        String message = "Sa debe ser mayor que Ta";
        InvalidConfigurationException exception = new InvalidConfigurationException(message);
        
        assertEquals(message, exception.getMessage());
        assertNull(exception.getCause());
    }
    
    @Test
    void testConstructorWithMessageAndCause() {
        String message = "Parámetro K inválido";
        Throwable cause = new IllegalArgumentException("K debe ser positivo");
        InvalidConfigurationException exception = new InvalidConfigurationException(message, cause);
        
        assertEquals(message, exception.getMessage());
        assertEquals(cause, exception.getCause());
    }
    
    @Test
    void testExceptionExtendsPlanningException() {
        InvalidConfigurationException exception = new InvalidConfigurationException("Test");
        assertTrue(exception instanceof PlanningException);
    }
    
    @Test
    void testConfigurationErrorScenario() {
        // Simular validación de configuración Sa > Ta (Requisito 34.2)
        int ta = 5;
        int sa = 3;
        
        if (sa <= ta) {
            InvalidConfigurationException exception = new InvalidConfigurationException(
                String.format("Sa (%d) debe ser estrictamente mayor que Ta (%d)", sa, ta)
            );
            
            assertTrue(exception.getMessage().contains("Sa"));
            assertTrue(exception.getMessage().contains("Ta"));
        }
    }
}
