package com.equipo2b.scheduler.monitoring;

/**
 * Niveles de colapso del sistema logístico.
 * 
 * <p>Define los estados de saturación del sistema desde operación normal
 * hasta colapso completo.</p>
 * 
 * <p><b>Requisitos implementados:</b></p>
 * <ul>
 *   <li>31.3: Definición de niveles de colapso</li>
 * </ul>
 * 
 * @see CollapseDetector
 * @see CollapseStatus
 */
public enum CollapseLevel {
    /**
     * Operación normal - sistema funcionando dentro de parámetros normales.
     * Ocupación < 60%
     */
    NORMAL,
    
    /**
     * Advertencia - sistema acercándose a límites de capacidad.
     * Ocupación entre 60% y 70%
     */
    WARNING,
    
    /**
     * Crítico - sistema en riesgo inminente de colapso.
     * Ocupación entre 70% y 80%
     */
    CRITICAL,
    
    /**
     * Colapsado - sistema saturado, no puede procesar pedidos a tiempo.
     * Ocupación >= 80% o pedidos no atendibles > 20%
     */
    COLLAPSED
}
