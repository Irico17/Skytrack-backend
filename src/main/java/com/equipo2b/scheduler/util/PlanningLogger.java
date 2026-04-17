package com.equipo2b.scheduler.util;

import com.equipo2b.scheduler.model.Flight;
import com.equipo2b.scheduler.model.ShipmentBatch;
import com.equipo2b.scheduler.monitoring.CollapseStatus;

import java.time.ZonedDateTime;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Logger centralizado para operaciones de planificación del sistema Tasf.B2B.
 * 
 * <p>Proporciona métodos especializados para registrar eventos del sistema:
 * - Inicio y fin de ciclos de planificación
 * - Mejoras de fitness durante optimización
 * - Cancelaciones de vuelos y replanificaciones
 * - Fallos en generación de rutas
 * - Detección de colapso logístico
 * 
 * <p>Utiliza java.util.logging como backend de logging para mantener
 * compatibilidad con el ecosistema Java estándar.
 * 
 * <p><strong>Validates: Requirements 16.3, 16.4</strong>
 * 
 * @see java.util.logging.Logger
 */
public class PlanningLogger {
    private static final Logger logger = Logger.getLogger("TasfB2B");
    
    /**
     * Registra un mensaje informativo.
     * 
     * <p>Usado para eventos normales del sistema como inicio de ciclos,
     * finalización exitosa de operaciones, etc.
     * 
     * @param message Mensaje a registrar
     * 
     * <p><strong>Validates: Requirements 16.3</strong>
     */
    public static void logInfo(String message) {
        logger.info(message);
    }
    
    /**
     * Registra una advertencia.
     * 
     * <p>Usado para situaciones que requieren atención pero no impiden
     * la operación del sistema, como fallos en generación de rutas,
     * valores de configuración extremos, etc.
     * 
     * @param message Mensaje de advertencia
     * 
     * <p><strong>Validates: Requirements 16.3</strong>
     */
    public static void logWarning(String message) {
        logger.warning(message);
    }
    
    /**
     * Registra un error con excepción asociada.
     * 
     * <p>Usado para errores graves que impiden operaciones críticas
     * del sistema.
     * 
     * @param message Mensaje de error
     * @param t Excepción asociada (puede ser null)
     * 
     * <p><strong>Validates: Requirements 16.3</strong>
     */
    public static void logError(String message, Throwable t) {
        logger.log(Level.SEVERE, message, t);
    }
    
    /**
     * Registra el inicio de un ciclo de planificación.
     * 
     * @param cycleNumber Número del ciclo
     * @param currentTime Tiempo actual de la simulación
     * @param batchCount Cantidad de lotes a planificar
     * 
     * <p><strong>Validates: Requirements 16.3</strong>
     */
    public static void logPlanningCycleStart(int cycleNumber, ZonedDateTime currentTime, int batchCount) {
        logger.info(String.format(
            "=== CICLO DE PLANIFICACIÓN %d INICIADO === Tiempo: %s, Lotes: %d",
            cycleNumber, currentTime, batchCount
        ));
    }
    
    /**
     * Registra el fin de un ciclo de planificación.
     * 
     * @param cycleNumber Número del ciclo
     * @param fitness Fitness de la solución obtenida
     * @param executionTimeMs Tiempo de ejecución en milisegundos
     * 
     * <p><strong>Validates: Requirements 16.4</strong>
     */
    public static void logPlanningCycleEnd(int cycleNumber, double fitness, long executionTimeMs) {
        logger.info(String.format(
            "=== CICLO DE PLANIFICACIÓN %d COMPLETADO === Fitness: %.2f, Tiempo: %d ms",
            cycleNumber, fitness, executionTimeMs
        ));
    }
    
    /**
     * Registra una mejora de fitness durante optimización.
     * 
     * @param generation Generación o iteración actual
     * @param oldFitness Fitness anterior
     * @param newFitness Nuevo fitness mejorado
     * 
     * <p><strong>Validates: Requirements 16.4</strong>
     */
    public static void logFitnessImprovement(int generation, double oldFitness, double newFitness) {
        double improvement = oldFitness - newFitness;
        double improvementPercent = (improvement / oldFitness) * 100;
        logger.info(String.format(
            "Generación %d: Fitness mejorado de %.2f a %.2f (mejora: %.2f, %.1f%%)",
            generation, oldFitness, newFitness, improvement, improvementPercent
        ));
    }
    
    /**
     * Registra una cancelación de vuelo.
     * 
     * @param flight Vuelo cancelado
     * @param affectedBatches Cantidad de lotes afectados
     * 
     * <p><strong>Validates: Requirements 16.3</strong>
     */
    public static void logCancellation(Flight flight, int affectedBatches) {
        logger.info(String.format(
            "CANCELACIÓN: Vuelo %s (%s → %s) - %d lotes afectados",
            flight.flightId(), 
            flight.origin().id(), 
            flight.destination().id(),
            affectedBatches
        ));
    }
    
    /**
     * Registra el inicio de una replanificación de emergencia.
     * 
     * @param cancelledFlightId ID del vuelo cancelado
     * @param affectedBatchCount Cantidad de lotes afectados
     * 
     * <p><strong>Validates: Requirements 16.3</strong>
     */
    public static void logReplanningStart(String cancelledFlightId, int affectedBatchCount) {
        logger.info(String.format(
            "=== REPLANIFICACIÓN INICIADA === Vuelo: %s, Lotes afectados: %d",
            cancelledFlightId, affectedBatchCount
        ));
    }
    
    /**
     * Registra el resultado de una replanificación.
     * 
     * @param replanedCount Cantidad de lotes replanificados exitosamente
     * @param unreplannableCount Cantidad de lotes no replanificables
     * 
     * <p><strong>Validates: Requirements 16.4</strong>
     */
    public static void logReplanningResult(int replanedCount, int unreplannableCount) {
        if (unreplannableCount == 0) {
            logger.info(String.format(
                "=== REPLANIFICACIÓN EXITOSA === %d lotes replanificados",
                replanedCount
            ));
        } else {
            logger.warning(String.format(
                "=== REPLANIFICACIÓN PARCIAL === %d lotes replanificados, %d no replanificables",
                replanedCount, unreplannableCount
            ));
        }
    }
    
    /**
     * Registra un fallo en la generación de ruta para un lote.
     * 
     * @param batch Lote para el cual no se pudo generar ruta
     * @param attempts Número de intentos realizados
     * 
     * <p><strong>Validates: Requirements 16.3</strong>
     */
    public static void logRouteGenerationFailure(ShipmentBatch batch, int attempts) {
        logger.warning(String.format(
            "FALLO GENERACIÓN RUTA: Lote %s después de %d intentos (origen: %s, destino: %s, cantidad: %d)",
            batch.batchId(), attempts, batch.origin().id(), batch.destination().id(), batch.quantity()
        ));
    }
    
    /**
     * Registra detección de colapso logístico.
     * 
     * @param status Estado de colapso detectado
     * 
     * <p><strong>Validates: Requirements 16.3</strong>
     */
    public static void logCollapseDetection(CollapseStatus status) {
        logger.warning(String.format(
            "⚠ COLAPSO DETECTADO: %s - Ocupación: %.1f%%, No planificables: %.1f%%",
            status.level(), status.occupancyPercentage(), status.unserviceablePercentage()
        ));
    }
}
