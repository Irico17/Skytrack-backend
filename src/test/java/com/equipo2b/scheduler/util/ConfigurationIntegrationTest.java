package com.equipo2b.scheduler.util;

import com.equipo2b.scheduler.algorithm.AlgorithmConfig;
import com.equipo2b.scheduler.exception.InvalidConfigurationException;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test de integración que demuestra el uso conjunto de ConfigurationValidator y PlanningLogger.
 * 
 * <p>Este test muestra cómo validar la configuración del sistema antes de iniciar
 * operaciones, usando el logger para registrar el proceso.
 * 
 * <p><strong>Validates: Requirements 16.3, 16.4, 34.1, 34.2, 34.3, 34.4, 34.5</strong>
 */
class ConfigurationIntegrationTest {
    
    @Test
    void testValidateAndLogSystemConfiguration() {
        // Configuración válida del sistema
        int Ta = 1;  // 1 minuto
        int Sa = 5;  // 5 minutos
        int K = 14;  // Simulación de periodo
        
        // Configuración del Algoritmo Genético
        AlgorithmConfig gaConfig = new AlgorithmConfig();
        gaConfig.setInt("populationSize", 50);
        gaConfig.setInt("generations", 100);
        gaConfig.setDouble("mutationRate", 0.1);
        gaConfig.setInt("tournamentSize", 4);
        gaConfig.setInt("eliteCount", 2);
        
        // Configuración de Búsqueda Tabú
        AlgorithmConfig tabuConfig = new AlgorithmConfig();
        tabuConfig.setInt("maxIterations", 200);
        tabuConfig.setInt("tabuTenure", 15);
        tabuConfig.setInt("neighborhoodSize", 20);
        
        // Log inicio de validación
        PlanningLogger.logInfo("=== VALIDACIÓN DE CONFIGURACIÓN ===");
        
        // Validar configuración completa
        assertDoesNotThrow(() -> 
            ConfigurationValidator.validateSystemConfiguration(Ta, Sa, K, gaConfig, tabuConfig)
        );
        
        // Log éxito
        PlanningLogger.logInfo("✓ Configuración validada exitosamente");
    }
    
    @Test
    void testValidateAndLogInvalidConfiguration() {
        // Configuración inválida: Sa <= Ta
        int Ta = 5;
        int Sa = 5;  // Igual a Ta (inválido)
        int K = 14;
        
        PlanningLogger.logInfo("=== VALIDACIÓN DE CONFIGURACIÓN INVÁLIDA ===");
        
        // Debe lanzar excepción
        InvalidConfigurationException exception = assertThrows(
            InvalidConfigurationException.class,
            () -> ConfigurationValidator.validateSystemConfiguration(Ta, Sa, K, null, null)
        );
        
        // Log del error
        PlanningLogger.logError("✗ Configuración inválida detectada", exception);
        
        // Verificar mensaje de error
        assertTrue(exception.getMessage().contains("Sa (5 min) debe ser estrictamente mayor que Ta (5 min)"));
    }
    
    @Test
    void testScenarioOperationDayToDay() {
        // Escenario: Operación día a día (K=1)
        int Ta = 1;
        int Sa = 5;
        int K = 1;
        
        PlanningLogger.logInfo("=== ESCENARIO: OPERACIÓN DÍA A DÍA ===");
        PlanningLogger.logInfo(String.format("Parámetros: Ta=%d min, Sa=%d min, K=%d", Ta, Sa, K));
        
        assertDoesNotThrow(() -> {
            ConfigurationValidator.validateSaGreaterThanTa(Ta, Sa);
            ConfigurationValidator.validateKPositive(K);
        });
        
        PlanningLogger.logInfo("✓ Configuración válida para operación día a día");
    }
    
    @Test
    void testScenarioSimulationPeriod() {
        // Escenario: Simulación de periodo (K=14-23)
        int Ta = 1;
        int Sa = 5;
        int K = 18;  // Valor intermedio
        
        PlanningLogger.logInfo("=== ESCENARIO: SIMULACIÓN DE PERIODO ===");
        PlanningLogger.logInfo(String.format("Parámetros: Ta=%d min, Sa=%d min, K=%d, Sc=%d min", 
                                             Ta, Sa, K, Sa * K));
        
        assertDoesNotThrow(() -> {
            ConfigurationValidator.validateSaGreaterThanTa(Ta, Sa);
            ConfigurationValidator.validateKPositive(K);
        });
        
        PlanningLogger.logInfo("✓ Configuración válida para simulación de periodo");
    }
    
    @Test
    void testScenarioSimulationCollapse() {
        // Escenario: Simulación hasta colapso (K=75)
        int Ta = 1;
        int Sa = 5;
        int K = 75;
        
        PlanningLogger.logInfo("=== ESCENARIO: SIMULACIÓN HASTA COLAPSO ===");
        PlanningLogger.logInfo(String.format("Parámetros: Ta=%d min, Sa=%d min, K=%d, Sc=%d min", 
                                             Ta, Sa, K, Sa * K));
        
        assertDoesNotThrow(() -> {
            ConfigurationValidator.validateSaGreaterThanTa(Ta, Sa);
            ConfigurationValidator.validateKPositive(K);
        });
        
        PlanningLogger.logInfo("✓ Configuración válida para simulación hasta colapso");
    }
    
    @Test
    void testExtremeValuesWarning() {
        // Sa muy pequeño debe generar warning
        int Ta = 1;
        int Sa1 = 1;  // Muy pequeño pero válido (Sa > Ta no se cumple)
        
        PlanningLogger.logInfo("=== TEST: VALORES EXTREMOS ===");
        
        // Primero validar que Sa > Ta falle
        final int finalSa1 = Sa1;
        assertThrows(
            InvalidConfigurationException.class,
            () -> ConfigurationValidator.validateSaGreaterThanTa(Ta, finalSa1)
        );
        
        // Ahora probar con Sa=2 (válido pero extremo)
        int Sa2 = 2;
        final int finalSa2 = Sa2;
        assertDoesNotThrow(() -> {
            ConfigurationValidator.validateSaGreaterThanTa(Ta, finalSa2);
            ConfigurationValidator.warnExtremeValues(finalSa2);  // Debe generar warning
        });
    }
    
    @Test
    void testAlgorithmConfigurationValidation() {
        PlanningLogger.logInfo("=== VALIDACIÓN DE CONFIGURACIÓN DE ALGORITMOS ===");
        
        // Configuración válida de GA
        AlgorithmConfig gaConfig = new AlgorithmConfig();
        gaConfig.setInt("populationSize", 100);
        gaConfig.setInt("generations", 200);
        gaConfig.setDouble("mutationRate", 0.15);
        gaConfig.setInt("tournamentSize", 5);
        gaConfig.setInt("eliteCount", 3);
        
        assertDoesNotThrow(() -> {
            ConfigurationValidator.validateGeneticAlgorithmConfig(gaConfig);
            PlanningLogger.logInfo("✓ Configuración de Algoritmo Genético válida");
        });
        
        // Configuración válida de Tabú
        AlgorithmConfig tabuConfig = new AlgorithmConfig();
        tabuConfig.setInt("maxIterations", 300);
        tabuConfig.setInt("tabuTenure", 20);
        tabuConfig.setInt("neighborhoodSize", 25);
        
        assertDoesNotThrow(() -> {
            ConfigurationValidator.validateTabuSearchConfig(tabuConfig);
            PlanningLogger.logInfo("✓ Configuración de Búsqueda Tabú válida");
        });
    }
}
