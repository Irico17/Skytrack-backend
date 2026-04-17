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
 * Parámetros:
 * - Ta: Tiempo máximo de ejecución del algoritmo (minutos)
 * - Sa: Salto entre ejecuciones del algoritmo (minutos)
 * - K: Constante de proporcionalidad para consumo de datos
 * - Sc = Sa × K: Salto de consumo de datos (minutos)
 * 
 * **Validates: Requirements 21.5, 22.4, 23.1, 34.1, 34.2**
 */
public class Scheduler {
    private final OptimizationAlgorithm geneticAlgorithm;
    private final TabuSearch tabuSearch;
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
     * Constructor del Scheduler.
     * 
     * @param geneticAlgorithm Algoritmo Genético para planificación masiva
     * @param tabuSearch Búsqueda Tabú para refinamiento
     * @param shipmentQueue Cola de pedidos pendientes
     * @param evaluator Evaluador de fitness
     * @param validator Validador de soluciones
     * @param Ta Tiempo máximo de algoritmo (minutos)
     * @param Sa Salto entre ejecuciones (minutos)
     * @param K Constante de proporcionalidad
     * 
     * **Validates: Requirements 21.5, 22.4, 23.1, 34.1, 34.2**
     */
    public Scheduler(OptimizationAlgorithm geneticAlgorithm,
                    TabuSearch tabuSearch,
                    ShipmentQueue shipmentQueue,
                    SolutionEvaluator evaluator,
                    RouteValidator validator,
                    int Ta, int Sa, int K) {
        this.geneticAlgorithm = Objects.requireNonNull(geneticAlgorithm);
        this.tabuSearch = Objects.requireNonNull(tabuSearch);
        this.shipmentQueue = Objects.requireNonNull(shipmentQueue);
        this.evaluator = Objects.requireNonNull(evaluator);
        this.validator = Objects.requireNonNull(validator);
        
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
     * 2. Ejecutar Algoritmo Genético
     * 3. Refinar con Búsqueda Tabú
     * 4. Validar solución
     * 5. Actualizar rutas asignadas
     * 
     * @param currentTime Tiempo actual de la simulación
     * @return Solución refinada y validada
     * 
     * **Validates: Requirements 22.1, 22.2, 22.3, 22.5, 23.2**
     */
    public Solution executePlanningCycle(ZonedDateTime currentTime) {
        System.out.println("\n=== CICLO DE PLANIFICACIÓN ===");
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
        
        // 3. Ejecutar Algoritmo Genético con pedidos consumidos
        long startTime = System.currentTimeMillis();
        System.out.println("\nEjecutando Algoritmo Genético...");
        Solution gaSolution = geneticAlgorithm.optimize(batches);
        long gaTime = System.currentTimeMillis() - startTime;
        
        System.out.println("✓ GA completado en " + gaTime + " ms");
        System.out.println("  Fitness: " + String.format("%.2f", gaSolution.getFitness()));
        
        // 4. Refinar mejor solución GA usando Búsqueda Tabú
        startTime = System.currentTimeMillis();
        System.out.println("\nRefinando con Búsqueda Tabú...");
        Solution refinedSolution = tabuSearch.refine(gaSolution);
        long tabuTime = System.currentTimeMillis() - startTime;
        
        System.out.println("✓ Tabú completado en " + tabuTime + " ms");
        System.out.println("  Fitness mejorado: " + String.format("%.2f", refinedSolution.getFitness()));
        
        // 5. Validar solución refinada usando RouteValidator
        ValidationReport validationReport = validator.validate(refinedSolution);
        
        if (validationReport.isValid()) {
            System.out.println("✓ Solución válida");
        } else {
            System.out.println("⚠ Solución con violaciones:");
            System.out.println(validationReport.getSummary());
        }
        
        // 6. Actualizar rutas asignadas en sistema
        currentSolution = refinedSolution;
        
        // 7. Registrar tiempo de ejecución y verificar que sea <= Ta
        long totalTime = gaTime + tabuTime;
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
     * **Validates: Requirements 21.1, 21.2, 21.3, 21.4, 22.1**
     */
    public Solution run(ZonedDateTime startTime, int maxCycles) {
        System.out.println("=".repeat(80));
        System.out.println("INICIANDO SCHEDULER");
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
        System.out.println("Ciclos ejecutados: " + cycle);
        System.out.println("Fitness final: " + String.format("%.2f", currentSolution.getFitness()));
        System.out.println("=".repeat(80));
        
        return currentSolution;
    }
    
    /**
     * Obtiene la solución actual del sistema.
     * 
     * @return Solución actual
     */
    public Solution getCurrentSolution() {
        return currentSolution;
    }
}
