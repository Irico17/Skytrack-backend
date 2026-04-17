package com.equipo2b.scheduler.monitoring;

import com.equipo2b.scheduler.model.*;

import java.util.*;

/**
 * Detector de colapso logístico del sistema.
 * 
 * <p>Monitorea la saturación del sistema y detecta cuándo se alcanza
 * el punto de colapso donde no se pueden procesar pedidos a tiempo.</p>
 * 
 * <p><b>Requisitos implementados:</b></p>
 * <ul>
 *   <li>31.3: Detección de niveles de colapso</li>
 *   <li>31.5: Umbrales configurables de colapso</li>
 * </ul>
 * 
 * @see CollapseLevel
 * @see CollapseStatus
 */
public class CollapseDetector {
    
    // Umbrales configurables
    private double collapseThreshold;  // Umbral de colapso (60-80%)
    private double unserviceableThreshold;  // Umbral de pedidos no atendibles (20%)
    
    /**
     * Constructor con umbrales por defecto.
     * Umbral de colapso: 70%
     * Umbral de no atendibles: 20%
     */
    public CollapseDetector() {
        this(70.0, 20.0);
    }
    
    /**
     * Constructor con umbrales personalizados.
     * 
     * @param collapseThreshold Umbral de colapso entre 60 y 80
     * @param unserviceableThreshold Umbral de pedidos no atendibles
     * @throws IllegalArgumentException si los umbrales están fuera de rango
     */
    public CollapseDetector(double collapseThreshold, double unserviceableThreshold) {
        if (collapseThreshold < 60 || collapseThreshold > 80) {
            throw new IllegalArgumentException(
                "Collapse threshold must be between 60 and 80, got: " + collapseThreshold
            );
        }
        if (unserviceableThreshold < 0 || unserviceableThreshold > 100) {
            throw new IllegalArgumentException(
                "Unserviceable threshold must be between 0 and 100, got: " + unserviceableThreshold
            );
        }
        this.collapseThreshold = collapseThreshold;
        this.unserviceableThreshold = unserviceableThreshold;
    }
    
    /**
     * Evalúa el estado de colapso del sistema.
     * 
     * <p>Proceso:</p>
     * <ol>
     *   <li>Calcular saturación promedio del sistema</li>
     *   <li>Calcular porcentaje de pedidos no atendibles</li>
     *   <li>Determinar CollapseLevel según umbrales</li>
     *   <li>Emitir alerta si saturación >= umbral de colapso</li>
     *   <li>Declarar colapso si pedidos no atendibles > 20%</li>
     * </ol>
     * 
     * @param solution Solución actual del sistema
     * @param totalBatches Total de lotes procesados
     * @param failedBatches Lotes que no pudieron ser planificados
     * @return CollapseStatus con el estado actual
     * 
     * **Validates: Requirements 31.1, 31.2, 31.3, 31.4, 31.5**
     */
    public CollapseStatus evaluateCollapse(Solution solution, int totalBatches, int failedBatches) {
        // 1. Calcular saturación promedio del sistema
        double occupancy = calculateSystemOccupancy(solution);
        
        // 2. Calcular porcentaje de pedidos no atendibles
        double unserviceablePercentage = totalBatches > 0 
            ? (failedBatches * 100.0 / totalBatches) 
            : 0.0;
        
        // 3. Determinar CollapseLevel según umbrales
        CollapseLevel level = determineCollapseLevel(occupancy, unserviceablePercentage);
        
        // 4. Generar mensaje descriptivo
        String message = generateMessage(level, occupancy, unserviceablePercentage);
        
        // 5. Emitir alerta si es necesario
        if (level == CollapseLevel.CRITICAL || level == CollapseLevel.COLLAPSED) {
            System.out.println("\n⚠️  ALERTA DE COLAPSO: " + message);
        }
        
        return new CollapseStatus(level, occupancy, unserviceablePercentage, message);
    }
    
    /**
     * Calcula la ocupación promedio del sistema.
     * 
     * @param solution Solución actual
     * @return Porcentaje de ocupación (0-100)
     */
    private double calculateSystemOccupancy(Solution solution) {
        if (solution.getRoutes().isEmpty()) {
            return 0.0;
        }
        
        // Calcular ocupación basada en fitness
        // Fitness negativo indica penalizaciones (violaciones)
        // Fitness más negativo = mayor ocupación/saturación
        double fitness = solution.getFitness();
        
        // Normalizar fitness a porcentaje de ocupación
        // Asumimos que fitness < -100000 indica alta saturación
        if (fitness >= 0) {
            return 0.0;  // Sin penalizaciones = baja ocupación
        }
        
        // Mapear fitness negativo a ocupación
        // -50000 = 50%, -100000 = 70%, -200000 = 85%, etc.
        double occupancy = Math.min(100.0, Math.abs(fitness) / 2000.0);
        
        return occupancy;
    }
    
    /**
     * Determina el nivel de colapso según umbrales.
     * 
     * @param occupancy Porcentaje de ocupación
     * @param unserviceablePercentage Porcentaje de no atendibles
     * @return CollapseLevel correspondiente
     */
    private CollapseLevel determineCollapseLevel(double occupancy, double unserviceablePercentage) {
        // Colapso si pedidos no atendibles > umbral
        if (unserviceablePercentage > unserviceableThreshold) {
            return CollapseLevel.COLLAPSED;
        }
        
        // Colapso si ocupación >= 80%
        if (occupancy >= 80.0) {
            return CollapseLevel.COLLAPSED;
        }
        
        // Crítico si ocupación >= umbral de colapso
        if (occupancy >= collapseThreshold) {
            return CollapseLevel.CRITICAL;
        }
        
        // Advertencia si ocupación >= 60%
        if (occupancy >= 60.0) {
            return CollapseLevel.WARNING;
        }
        
        // Normal
        return CollapseLevel.NORMAL;
    }
    
    /**
     * Genera mensaje descriptivo del estado.
     * 
     * @param level Nivel de colapso
     * @param occupancy Porcentaje de ocupación
     * @param unserviceablePercentage Porcentaje de no atendibles
     * @return Mensaje descriptivo
     */
    private String generateMessage(CollapseLevel level, double occupancy, double unserviceablePercentage) {
        return switch (level) {
            case NORMAL -> "Sistema operando normalmente";
            case WARNING -> "Sistema acercándose a límites de capacidad";
            case CRITICAL -> "Sistema en riesgo inminente de colapso";
            case COLLAPSED -> unserviceablePercentage > unserviceableThreshold
                ? "Sistema colapsado - demasiados pedidos no atendibles"
                : "Sistema colapsado - saturación de capacidad";
        };
    }
    
    /**
     * Obtiene el umbral de colapso configurado.
     * 
     * @return Umbral de colapso (60-80%)
     */
    public double getCollapseThreshold() {
        return collapseThreshold;
    }
    
    /**
     * Obtiene el umbral de pedidos no atendibles.
     * 
     * @return Umbral de no atendibles (%)
     */
    public double getUnserviceableThreshold() {
        return unserviceableThreshold;
    }
}
