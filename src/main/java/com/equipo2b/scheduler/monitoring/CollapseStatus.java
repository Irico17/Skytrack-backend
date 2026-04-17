package com.equipo2b.scheduler.monitoring;

/**
 * Estado de colapso del sistema logístico.
 * 
 * <p>Record inmutable que representa el estado actual de saturación del sistema,
 * incluyendo nivel de colapso, porcentajes de ocupación y pedidos no atendibles.</p>
 * 
 * <p><b>Requisitos implementados:</b></p>
 * <ul>
 *   <li>31.3: Representación del nivel de colapso</li>
 *   <li>31.4: Porcentaje de ocupación del sistema</li>
 *   <li>31.5: Porcentaje de pedidos no atendibles</li>
 * </ul>
 * 
 * @param level Nivel de colapso actual
 * @param occupancyPercentage Porcentaje de ocupación promedio del sistema (0-100)
 * @param unserviceablePercentage Porcentaje de pedidos no atendibles (0-100)
 * @param message Mensaje descriptivo del estado
 * 
 * @see CollapseLevel
 * @see CollapseDetector
 */
public record CollapseStatus(
    CollapseLevel level,
    double occupancyPercentage,
    double unserviceablePercentage,
    String message
) {
    /**
     * Constructor con validaciones.
     * 
     * @param level Nivel de colapso, no puede ser null
     * @param occupancyPercentage Porcentaje de ocupación, debe estar entre 0 y 100
     * @param unserviceablePercentage Porcentaje de no atendibles, debe estar entre 0 y 100
     * @param message Mensaje descriptivo, no puede ser null
     * @throws IllegalArgumentException si los porcentajes están fuera de rango
     * @throws NullPointerException si level o message son null
     */
    public CollapseStatus {
        if (level == null) {
            throw new NullPointerException("CollapseLevel cannot be null");
        }
        if (message == null) {
            throw new NullPointerException("Message cannot be null");
        }
        if (occupancyPercentage < 0 || occupancyPercentage > 100) {
            throw new IllegalArgumentException(
                "Occupancy percentage must be between 0 and 100, got: " + occupancyPercentage
            );
        }
        if (unserviceablePercentage < 0 || unserviceablePercentage > 100) {
            throw new IllegalArgumentException(
                "Unserviceable percentage must be between 0 and 100, got: " + unserviceablePercentage
            );
        }
    }
    
    /**
     * Verifica si el sistema está en estado de colapso.
     * 
     * @return true si el nivel es COLLAPSED
     */
    public boolean isCollapsed() {
        return level == CollapseLevel.COLLAPSED;
    }
    
    /**
     * Verifica si el sistema está en estado crítico o colapsado.
     * 
     * @return true si el nivel es CRITICAL o COLLAPSED
     */
    public boolean isCritical() {
        return level == CollapseLevel.CRITICAL || level == CollapseLevel.COLLAPSED;
    }
    
    /**
     * Genera un resumen legible del estado.
     * 
     * @return String con resumen del estado de colapso
     */
    public String getSummary() {
        return String.format(
            "Estado: %s | Ocupación: %.1f%% | No atendibles: %.1f%% | %s",
            level,
            occupancyPercentage,
            unserviceablePercentage,
            message
        );
    }
}
