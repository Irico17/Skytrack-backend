package com.equipo2b.scheduler.monitoring;

/**
 * Representa un cuello de botella en el sistema logístico.
 * 
 * <p>Un cuello de botella es un recurso (vuelo o almacén) con ocupación
 * superior al 70%, que puede causar problemas de capacidad.</p>
 * 
 * <p><b>Requisitos implementados:</b></p>
 * <ul>
 *   <li>35.5: Identificación de cuellos de botella</li>
 * </ul>
 * 
 * @param type Tipo de cuello de botella (STORAGE o FLIGHT)
 * @param resourceId ID del recurso afectado (Airport ID o Flight ID)
 * @param occupancy Nivel de ocupación del recurso (0.0 = 0%, 1.0 = 100%)
 * 
 * @see BottleneckType
 * @see CapacityMonitor
 */
public record Bottleneck(
    BottleneckType type,
    String resourceId,
    double occupancy
) {
    /**
     * Constructor con validaciones.
     * 
     * @param type Tipo de cuello de botella, no puede ser null
     * @param resourceId ID del recurso, no puede ser null
     * @param occupancy Nivel de ocupación, debe ser >= 0
     * @throws NullPointerException si type o resourceId son null
     * @throws IllegalArgumentException si occupancy es negativo
     */
    public Bottleneck {
        if (type == null) {
            throw new NullPointerException("BottleneckType cannot be null");
        }
        if (resourceId == null) {
            throw new NullPointerException("Resource ID cannot be null");
        }
        if (occupancy < 0) {
            throw new IllegalArgumentException(
                "Occupancy cannot be negative, got: " + occupancy
            );
        }
    }
    
    /**
     * Genera una descripción legible del cuello de botella.
     * 
     * @return String con descripción del cuello de botella
     */
    public String getDescription() {
        return String.format(
            "%s bottleneck at %s: %.1f%% occupancy",
            type,
            resourceId,
            occupancy * 100
        );
    }
    
    /**
     * Verifica si el cuello de botella es crítico (ocupación > 85%).
     * 
     * @return true si la ocupación supera el 85%
     */
    public boolean isCritical() {
        return occupancy > 0.85;
    }
}
