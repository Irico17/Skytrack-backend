package com.equipo2b.scheduler.monitoring;

/**
 * Enum que representa los tres estados del indicador semáforo.
 * 
 * - GREEN: Operación normal, recursos dentro de rangos seguros
 * - AMBER: Precaución, recursos acercándose a límites
 * - RED: Crítico, recursos en niveles peligrosos
 * 
 * **Validates: Requirements 32.1**
 */
public enum TrafficLightColor {
    /**
     * Verde: operación normal, recursos dentro de rangos seguros.
     */
    GREEN,
    
    /**
     * Ámbar: precaución, recursos acercándose a límites.
     */
    AMBER,
    
    /**
     * Rojo: crítico, recursos en niveles peligrosos.
     */
    RED
}
