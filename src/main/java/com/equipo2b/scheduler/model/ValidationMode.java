package com.equipo2b.scheduler.model;

/**
 * Controla el modo de validación del sistema.
 * 
 * - STRICT: Validaciones estrictas según especificaciones del diseño (producción)
 * - LENIENT: Validaciones relajadas para datos reales de prueba (testing)
 * 
 * Por defecto usa STRICT para garantizar cumplimiento de especificaciones.
 */
public class ValidationMode {
    private static Mode currentMode = Mode.STRICT;
    
    public enum Mode {
        /**
         * Modo estricto (producción):
         * - Capacidad aeropuertos: 500-800
         * - Capacidad vuelos intra: 150-250
         * - Capacidad vuelos inter: 150-400
         * - Duración vuelos intra: exactamente 12h
         * - Duración vuelos inter: exactamente 24h
         */
        STRICT,
        
        /**
         * Modo permisivo (testing con datos reales):
         * - Capacidad aeropuertos: 300-1000
         * - Capacidad vuelos: 100-500
         * - Duración vuelos: 0.5h - 48h
         */
        LENIENT
    }
    
    public static Mode getMode() {
        return currentMode;
    }
    
    public static void setMode(Mode mode) {
        currentMode = mode;
    }
    
    public static boolean isStrict() {
        return currentMode == Mode.STRICT;
    }
    
    public static boolean isLenient() {
        return currentMode == Mode.LENIENT;
    }
}
