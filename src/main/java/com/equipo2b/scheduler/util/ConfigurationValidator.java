package com.equipo2b.scheduler.util;

import com.equipo2b.scheduler.algorithm.AlgorithmConfig;
import com.equipo2b.scheduler.exception.InvalidConfigurationException;

/**
 * Validador de configuración del sistema Tasf.B2B.
 * 
 * <p>Valida parámetros críticos del sistema antes de iniciar operaciones:
 * - Restricción Sa > Ta (Requisito 34.1, 34.2)
 * - Rangos válidos de parámetros de algoritmos (Requisito 34.3)
 * - Valor positivo de K (Requisito 34.4)
 * - Valores extremos que pueden causar problemas (Requisito 34.5)
 * 
 * <p>Lanza InvalidConfigurationException cuando detecta configuraciones inválidas.
 * Emite warnings para valores extremos pero válidos.
 * 
 * <p><strong>Validates: Requirements 34.1, 34.2, 34.3, 34.4, 34.5</strong>
 * 
 * @see com.equipo2b.scheduler.exception.InvalidConfigurationException
 * @see com.equipo2b.scheduler.algorithm.AlgorithmConfig
 */
public class ConfigurationValidator {
    
    // Valores referenciales recomendados
    private static final int RECOMMENDED_TA = 1;  // minutos
    private static final int RECOMMENDED_SA = 5;  // minutos
    
    // Umbrales para warnings
    private static final int MIN_SA_WARNING = 2;  // Sa muy pequeño aumenta riesgo de caída
    private static final int MAX_SA_WARNING = 15; // Sa muy grande aumenta riesgo de colapso
    
    /**
     * Valida la restricción fundamental Sa > Ta.
     * 
     * <p>Esta restricción es crítica para evitar conflictos de ejecución concurrente.
     * Si Sa ≤ Ta, el siguiente ciclo podría iniciar antes de que termine el anterior.
     * 
     * @param Ta Tiempo máximo de ejecución del algoritmo (minutos)
     * @param Sa Salto entre ejecuciones del algoritmo (minutos)
     * @throws InvalidConfigurationException si Sa ≤ Ta
     * 
     * <p><strong>Validates: Requirements 34.1, 34.2</strong>
     */
    public static void validateSaGreaterThanTa(int Ta, int Sa) throws InvalidConfigurationException {
        if (Sa <= Ta) {
            throw new InvalidConfigurationException(String.format(
                "Configuración inválida: Sa (%d min) debe ser estrictamente mayor que Ta (%d min). " +
                "Valores referenciales recomendados: Ta=%d min, Sa=%d min",
                Sa, Ta, RECOMMENDED_TA, RECOMMENDED_SA
            ));
        }
    }
    
    /**
     * Valida que K sea positivo.
     * 
     * <p>K es la constante de proporcionalidad que determina cuántos datos
     * se consumen por ciclo (Sc = Sa × K). Debe ser positivo.
     * 
     * @param K Constante de proporcionalidad
     * @throws InvalidConfigurationException si K ≤ 0
     * 
     * <p><strong>Validates: Requirements 34.4</strong>
     */
    public static void validateKPositive(int K) throws InvalidConfigurationException {
        if (K <= 0) {
            throw new InvalidConfigurationException(String.format(
                "Configuración inválida: K (%d) debe ser positivo. " +
                "Valores típicos: K=1 (operación día a día), K=14-23 (simulación periodo), K=75 (simulación colapso)",
                K
            ));
        }
    }
    
    /**
     * Emite warnings sobre valores extremos de Sa.
     * 
     * <p>Valores muy pequeños de Sa aumentan el riesgo de caída del sistema
     * por ejecuciones muy frecuentes. Valores muy grandes aumentan la probabilidad
     * de colapso por acumulación excesiva de pedidos.
     * 
     * @param Sa Salto entre ejecuciones del algoritmo (minutos)
     * 
     * <p><strong>Validates: Requirements 34.3, 34.5</strong>
     */
    public static void warnExtremeValues(int Sa) {
        if (Sa < MIN_SA_WARNING) {
            PlanningLogger.logWarning(String.format(
                "⚠ ADVERTENCIA: Sa=%d min es muy pequeño. " +
                "Valores pequeños aumentan el riesgo de caída del sistema por ejecuciones muy frecuentes. " +
                "Valor recomendado: Sa=%d min",
                Sa, RECOMMENDED_SA
            ));
        } else if (Sa > MAX_SA_WARNING) {
            PlanningLogger.logWarning(String.format(
                "⚠ ADVERTENCIA: Sa=%d min es muy grande. " +
                "Valores grandes aumentan la probabilidad de colapso por acumulación de pedidos. " +
                "Valor recomendado: Sa=%d min",
                Sa, RECOMMENDED_SA
            ));
        }
    }
    
    /**
     * Valida rangos de parámetros del Algoritmo Genético.
     * 
     * @param config Configuración del algoritmo
     * @throws InvalidConfigurationException si algún parámetro está fuera de rango
     * 
     * <p><strong>Validates: Requirements 34.3</strong>
     */
    public static void validateGeneticAlgorithmConfig(AlgorithmConfig config) throws InvalidConfigurationException {
        // Validar tamaño de población
        int populationSize = config.getInt("populationSize", 50);
        if (populationSize < 2) {
            throw new InvalidConfigurationException(
                "populationSize debe ser al menos 2 (valor: " + populationSize + ")"
            );
        }
        if (populationSize > 1000) {
            PlanningLogger.logWarning(
                "⚠ populationSize=" + populationSize + " es muy grande, puede afectar rendimiento"
            );
        }
        
        // Validar número de generaciones
        int generations = config.getInt("generations", 100);
        if (generations < 1) {
            throw new InvalidConfigurationException(
                "generations debe ser al menos 1 (valor: " + generations + ")"
            );
        }
        if (generations > 1000) {
            PlanningLogger.logWarning(
                "⚠ generations=" + generations + " es muy grande, puede afectar rendimiento"
            );
        }
        
        // Validar tasa de mutación
        double mutationRate = config.getDouble("mutationRate", 0.1);
        if (mutationRate < 0.0 || mutationRate > 1.0) {
            throw new InvalidConfigurationException(
                "mutationRate debe estar entre 0.0 y 1.0 (valor: " + mutationRate + ")"
            );
        }
        
        // Validar tamaño de torneo
        int tournamentSize = config.getInt("tournamentSize", 4);
        if (tournamentSize < 2) {
            throw new InvalidConfigurationException(
                "tournamentSize debe ser al menos 2 (valor: " + tournamentSize + ")"
            );
        }
        if (tournamentSize > populationSize) {
            throw new InvalidConfigurationException(
                "tournamentSize (" + tournamentSize + ") no puede ser mayor que populationSize (" + populationSize + ")"
            );
        }
        
        // Validar elite count
        int eliteCount = config.getInt("eliteCount", 2);
        if (eliteCount < 0) {
            throw new InvalidConfigurationException(
                "eliteCount no puede ser negativo (valor: " + eliteCount + ")"
            );
        }
        if (eliteCount >= populationSize) {
            throw new InvalidConfigurationException(
                "eliteCount (" + eliteCount + ") debe ser menor que populationSize (" + populationSize + ")"
            );
        }
    }
    
    /**
     * Valida rangos de parámetros de Búsqueda Tabú.
     * 
     * @param config Configuración del algoritmo
     * @throws InvalidConfigurationException si algún parámetro está fuera de rango
     * 
     * <p><strong>Validates: Requirements 34.3</strong>
     */
    public static void validateTabuSearchConfig(AlgorithmConfig config) throws InvalidConfigurationException {
        // Validar número de iteraciones
        int maxIterations = config.getInt("maxIterations", 200);
        if (maxIterations < 1) {
            throw new InvalidConfigurationException(
                "maxIterations debe ser al menos 1 (valor: " + maxIterations + ")"
            );
        }
        if (maxIterations > 10000) {
            PlanningLogger.logWarning(
                "⚠ maxIterations=" + maxIterations + " es muy grande, puede afectar rendimiento"
            );
        }
        
        // Validar tabu tenure
        int tabuTenure = config.getInt("tabuTenure", 15);
        if (tabuTenure < 1) {
            throw new InvalidConfigurationException(
                "tabuTenure debe ser al menos 1 (valor: " + tabuTenure + ")"
            );
        }
        if (tabuTenure > maxIterations / 2) {
            PlanningLogger.logWarning(
                "⚠ tabuTenure=" + tabuTenure + " es muy grande respecto a maxIterations=" + maxIterations
            );
        }
        
        // Validar tamaño de vecindario
        int neighborhoodSize = config.getInt("neighborhoodSize", 20);
        if (neighborhoodSize < 1) {
            throw new InvalidConfigurationException(
                "neighborhoodSize debe ser al menos 1 (valor: " + neighborhoodSize + ")"
            );
        }
    }
    
    /**
     * Valida configuración completa del sistema.
     * 
     * <p>Realiza todas las validaciones necesarias antes de iniciar operaciones.
     * 
     * @param Ta Tiempo máximo de algoritmo (minutos)
     * @param Sa Salto entre ejecuciones (minutos)
     * @param K Constante de proporcionalidad
     * @param gaConfig Configuración del Algoritmo Genético (puede ser null)
     * @param tabuConfig Configuración de Búsqueda Tabú (puede ser null)
     * @throws InvalidConfigurationException si alguna validación falla
     * 
     * <p><strong>Validates: Requirements 34.1, 34.2, 34.3, 34.4, 34.5</strong>
     */
    public static void validateSystemConfiguration(
            int Ta, int Sa, int K,
            AlgorithmConfig gaConfig,
            AlgorithmConfig tabuConfig) throws InvalidConfigurationException {
        
        // Validaciones críticas
        validateSaGreaterThanTa(Ta, Sa);
        validateKPositive(K);
        
        // Warnings sobre valores extremos
        warnExtremeValues(Sa);
        
        // Validar configuraciones de algoritmos si se proporcionan
        if (gaConfig != null) {
            validateGeneticAlgorithmConfig(gaConfig);
        }
        
        if (tabuConfig != null) {
            validateTabuSearchConfig(tabuConfig);
        }
        
        // Log de configuración validada
        PlanningLogger.logInfo(String.format(
            "✓ Configuración validada: Ta=%d min, Sa=%d min, K=%d, Sc=%d min",
            Ta, Sa, K, Sa * K
        ));
    }
}
