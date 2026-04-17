package com.equipo2b.scheduler.exception;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitarios para la clase DataLoadException.
 */
class DataLoadExceptionTest {
    
    @Test
    void testConstructorWithMessage() {
        String message = "Error al cargar archivo";
        DataLoadException exception = new DataLoadException(message);
        
        assertEquals(message, exception.getMessage());
        assertNull(exception.getCause());
        assertNull(exception.getFileName());
        assertEquals(-1, exception.getLineNumber());
    }
    
    @Test
    void testConstructorWithMessageAndCause() {
        String message = "Error de parsing";
        Throwable cause = new NumberFormatException("Invalid number");
        DataLoadException exception = new DataLoadException(message, cause);
        
        assertEquals(message, exception.getMessage());
        assertEquals(cause, exception.getCause());
        assertNull(exception.getFileName());
        assertEquals(-1, exception.getLineNumber());
    }
    
    @Test
    void testConstructorWithFileAndLineInfo() {
        String message = "Formato de fecha inválido";
        String fileName = "vuelos.txt";
        int lineNumber = 42;
        
        DataLoadException exception = new DataLoadException(message, fileName, lineNumber);
        
        assertTrue(exception.getMessage().contains(message));
        assertTrue(exception.getMessage().contains(fileName));
        assertTrue(exception.getMessage().contains("42"));
        assertEquals(fileName, exception.getFileName());
        assertEquals(lineNumber, exception.getLineNumber());
    }
    
    @Test
    void testConstructorWithFileLineAndCause() {
        String message = "Capacidad inválida";
        String fileName = "aeropuertos.txt";
        int lineNumber = 15;
        Throwable cause = new NumberFormatException("For input string: 'abc'");
        
        DataLoadException exception = new DataLoadException(message, fileName, lineNumber, cause);
        
        assertTrue(exception.getMessage().contains(message));
        assertTrue(exception.getMessage().contains(fileName));
        assertTrue(exception.getMessage().contains("15"));
        assertEquals(fileName, exception.getFileName());
        assertEquals(lineNumber, exception.getLineNumber());
        assertEquals(cause, exception.getCause());
    }
    
    @Test
    void testExceptionExtendsPlanningException() {
        DataLoadException exception = new DataLoadException("Test");
        assertTrue(exception instanceof PlanningException);
    }
    
    @Test
    void testDataLoadErrorScenario() {
        // Simular error de carga de datos (Requisito 17.5)
        String fileName = "pedidos.txt";
        int lineNumber = 100;
        String invalidLine = "LOTE001|2024-03-15|JFK|INVALID|50|CLIENT1";
        
        DataLoadException exception = new DataLoadException(
            "Código de aeropuerto inválido: INVALID",
            fileName,
            lineNumber
        );
        
        assertNotNull(exception.getMessage());
        assertEquals(fileName, exception.getFileName());
        assertEquals(lineNumber, exception.getLineNumber());
    }
}
