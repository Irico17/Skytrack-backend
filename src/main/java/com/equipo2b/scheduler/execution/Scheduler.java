package com.equipo2b.scheduler.execution;

import com.equipo2b.scheduler.algorithm.*;
import com.equipo2b.scheduler.logic.SolutionEvaluator;
import com.equipo2b.scheduler.model.*;
import com.equipo2b.scheduler.validation.RouteValidator;
import com.equipo2b.scheduler.validation.ValidationReport;

import java.time.ZonedDateTime;
import java.time.Duration;
import java.util.*;

/**
 * Scheduler para planificación programada con ciclos periódicos.
 * 
 * <p>Soporta dos modos de algoritmo:
 * <ul>
 *   <li>GATS: Algoritmo Genético + Búsqueda Tabú (híbrido)</li>
 *   <li>TABU_PURE: Búsqueda Tabú pura (standalone)</li>
 * </ul>
 * 
 * Parámetros:
 * - Ta: Tiempo máximo de ejecución del algoritmo (minutos)
 * - Sa: Salto entre ejecuciones del algoritmo (minutos)
 * - K: Constante de proporcionalidad para consumo de datos
 * - Sc = Sa × K: Salto de consumo de datos (minutos)
 * 
 * **Validates: Requirements 21.5, 22.4, 23.1, 34.1, 34.2, Caso de estudio punto a, b**
 */
public class Scheduler {
    private final OptimizationAlgorithm primaryAlgorithm;
    private final TabuSearch tabuSearch;
    private final AlgorithmType algorithmType;
    private final boolean useRefinement;
    private final ShipmentQueue shipmentQueue;
    private final SolutionEvaluator evaluator;
    private final RouteValidator validator;
    
    // Parámetros de configuración
    private final int Ta;  // Tiempo algoritmo (minutos)
    private final int Sa;  // Salto algoritmo (minutos)
    private final int K;   // Constante proporcionalidad
    private final int Sc;  // Salto consumo = Sa × K (minutos)
    
    // Solución actual del sistema
    private Solution currentSolution;
    
    /**
     * Constructor del Scheduler con algoritmo configurable.
     * 
     * @param primaryAlgorithm Algoritmo primario (GA o Tabu)
     * @param tabuSearch Búsqueda Tabú para refinamiento (opcional)
     * @param algorithmType Tipo de algoritmo (GATS o TABU_PURE)
     * @param useRefinement Si se debe aplicar refinamiento Tabú adicional
     * @param shipmentQueue Cola de pedidos pendientes
     * @param evaluator Evaluador de fitness
     * @param validator Validador de soluciones
     * @param Ta Tiempo máximo de algoritmo (minutos)
     * @param Sa Salto entre ejecuciones (minutos)
     * @param K Constante de proporcionalidad
     * 
     * **Validates: Requirements 21.5, 22.4, 23.1, 34.1, 34.2, Caso de estudio punto a, b**
     */
    public Scheduler(OptimizationAlgorithm primaryAlgorithm,
                    TabuSearch tabuSearch,
                    AlgorithmType algorithmType,
                    boolean useRefinement,
                    ShipmentQueue shipmentQueue,
                    SolutionEvaluator evaluator,
                    RouteValidator validator,
                    int Ta, int Sa, int K) {
        this.primaryAlgorithm = Objects.requireNonNull(primaryAlgorithm, "Primary algorithm cannot be null");
        this.tabuSearch = Objects.requireNonNull(tabuSearch, "Tabu search cannot be null");
        this.algorithmType = Objects.requireNonNull(algorithmType, "Algorithm type cannot be null");
        this.useRefinement = useRefinement;
        this.shipmentQueue = Objects.requireNonNull(shipmentQueue, "Shipment queue cannot be null");
        this.evaluator = Objects.requireNonNull(evaluator, "Evaluator cannot be null");
        this.validator = Objects.requireNonNull(validator, "Validator cannot be null");
        
        this.Ta = Ta;
        this.Sa = Sa;
        this.K = K;
        this.Sc = Sa * K;
        
        // Validar que Sa > Ta
        if (Sa <= Ta) {
            throw new IllegalArgumentException(
                String.format("Sa (%d) must be greater than Ta (%d)", Sa, Ta)
            );
        }
        
        this.currentSolution = new Solution();
    }
    
    /**
     * Ejecuta un ciclo de planificación.
     * 
     * Proceso:
     * 1. Consumir pedidos de ventana Sc
     * 2. Ejecutar algoritmo primario (GA o Tabu)
     * 3. Refinar con Búsqueda Tabú (solo si useRefinement = true)
     * 4. Validar solución
     * 5. Actualizar rutas asignadas
     * 
     * @param currentTime Tiempo actual de la simulación
     * @return Solución refinada y validada
     * 
     * **Validates: Requirements 22.1, 22.2, 22.3, 22.5, 23.2, Caso de estudio punto a, b**
     */
    public Solution executePlanningCycle(ZonedDateTime currentTime) {
        System.out.println("\n=== CICLO DE PLANIFICACIÓN ===");
        System.out.println("Algoritmo: " + algorithmType.getDisplayName());
        System.out.println("Tiempo actual: " + currentTime);
        
        // 1. Calcular ventana de consumo: [currentTime, currentTime + Sc]
        ZonedDateTime windowStart = currentTime;
        ZonedDateTime windowEnd = currentTime.plusMinutes(Sc);
        
        System.out.println("Ventana de consumo: " + Sc + " minutos");
        
        // 2. Consumir pedidos de ShipmentQueue en ventana Sc
        List<ShipmentBatch> batches = shipmentQueue.consumeShipments(windowStart, windowEnd);
        System.out.println("Lotes consumidos: " + batches.size());
        
        if (batches.isEmpty()) {
            System.out.println("No hay lotes para planificar");
            return currentSolution;
        }
        
        // 3. Ejecutar algoritmo primario con pedidos consumidos
        long startTime = System.currentTimeMillis();
        String algorithmName = algorithmType == AlgorithmType.GATS ? "Algoritmo Genético" : "Búsqueda Tabú";
        System.out.println("\nEjecutando " + algorithmName + "...");
        Solution primarySolution = primaryAlgorithm.optimize(batches);
        long primaryTime = System.currentTimeMillis() - startTime;
        
        System.out.println("✓ " + algorithmName + " completado en " + primaryTime + " ms");
        System.out.println("  Fitness: " + String.format("%.2f", primarySolution.getFitness()));
        
        Solution finalSolution = primarySolution;
        long refinementTime = 0;
        
        // 4. Refinar con Búsqueda Tabú (solo para GATS)
        if (useRefinement) {
            startTime = System.currentTimeMillis();
            System.out.println("\nRefinando con Búsqueda Tabú...");
            finalSolution = tabuSearch.refine(primarySolution);
            refinementTime = System.currentTimeMillis() - startTime;
            
            System.out.println("✓ Refinamiento completado en " + refinementTime + " ms");
            System.out.println("  Fitness mejorado: " + String.format("%.2f", finalSolution.getFitness()));
        }
        
        // 5. ACUMULAR rutas nuevas a la solución existente (PLANIFICACIÓN INCREMENTAL)
        int routesBeforeAccumulation = currentSolution.getRoutes().size();
        int newRoutesCount = finalSolution.getRoutes().size();
        
        System.out.println("\n=== ACUMULACIÓN DE RUTAS ===");
        System.out.println("Rutas existentes: " + routesBeforeAccumulation);
        System.out.println("Rutas nuevas generadas: " + newRoutesCount);
        
        // Acumular cada ruta nueva a la solución actual
        for (AssignedRoute route : finalSolution.getRoutes().values()) {
            currentSolution.addRoute(route);  // Agrega o reemplaza por batchId
        }
        
        int routesAfterAccumulation = currentSolution.getRoutes().size();
        System.out.println("Rutas totales acumuladas: " + routesAfterAccumulation);
        
        // 6. Re-evaluar fitness de la solución completa
        evaluator.evaluate(currentSolution);
        System.out.println("Fitness de solución acumulada: " + String.format("%.2f", currentSolution.getFitness()));
        
        // 7. Validar solución acumulada usando RouteValidator
        ValidationReport validationReport = validator.validate(currentSolution);
        
        if (validationReport.isValid()) {
            System.out.println("✓ Solución acumulada válida");
        } else {
            System.out.println("⚠ Solución acumulada con violaciones:");
            System.out.println(validationReport.getSummary());
        }
        
        // 8. Registrar tiempo de ejecución y verificar que sea <= Ta
        long totalTime = primaryTime + refinementTime;
        long taMillis = Ta * 60 * 1000L;
        
        System.out.println("\nTiempo total: " + totalTime + " ms (límite: " + taMillis + " ms)");
        
        if (totalTime > taMillis) {
            System.out.println("⚠ ADVERTENCIA: Tiempo excedido");
        }
        
        return currentSolution;
    }
    
    /**
     * Ejecuta simulación completa con ciclos de planificación.
     * 
     * Proceso:
     * - Ejecutar ciclos cada Sa minutos
     * - Continuar hasta que no haya más pedidos o se alcance límite
     * 
     * @param startTime Tiempo de inicio de la simulación
     * @param maxCycles Número máximo de ciclos (0 = sin límite)
     * @return Solución final
     * 
     * **Validates: Requirements 21.1, 21.2, 21.3, 21.4, 22.1, Caso de estudio punto a, b**
     */
    public Solution run(ZonedDateTime startTime, int maxCycles) {
        System.out.println("=".repeat(80));
        System.out.println("INICIANDO SCHEDULER");
        System.out.println("Algoritmo: " + algorithmType.getDisplayName());
        System.out.println("Parámetros: Ta=" + Ta + " min, Sa=" + Sa + " min, K=" + K + ", Sc=" + Sc + " min");
        System.out.println("=".repeat(80));
        
        ZonedDateTime currentTime = startTime;
        int cycle = 0;
        
        while (shipmentQueue.getPendingCount() > 0) {
            cycle++;
            System.out.println("\n--- CICLO " + cycle + " ---");
            
            // Ejecutar ciclo de planificación
            executePlanningCycle(currentTime);
            
            // Avanzar tiempo en Sa minutos
            currentTime = currentTime.plusMinutes(Sa);
            
            // Verificar límite de ciclos
            if (maxCycles > 0 && cycle >= maxCycles) {
                System.out.println("\nLímite de ciclos alcanzado: " + maxCycles);
                break;
            }
        }
        
        System.out.println("\n" + "=".repeat(80));
        System.out.println("SCHEDULER COMPLETADO");
        System.out.println("Algoritmo usado: " + algorithmType.getDisplayName());
        System.out.println("Ciclos ejecutados: " + cycle);
        System.out.println("Fitness final: " + String.format("%.2f", currentSolution.getFitness()));
        System.out.println("=".repeat(80));
        
        return currentSolution;
    }
    
    /**
     * Obtiene el tipo de algoritmo configurado.
     * 
     * @return Tipo de algoritmo
     */
    public AlgorithmType getAlgorithmType() {
        return algorithmType;
    }
    
    /**
     * Obtiene la solución actual del sistema.
     * 
     * @return Solución actual
     */
    public Solution getCurrentSolution() {
        return currentSolution;
    }
    
    /**
     * Actualiza la solución actual del sistema.
     * Usado para replanificación de emergencia.
     * 
     * @param newSolution Nueva solución
     * 
     * **Validates: Requirements 12.6, 24.5**
     */
    public void updateSolution(Solution newSolution) {
        this.currentSolution = Objects.requireNonNull(newSolution, "New solution cannot be null");
        System.out.println("✓ Solución actualizada en Scheduler");
    }
}
