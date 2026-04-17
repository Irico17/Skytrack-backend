package com.equipo2b.scheduler.util;

import com.equipo2b.scheduler.algorithm.AlgorithmConfig;
import com.equipo2b.scheduler.exception.InvalidConfigurationException;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitarios para ConfigurationValidator.
 * 
 * <p><strong>Validates: Requirements 34.1, 34.2, 34.3, 34.4, 34.5</strong>
 */
class ConfigurationValidatorTest {
    
    // ========== Tests para validateSaGreaterThanTa ==========
    
    @Test
    void testValidateSaGreaterThanTa_Valid() {
        // Sa > Ta debe pasar sin excepción
        assertDoesNotThrow(() -> ConfigurationValidator.validateSaGreaterThanTa(1, 5));
        assertDoesNotThrow(() -> ConfigurationValidator.validateSaGreaterThanTa(2, 10));
        assertDoesNotThrow(() -> ConfigurationValidator.validateSaGreaterThanTa(1, 2));
    }
    
    @Test
    void testValidateSaGreaterThanTa_Equal() {
        // Sa == Ta debe lanzar excepción
        InvalidConfigurationException exception = assertThrows(
            InvalidConfigurationException.class,
            () -> ConfigurationValidator.validateSaGreaterThanTa(5, 5)
        );
        
        assertTrue(exception.getMessage().contains("Sa (5 min) debe ser estrictamente mayor que Ta (5 min)"));
    }
    
    @Test
    void testValidateSaGreaterThanTa_SaLessThanTa() {
        // Sa < Ta debe lanzar excepción
        InvalidConfigurationException exception = assertThrows(
            InvalidConfigurationException.class,
            () -> ConfigurationValidator.validateSaGreaterThanTa(10, 5)
        );
        
        assertTrue(exception.getMessage().contains("Sa (5 min) debe ser estrictamente mayor que Ta (10 min)"));
    }
    
    // ========== Tests para validateKPositive ==========
    
    @Test
    void testValidateKPositive_Valid() {
        // K > 0 debe pasar sin excepción
        assertDoesNotThrow(() -> ConfigurationValidator.validateKPositive(1));
        assertDoesNotThrow(() -> ConfigurationValidator.validateKPositive(14));
        assertDoesNotThrow(() -> ConfigurationValidator.validateKPositive(75));
        assertDoesNotThrow(() -> ConfigurationValidator.validateKPositive(100));
    }
    
    @Test
    void testValidateKPositive_Zero() {
        // K == 0 debe lanzar excepción
        InvalidConfigurationException exception = assertThrows(
            InvalidConfigurationException.class,
            () -> ConfigurationValidator.validateKPositive(0)
        );
        
        assertTrue(exception.getMessage().contains("K (0) debe ser positivo"));
    }
    
    @Test
    void testValidateKPositive_Negative() {
        // K < 0 debe lanzar excepción
        InvalidConfigurationException exception = assertThrows(
            InvalidConfigurationException.class,
            () -> ConfigurationValidator.validateKPositive(-5)
        );
        
        assertTrue(exception.getMessage().contains("K (-5) debe ser positivo"));
    }
    
    // ========== Tests para warnExtremeValues ==========
    
    @Test
    void testWarnExtremeValues_Normal() {
        // Valores normales no deben generar warnings (solo verificamos que no lance excepción)
        assertDoesNotThrow(() -> ConfigurationValidator.warnExtremeValues(5));
        assertDoesNotThrow(() -> ConfigurationValidator.warnExtremeValues(10));
    }
    
    @Test
    void testWarnExtremeValues_TooSmall() {
        // Sa muy pequeño debe generar warning (no excepción)
        assertDoesNotThrow(() -> ConfigurationValidator.warnExtremeValues(1));
    }
    
    @Test
    void testWarnExtremeValues_TooLarge() {
        // Sa muy grande debe generar warning (no excepción)
        assertDoesNotThrow(() -> ConfigurationValidator.warnExtremeValues(20));
    }
    
    // ========== Tests para validateGeneticAlgorithmConfig ==========
    
    @Test
    void testValidateGeneticAlgorithmConfig_Valid() {
        AlgorithmConfig config = new AlgorithmConfig();
        config.setInt("populationSize", 50);
        config.setInt("generations", 100);
        config.setDouble("mutationRate", 0.1);
        config.setInt("tournamentSize", 4);
        config.setInt("eliteCount", 2);
        
        assertDoesNotThrow(() -> ConfigurationValidator.validateGeneticAlgorithmConfig(config));
    }
    
    @Test
    void testValidateGeneticAlgorithmConfig_PopulationSizeTooSmall() {
        AlgorithmConfig config = new AlgorithmConfig();
        config.setInt("populationSize", 1);
        
        InvalidConfigurationException exception = assertThrows(
            InvalidConfigurationException.class,
            () -> ConfigurationValidator.validateGeneticAlgorithmConfig(config)
        );
        
        assertTrue(exception.getMessage().contains("populationSize debe ser al menos 2"));
    }
    
    @Test
    void testValidateGeneticAlgorithmConfig_GenerationsTooSmall() {
        AlgorithmConfig config = new AlgorithmConfig();
        config.setInt("populationSize", 50);
        config.setInt("generations", 0);
        
        InvalidConfigurationException exception = assertThrows(
            InvalidConfigurationException.class,
            () -> ConfigurationValidator.validateGeneticAlgorithmConfig(config)
        );
        
        assertTrue(exception.getMessage().contains("generations debe ser al menos 1"));
    }
    
    @Test
    void testValidateGeneticAlgorithmConfig_MutationRateNegative() {
        AlgorithmConfig config = new AlgorithmConfig();
        config.setInt("populationSize", 50);
        config.setInt("generations", 100);
        config.setDouble("mutationRate", -0.1);
        
        InvalidConfigurationException exception = assertThrows(
            InvalidConfigurationException.class,
            () -> ConfigurationValidator.validateGeneticAlgorithmConfig(config)
        );
        
        assertTrue(exception.getMessage().contains("mutationRate debe estar entre 0.0 y 1.0"));
    }
    
    @Test
    void testValidateGeneticAlgorithmConfig_MutationRateTooHigh() {
        AlgorithmConfig config = new AlgorithmConfig();
        config.setInt("populationSize", 50);
        config.setInt("generations", 100);
        config.setDouble("mutationRate", 1.5);
        
        InvalidConfigurationException exception = assertThrows(
            InvalidConfigurationException.class,
            () -> ConfigurationValidator.validateGeneticAlgorithmConfig(config)
        );
        
        assertTrue(exception.getMessage().contains("mutationRate debe estar entre 0.0 y 1.0"));
    }
    
    @Test
    void testValidateGeneticAlgorithmConfig_TournamentSizeTooSmall() {
        AlgorithmConfig config = new AlgorithmConfig();
        config.setInt("populationSize", 50);
        config.setInt("generations", 100);
        config.setDouble("mutationRate", 0.1);
        config.setInt("tournamentSize", 1);
        
        InvalidConfigurationException exception = assertThrows(
            InvalidConfigurationException.class,
            () -> ConfigurationValidator.validateGeneticAlgorithmConfig(config)
        );
        
        assertTrue(exception.getMessage().contains("tournamentSize debe ser al menos 2"));
    }
    
    @Test
    void testValidateGeneticAlgorithmConfig_TournamentSizeTooLarge() {
        AlgorithmConfig config = new AlgorithmConfig();
        config.setInt("populationSize", 50);
        config.setInt("generations", 100);
        config.setDouble("mutationRate", 0.1);
        config.setInt("tournamentSize", 60);
        
        InvalidConfigurationException exception = assertThrows(
            InvalidConfigurationException.class,
            () -> ConfigurationValidator.validateGeneticAlgorithmConfig(config)
        );
        
        assertTrue(exception.getMessage().contains("tournamentSize (60) no puede ser mayor que populationSize (50)"));
    }
    
    @Test
    void testValidateGeneticAlgorithmConfig_EliteCountNegative() {
        AlgorithmConfig config = new AlgorithmConfig();
        config.setInt("populationSize", 50);
        config.setInt("generations", 100);
        config.setDouble("mutationRate", 0.1);
        config.setInt("tournamentSize", 4);
        config.setInt("eliteCount", -1);
        
        InvalidConfigurationException exception = assertThrows(
            InvalidConfigurationException.class,
            () -> ConfigurationValidator.validateGeneticAlgorithmConfig(config)
        );
        
        assertTrue(exception.getMessage().contains("eliteCount no puede ser negativo"));
    }
    
    @Test
    void testValidateGeneticAlgorithmConfig_EliteCountTooLarge() {
        AlgorithmConfig config = new AlgorithmConfig();
        config.setInt("populationSize", 50);
        config.setInt("generations", 100);
        config.setDouble("mutationRate", 0.1);
        config.setInt("tournamentSize", 4);
        config.setInt("eliteCount", 50);
        
        InvalidConfigurationException exception = assertThrows(
            InvalidConfigurationException.class,
            () -> ConfigurationValidator.validateGeneticAlgorithmConfig(config)
        );
        
        assertTrue(exception.getMessage().contains("eliteCount (50) debe ser menor que populationSize (50)"));
    }
    
    @Test
    void testValidateGeneticAlgorithmConfig_DefaultValues() {
        // Config vacío debe usar valores por defecto y pasar validación
        AlgorithmConfig config = new AlgorithmConfig();
        assertDoesNotThrow(() -> ConfigurationValidator.validateGeneticAlgorithmConfig(config));
    }
    
    // ========== Tests para validateTabuSearchConfig ==========
    
    @Test
    void testValidateTabuSearchConfig_Valid() {
        AlgorithmConfig config = new AlgorithmConfig();
        config.setInt("maxIterations", 200);
        config.setInt("tabuTenure", 15);
        config.setInt("neighborhoodSize", 20);
        
        assertDoesNotThrow(() -> ConfigurationValidator.validateTabuSearchConfig(config));
    }
    
    @Test
    void testValidateTabuSearchConfig_MaxIterationsTooSmall() {
        AlgorithmConfig config = new AlgorithmConfig();
        config.setInt("maxIterations", 0);
        
        InvalidConfigurationException exception = assertThrows(
            InvalidConfigurationException.class,
            () -> ConfigurationValidator.validateTabuSearchConfig(config)
        );
        
        assertTrue(exception.getMessage().contains("maxIterations debe ser al menos 1"));
    }
    
    @Test
    void testValidateTabuSearchConfig_TabuTenureTooSmall() {
        AlgorithmConfig config = new AlgorithmConfig();
        config.setInt("maxIterations", 200);
        config.setInt("tabuTenure", 0);
        
        InvalidConfigurationException exception = assertThrows(
            InvalidConfigurationException.class,
            () -> ConfigurationValidator.validateTabuSearchConfig(config)
        );
        
        assertTrue(exception.getMessage().contains("tabuTenure debe ser al menos 1"));
    }
    
    @Test
    void testValidateTabuSearchConfig_NeighborhoodSizeTooSmall() {
        AlgorithmConfig config = new AlgorithmConfig();
        config.setInt("maxIterations", 200);
        config.setInt("tabuTenure", 15);
        config.setInt("neighborhoodSize", 0);
        
        InvalidConfigurationException exception = assertThrows(
            InvalidConfigurationException.class,
            () -> ConfigurationValidator.validateTabuSearchConfig(config)
        );
        
        assertTrue(exception.getMessage().contains("neighborhoodSize debe ser al menos 1"));
    }
    
    @Test
    void testValidateTabuSearchConfig_DefaultValues() {
        // Config vacío debe usar valores por defecto y pasar validación
        AlgorithmConfig config = new AlgorithmConfig();
        assertDoesNotThrow(() -> ConfigurationValidator.validateTabuSearchConfig(config));
    }
    
    // ========== Tests para validateSystemConfiguration ==========
    
    @Test
    void testValidateSystemConfiguration_Valid() {
        AlgorithmConfig gaConfig = new AlgorithmConfig();
        gaConfig.setInt("populationSize", 50);
        gaConfig.setInt("generations", 100);
        
        AlgorithmConfig tabuConfig = new AlgorithmConfig();
        tabuConfig.setInt("maxIterations", 200);
        
        assertDoesNotThrow(() -> 
            ConfigurationValidator.validateSystemConfiguration(1, 5, 14, gaConfig, tabuConfig)
        );
    }
    
    @Test
    void testValidateSystemConfiguration_InvalidSa() {
        InvalidConfigurationException exception = assertThrows(
            InvalidConfigurationException.class,
            () -> ConfigurationValidator.validateSystemConfiguration(5, 5, 14, null, null)
        );
        
        assertTrue(exception.getMessage().contains("Sa (5 min) debe ser estrictamente mayor que Ta (5 min)"));
    }
    
    @Test
    void testValidateSystemConfiguration_InvalidK() {
        InvalidConfigurationException exception = assertThrows(
            InvalidConfigurationException.class,
            () -> ConfigurationValidator.validateSystemConfiguration(1, 5, 0, null, null)
        );
        
        assertTrue(exception.getMessage().contains("K (0) debe ser positivo"));
    }
    
    @Test
    void testValidateSystemConfiguration_NullConfigs() {
        // Debe pasar con configs null (se omiten validaciones de algoritmos)
        assertDoesNotThrow(() -> 
            ConfigurationValidator.validateSystemConfiguration(1, 5, 14, null, null)
        );
    }
    
    @Test
    void testValidateSystemConfiguration_InvalidGAConfig() {
        AlgorithmConfig gaConfig = new AlgorithmConfig();
        gaConfig.setInt("populationSize", 1);  // Inválido
        
        InvalidConfigurationException exception = assertThrows(
            InvalidConfigurationException.class,
            () -> ConfigurationValidator.validateSystemConfiguration(1, 5, 14, gaConfig, null)
        );
        
        assertTrue(exception.getMessage().contains("populationSize debe ser al menos 2"));
    }
    
    @Test
    void testValidateSystemConfiguration_InvalidTabuConfig() {
        AlgorithmConfig tabuConfig = new AlgorithmConfig();
        tabuConfig.setInt("maxIterations", 0);  // Inválido
        
        InvalidConfigurationException exception = assertThrows(
            InvalidConfigurationException.class,
            () -> ConfigurationValidator.validateSystemConfiguration(1, 5, 14, null, tabuConfig)
        );
        
        assertTrue(exception.getMessage().contains("maxIterations debe ser al menos 1"));
    }
    
    // ========== Tests de casos extremos ==========
    
    @Test
    void testEdgeCase_MutationRateBoundaries() {
        AlgorithmConfig config = new AlgorithmConfig();
        config.setInt("populationSize", 50);
        config.setInt("generations", 100);
        
        // 0.0 debe ser válido
        config.setDouble("mutationRate", 0.0);
        assertDoesNotThrow(() -> ConfigurationValidator.validateGeneticAlgorithmConfig(config));
        
        // 1.0 debe ser válido
        config.setDouble("mutationRate", 1.0);
        assertDoesNotThrow(() -> ConfigurationValidator.validateGeneticAlgorithmConfig(config));
    }
    
    @Test
    void testEdgeCase_EliteCountZero() {
        AlgorithmConfig config = new AlgorithmConfig();
        config.setInt("populationSize", 50);
        config.setInt("generations", 100);
        config.setDouble("mutationRate", 0.1);
        config.setInt("tournamentSize", 4);
        config.setInt("eliteCount", 0);
        
        // eliteCount = 0 debe ser válido (sin elitismo)
        assertDoesNotThrow(() -> ConfigurationValidator.validateGeneticAlgorithmConfig(config));
    }
}
