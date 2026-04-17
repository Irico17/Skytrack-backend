package com.equipo2b.scheduler.exception;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de integración para verificar la jerarquía de excepciones y su uso en escenarios reales.
 */
class ExceptionHierarchyIntegrationTest {
    
    @Test
    void testExceptionHierarchy() {
        // Verificar que todas las excepciones específicas extienden PlanningException
        assertTrue(PlanningException.class.isAssignableFrom(InvalidConfigurationException.class));
        assertTrue(PlanningException.class.isAssignableFrom(DataLoadException.class));
        assertTrue(PlanningException.class.isAssignableFrom(RouteGenerationException.class));
    }
    
    @Test
    void testCatchingSpecificExceptions() {
        // Simular captura de excepciones específicas
        try {
            simulateConfigurationError();
            fail("Should have thrown InvalidConfigurationException");
        } catch (InvalidConfigurationException e) {
            assertTrue(e.getMessage().contains("Sa"));
        } catch (PlanningException e) {
            fail("Should have caught InvalidConfigurationException specifically");
        }
    }
    
    @Test
    void testCatchingBaseException() {
        // Simular captura de excepción base para manejar todas las excepciones de planificación
        try {
            simulateDataLoadError();
            fail("Should have thrown DataLoadException");
        } catch (PlanningException e) {
            // Captura exitosa de excepción derivada mediante tipo base
            assertTrue(e instanceof DataLoadException);
        }
    }
    
    @Test
    void testMultipleExceptionHandling() {
        // Simular manejo de múltiples tipos de excepciones
        int configErrors = 0;
        int dataErrors = 0;
        int routeErrors = 0;
        
        try {
            simulateConfigurationError();
        } catch (InvalidConfigurationException e) {
            configErrors++;
        } catch (PlanningException e) {
            fail("Should have caught specific exception type");
        }
        
        try {
            simulateDataLoadError();
        } catch (DataLoadException e) {
            dataErrors++;
        } catch (PlanningException e) {
            fail("Should have caught specific exception type");
        }
        
        try {
            simulateRouteGenerationError();
        } catch (RouteGenerationException e) {
            routeErrors++;
        } catch (PlanningException e) {
            fail("Should have caught specific exception type");
        }
        
        assertEquals(1, configErrors);
        assertEquals(1, dataErrors);
        assertEquals(1, routeErrors);
    }
    
    @Test
    void testExceptionChaining() {
        // Verificar que las excepciones mantienen la cadena de causas
        try {
            simulateChainedError();
            fail("Should have thrown exception");
        } catch (DataLoadException e) {
            assertNotNull(e.getCause());
            assertTrue(e.getCause() instanceof NumberFormatException);
        } catch (PlanningException e) {
            fail("Should have caught DataLoadException");
        }
    }
    
    // Métodos auxiliares para simular errores
    
    private void simulateConfigurationError() throws InvalidConfigurationException {
        int ta = 5;
        int sa = 3;
        if (sa <= ta) {
            throw new InvalidConfigurationException(
                String.format("Sa (%d) debe ser mayor que Ta (%d)", sa, ta)
            );
        }
    }
    
    private void simulateDataLoadError() throws DataLoadException {
        throw new DataLoadException(
            "Formato de aeropuerto inválido",
            "aeropuertos.txt",
            42
        );
    }
    
    private void simulateRouteGenerationError() throws RouteGenerationException {
        throw new RouteGenerationException(
            "No existen vuelos disponibles",
            "BATCH001",
            "JFK",
            "UNKNOWN"
        );
    }
    
    private void simulateChainedError() throws DataLoadException {
        try {
            Integer.parseInt("invalid");
        } catch (NumberFormatException e) {
            throw new DataLoadException(
                "Error al parsear capacidad",
                "vuelos.txt",
                15,
                e
            );
        }
    }
}
