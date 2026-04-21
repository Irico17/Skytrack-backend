package com.equipo2b.scheduler.algorithm;

/**
 * Tipos de algoritmos metaheurísticos disponibles para planificación.
 * 
 * <p>El sistema soporta dos algoritmos según requisitos del caso de estudio:
 * <ul>
 *   <li>GATS: Algoritmo Genético + Búsqueda Tabú (híbrido)</li>
 *   <li>TABU_PURE: Búsqueda Tabú pura (standalone)</li>
 * </ul>
 * 
 * <p><strong>Validates: Caso de estudio punto a, b - Dos algoritmos metaheurísticos</strong>
 */
public enum AlgorithmType {
    /**
     * GATS: Genetic Algorithm + Tabu Search (híbrido).
     * 
     * <p>Proceso:
     * <ol>
     *   <li>Algoritmo Genético genera población inicial y evoluciona</li>
     *   <li>Búsqueda Tabú refina la mejor solución del GA</li>
     * </ol>
     * 
     * <p>Ventajas: Exploración global (GA) + explotación local (Tabú)
     */
    GATS("Genetic Algorithm + Tabu Search", 
         "Híbrido: GA genera población inicial, Tabu refina mejor solución"),
    
    /**
     * Tabu Search Puro (standalone).
     * 
     * <p>Proceso:
     * <ol>
     *   <li>Genera solución inicial constructiva</li>
     *   <li>Aplica búsqueda tabú desde el inicio</li>
     * </ol>
     * 
     * <p>Ventajas: Más rápido, menos memoria, búsqueda local intensiva
     */
    TABU_PURE("Tabu Search Puro", 
              "Standalone: Tabu genera solución inicial y optimiza directamente");
    
    private final String displayName;
    private final String description;
    
    AlgorithmType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }
    
    /**
     * Obtiene el nombre para mostrar del algoritmo.
     * 
     * @return Nombre legible del algoritmo
     */
    public String getDisplayName() {
        return displayName;
    }
    
    /**
     * Obtiene la descripción del algoritmo.
     * 
     * @return Descripción detallada del funcionamiento
     */
    public String getDescription() {
        return description;
    }
}
