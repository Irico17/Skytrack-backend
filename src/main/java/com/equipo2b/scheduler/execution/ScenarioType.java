package com.equipo2b.scheduler.execution;

/**
 * Tipos de escenarios de simulación soportados por el sistema.
 * 
 * Cada escenario tiene diferentes parámetros de aceleración (K) y configuración:
 * - DAY_TO_DAY: Operación en tiempo real (K=1)
 * - PERIOD_SIMULATION: Simulación de 3-5 días (K=14-23)
 * - COLLAPSE_SIMULATION: Simulación hasta colapso (K=75)
 * 
 * **Validates: Requirements 21.1-21.3**
 */
public enum ScenarioType {
    /**
     * Escenario 1: Operación Día a Día (K=1).
     * - Simula operación en tiempo real
     * - 100 lotes aproximadamente
     * - Configuración rápida de algoritmos
     */
    DAY_TO_DAY(
        1,    // K: Factor de aceleración
        2,    // Ta: Tiempo máximo de algoritmo (minutos)
        5,    // Sa: Salto entre ejecuciones (minutos)
        "Escenario 1: Operación Día a Día (K=1)"
    ),
    
    /**
     * Escenario 2: Simulación de Período (K=14).
     * - Simula 3-5 días de operación
     * - 500 lotes aproximadamente
     * - Configuración balanceada de algoritmos
     * - Debe completarse en 30-90 minutos reales
     */
    PERIOD_SIMULATION(
        14,   // K: Factor de aceleración
        2,    // Ta: Tiempo máximo de algoritmo (minutos)
        5,    // Sa: Salto entre ejecuciones (minutos)
        "Escenario 2: Simulación de Período (K=14)"
    ),
    
    /**
     * Escenario 3: Simulación hasta Colapso (K=75).
     * - Simula 2.5 meses de operación
     * - 2000 lotes aproximadamente
     * - Configuración intensiva de algoritmos
     * - Objetivo: Detectar colapso logístico
     */
    COLLAPSE_SIMULATION(
        75,   // K: Factor de aceleración
        2,    // Ta: Tiempo máximo de algoritmo (minutos)
        5,    // Sa: Salto entre ejecuciones (minutos)
        "Escenario 3: Simulación hasta Colapso (K=75)"
    );
    
    private final int K;           // Constante de proporcionalidad
    private final int Ta;          // Tiempo algoritmo (minutos)
    private final int Sa;          // Salto algoritmo (minutos)
    private final String description;
    
    ScenarioType(int K, int Ta, int Sa, String description) {
        this.K = K;
        this.Ta = Ta;
        this.Sa = Sa;
        this.description = description;
    }
    
    /**
     * Obtiene el factor de aceleración K.
     * 
     * @return Factor K
     */
    public int getK() {
        return K;
    }
    
    /**
     * Obtiene el tiempo máximo de algoritmo Ta (minutos).
     * 
     * @return Tiempo Ta
     */
    public int getTa() {
        return Ta;
    }
    
    /**
     * Obtiene el salto entre ejecuciones Sa (minutos).
     * 
     * @return Salto Sa
     */
    public int getSa() {
        return Sa;
    }
    
    /**
     * Calcula el salto de consumo Sc = Sa × K.
     * 
     * @return Salto de consumo Sc (minutos)
     */
    public int getSc() {
        return Sa * K;
    }
    
    /**
     * Obtiene la descripción del escenario.
     * 
     * @return Descripción
     */
    public String getDescription() {
        return description;
    }
    
    @Override
    public String toString() {
        return String.format("%s (K=%d, Ta=%d min, Sa=%d min, Sc=%d min)",
            description, K, Ta, Sa, getSc());
    }
}
