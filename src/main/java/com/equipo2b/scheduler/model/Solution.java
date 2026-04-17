package com.equipo2b.scheduler.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Representa una solución completa al problema de planificación.
 * Estructura: Map<BatchId, AssignedRoute> para acceso O(1).
 * 
 * Justificación del diseño:
 * - HashMap permite acceso O(1) a la ruta de cualquier lote
 * - Facilita crossover uniforme: intercambio de rutas individuales entre padres
 * - Facilita mutación: regeneración de rutas específicas sin afectar otras
 * - Mantiene "Equipaje con Dueño": cada lote tiene su ruta independiente
 * - Permite división de lotes: múltiples entradas con mismo clientId pero diferentes rutas
 * 
 * **Validates: Requirements 8.1, 8.2, 8.3, 8.4, 8.5**
 */
public final class Solution {
    private final Map<String, AssignedRoute> routes;  // BatchId -> AssignedRoute
    private double fitness;
    private boolean evaluated;
    
    /**
     * Constructor vacío que inicializa una solución sin rutas.
     */
    public Solution() {
        this.routes = new HashMap<>();
        this.fitness = Double.MAX_VALUE;
        this.evaluated = false;
    }
    
    /**
     * Constructor de copia profunda.
     * Esencial para operaciones genéticas sin efectos secundarios.
     * 
     * @param other La solución a copiar
     */
    public Solution(Solution other) {
        this.routes = new HashMap<>();
        for (Map.Entry<String, AssignedRoute> entry : other.routes.entrySet()) {
            this.routes.put(entry.getKey(), new AssignedRoute(entry.getValue()));
        }
        this.fitness = other.fitness;
        this.evaluated = other.evaluated;
    }
    
    /**
     * Agrega o reemplaza una ruta para un lote.
     * 
     * @param route La ruta asignada a agregar
     */
    public void addRoute(AssignedRoute route) {
        routes.put(route.getBatch().batchId(), route);
        evaluated = false;  // Invalidar fitness
    }
    
    /**
     * Obtiene la ruta asignada a un lote específico.
     * 
     * @param batchId ID del lote
     * @return La ruta asignada o null si no existe
     */
    public AssignedRoute getRoute(String batchId) {
        return routes.get(batchId);
    }
    
    /**
     * Obtiene todas las rutas asignadas.
     * 
     * @return Mapa inmutable de rutas (BatchId -> AssignedRoute)
     */
    public Map<String, AssignedRoute> getRoutes() {
        return Collections.unmodifiableMap(routes);
    }
    
    /**
     * @return El valor de fitness de la solución
     */
    public double getFitness() {
        return fitness;
    }
    
    /**
     * Establece el valor de fitness y marca la solución como evaluada.
     * 
     * @param fitness El valor de fitness a establecer
     */
    public void setFitness(double fitness) {
        this.fitness = fitness;
        this.evaluated = true;
    }
    
    /**
     * @return true si la solución ha sido evaluada, false en caso contrario
     */
    public boolean isEvaluated() {
        return evaluated;
    }
    
    /**
     * Obtiene todos los vuelos utilizados en la solución.
     * 
     * @return Conjunto de vuelos únicos utilizados
     */
    public Set<Flight> getUsedFlights() {
        Set<Flight> usedFlights = new HashSet<>();
        for (AssignedRoute route : routes.values()) {
            usedFlights.addAll(route.getFlights());
        }
        return usedFlights;
    }
    
    /**
     * Calcula el número total de maletas planificadas.
     * 
     * @return Suma de maletas de todos los lotes en la solución
     */
    public int getTotalBags() {
        return routes.values().stream()
                .mapToInt(route -> route.getBatch().quantity())
                .sum();
    }
}
