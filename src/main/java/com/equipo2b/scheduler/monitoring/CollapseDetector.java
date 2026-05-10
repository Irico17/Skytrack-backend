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
    private double collapseThreshold;
    private double unserviceableThreshold;

    /** Monitor de capacidad para calcular ocupación real. Puede ser null. */
    private CapacityMonitor capacityMonitor;

    /** Constructor con umbrales por defecto y sin monitor de capacidad. */
    public CollapseDetector() {
        this(70.0, 20.0, null);
    }

    /** Constructor con monitor de capacidad real (recomendado para el backend). */
    public CollapseDetector(CapacityMonitor capacityMonitor) {
        this(70.0, 20.0, capacityMonitor);
    }
    
    /**
     * Constructor con umbrales personalizados.
     */
    public CollapseDetector(double collapseThreshold, double unserviceableThreshold) {
        this(collapseThreshold, unserviceableThreshold, null);
    }

    /**
     * Constructor completo.
     */
    public CollapseDetector(double collapseThreshold, double unserviceableThreshold, CapacityMonitor capacityMonitor) {
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
        this.capacityMonitor = capacityMonitor;
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
        // Colapso directo si fitness positivo (penalizaciones superan premios)
        if (solution.isEvaluated() && solution.getFitness() > 0) {
            String msg = "Sistema colapsado - fitness positivo: " + String.format("%.0f", solution.getFitness());
            System.out.println("\n⚠️  COLAPSO POR FITNESS: " + msg);
            return new CollapseStatus(CollapseLevel.COLLAPSED, 100.0, 100.0, msg);
        }

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

        // Usar CapacityMonitor si está disponible (métrica real)
        if (capacityMonitor != null) {
            double realOccupancy = capacityMonitor.calculateAverageFlightOccupancy(solution);
            return realOccupancy * 100.0;  // Convertir a porcentaje
        }

        // Fallback: estimar ocupación basada en fitness (menos preciso pero no requiere monitor)
        // Fitness negativo = sistema saludable (entregas a tiempo generan recompensa negativa).
        // Más negativo → más sano → menor ocupación.
        // Fitness positivo = penalizaciones superan recompensas → sistema saturado.
        double fitness = solution.getFitness();
        if (fitness >= 0) {
            return 85.0;  // Fitness >= 0 indica alta saturación
        }
        // Escala razonable: fitness de -100K+ es operación normal → baja ocupación.
        // Sólo preocuparse cuando fitness se acerca a 0 (penalizaciones crecen).
        // Mapeo: -200K → ~10%, -100K → ~20%, -10K → ~60%, -1K → ~75%
        double absFitness = Math.abs(fitness);
        if (absFitness > 100_000) {
            return 10.0 + (100_000.0 / absFitness) * 15.0;  // 10–25%
        }
        // Para fitness entre 0 y -100K, mapear linealmente a 25–80%
        return 25.0 + (1.0 - absFitness / 100_000.0) * 55.0;
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
