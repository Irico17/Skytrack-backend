package com.equipo2b.scheduler.monitoring;

/**
 * Tipos de cuellos de botella en el sistema logístico.
 * 
 * <p>Define los tipos de recursos que pueden convertirse en cuellos
 * de botella por alta ocupación.</p>
 * 
 * <p><b>Requisitos implementados:</b></p>
 * <ul>
 *   <li>35.5: Clasificación de cuellos de botella</li>
 * </ul>
 * 
 * @see Bottleneck
 * @see CapacityMonitor
 */
public enum BottleneckType {
    /**
     * Cuello de botella en almacén de aeropuerto.
     * Indica que un almacén tiene ocupación > 70%.
     */
    STORAGE,
    
    /**
     * Cuello de botella en vuelo.
     * Indica que un vuelo tiene ocupación > 70%.
     */
    FLIGHT
}
