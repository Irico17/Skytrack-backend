package com.equipo2b.scheduler.algorithm;

import com.equipo2b.scheduler.model.ShipmentBatch;
import com.equipo2b.scheduler.model.Solution;

import java.util.List;

/**
 * Interfaz para algoritmos de optimización.
 * Permite intercambiar implementaciones (GA, Tabú, otros).
 * 
 * <p>Esta interfaz define el contrato para algoritmos de optimización
 * que resuelven el problema de planificación logística. Permite
 * implementar diferentes estrategias (Algoritmo Genético, Búsqueda Tabú, etc.)
 * de forma intercambiable siguiendo el principio Open/Closed.
 * 
 * <p><strong>Validates: Requirements 15.7, 18.3</strong>
 */
public interface OptimizationAlgorithm {
    /**
     * Optimiza una lista de lotes y retorna la mejor solución encontrada.
     * 
     * <p>Este método es el punto de entrada principal para ejecutar
     * el algoritmo de optimización. Recibe una lista de lotes de maletas
     * y retorna una solución completa con rutas asignadas.
     * 
     * @param batches Lista de lotes de maletas a planificar
     * @return Mejor solución encontrada por el algoritmo
     * @throws NullPointerException si batches es null
     */
    Solution optimize(List<ShipmentBatch> batches);
    
    /**
     * Configura parámetros del algoritmo.
     * 
     * <p>Permite ajustar los parámetros del algoritmo en tiempo de ejecución
     * sin necesidad de crear nuevas instancias. Los parámetros específicos
     * dependen de la implementación concreta.
     * 
     * @param config Configuración con parámetros del algoritmo
     * @throws NullPointerException si config es null
     */
    void configure(AlgorithmConfig config);
}
