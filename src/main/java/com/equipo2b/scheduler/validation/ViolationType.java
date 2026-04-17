package com.equipo2b.scheduler.validation;

/**
 * Tipos de violaciones de restricciones en una solución.
 * 
 * **Validates: Requirements 13.3**
 */
public enum ViolationType {
    /**
     * Capacidad de vuelo excedida.
     */
    FLIGHT_CAPACITY,
    
    /**
     * Capacidad de almacén excedida.
     */
    STORAGE_CAPACITY,
    
    /**
     * Violación de SLA (llegada tardía).
     */
    SLA_VIOLATION,
    
    /**
     * Tiempo de escala insuficiente entre vuelos.
     */
    LAYOVER_VIOLATION,
    
    /**
     * Duración de vuelo incorrecta.
     */
    FLIGHT_DURATION
}
