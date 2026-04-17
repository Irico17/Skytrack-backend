package com.equipo2b.scheduler.execution;

import com.equipo2b.scheduler.algorithm.*;
import com.equipo2b.scheduler.logic.*;
import com.equipo2b.scheduler.model.*;
import com.equipo2b.scheduler.upload.*;

import java.time.ZonedDateTime;
import java.time.Duration;
import java.util.*;

/**
 * Ejecuta simulaciones completas del sistema de planificación logística.
 * 
 * Escenarios soportados:
 * - K=1: Día a día (planificación diaria)
 * - K=14-23: Período (simulación de 2-3 semanas)
 * - K=75: Colapso (simulación de 2.5 meses)
 * 
 * Proceso:
 * 1. Planificación inicial con Algoritmo Genético
 * 2. Simulación día a día con eventos
 * 3. Replanificación de emergencia cuando sea necesario
 * 4. Métricas y estadísticas finales
 */
public class SimulationRunner {
    
    private final FlightPlan flightPlan;
    private final AirportManager airportManager;
    private final GeneticAlgorithm geneticAlgorithm;
    private final TabuSearch tabuSearch;
    private final SolutionEvaluator evaluator;
    
    // Configuración de simulación
    private int simulationDays;
    private ZonedDateTime simulationStartDate;
    
    // Métricas de simulación
    private int totalReplans = 0;
    private int totalBatchesProcessed = 0;
    private int totalBatchesFailed = 0;
    private List<SimulationEvent> events = new ArrayList<>();
    
    public SimulationRunner(FlightPlan flightPlan, AirportManager airportManager) {
        this.flightPlan = flightPlan;
        this.airportManager = airportManager;
        this.geneticAlgorithm = new GeneticAlgorithm(flightPlan, airportManager);
        this.tabuSearch = new TabuSearch(flightPlan, airportManager);
        this.evaluator = new SolutionEvaluator(flightPlan, airportManager);
    }
    
    /**
     * Ejecuta simulación de K días.
     * 
     * @param batches Lotes a procesar durante la simulación
     * @param K Número de días a simular (1, 14-23, o 75)
     * @param startDate Fecha de inicio de la simulación
     * @return Resultado de la simulación con métricas
     */
    public SimulationResult runSimulation(List<ShipmentBatch> batches, int K, ZonedDateTime startDate) {
        this.simulationDays = K;
        this.simulationStartDate = startDate;
        
        System.out.println("=".repeat(80));
        System.out.println("INICIANDO SIMULACIÓN: K=" + K + " días");
        System.out.println("Fecha inicio: " + startDate);
        System.out.println("Lotes totales: " + batches.size());
        System.out.println("=".repeat(80));
        System.out.println();
        
        // FASE 1: Planificación Inicial
        System.out.println("FASE 1: Planificación Inicial con Algoritmo Genético");
        System.out.println("-".repeat(80));
        
        long startTime = System.currentTimeMillis();
        Solution initialSolution = geneticAlgorithm.optimize(batches);
        long gaTime = System.currentTimeMillis() - startTime;
        
        System.out.println("✓ Planificación inicial completada en " + gaTime + " ms");
        System.out.println("  Fitness: " + String.format("%.2f", initialSolution.getFitness()));
        System.out.println("  Rutas generadas: " + initialSolution.getRoutes().size());
        System.out.println();
        
        // FASE 2: Refinamiento con Búsqueda Tabú
        System.out.println("FASE 2: Refinamiento con Búsqueda Tabú");
        System.out.println("-".repeat(80));
        
        startTime = System.currentTimeMillis();
        Solution refinedSolution = tabuSearch.refine(initialSolution);
        long tabuTime = System.currentTimeMillis() - startTime;
        
        System.out.println("✓ Refinamiento completado en " + tabuTime + " ms");
        System.out.println("  Fitness mejorado: " + String.format("%.2f", refinedSolution.getFitness()));
        System.out.println();
        
        // FASE 3: Simulación Día a Día
        System.out.println("FASE 3: Simulación Día a Día (K=" + K + " días)");
        System.out.println("-".repeat(80));
        
        Solution currentSolution = refinedSolution;
        
        for (int day = 0; day < K; day++) {
            ZonedDateTime currentDate = startDate.plusDays(day);
            System.out.println("\n--- Día " + (day + 1) + "/" + K + " (" + currentDate.toLocalDate() + ") ---");
            
            // Obtener lotes que ingresan este día
            List<ShipmentBatch> dailyBatches = getBatchesForDay(batches, currentDate);
            System.out.println("Lotes ingresando hoy: " + dailyBatches.size());
            
            if (!dailyBatches.isEmpty()) {
                // Verificar si necesitamos replanificar
                boolean needsReplan = checkIfReplanNeeded(currentSolution, dailyBatches);
                
                if (needsReplan) {
                    System.out.println("⚠ Replanificación necesaria");
                    totalReplans++;
                    
                    // Replanificación de emergencia con Tabú
                    startTime = System.currentTimeMillis();
                    currentSolution = tabuSearch.replan(currentSolution, dailyBatches);
                    long replanTime = System.currentTimeMillis() - startTime;
                    
                    System.out.println("✓ Replanificación completada en " + replanTime + " ms");
                    
                    events.add(new SimulationEvent(
                        currentDate,
                        SimulationEventType.REPLAN,
                        "Replanificación de emergencia: " + dailyBatches.size() + " lotes"
                    ));
                }
            }
            
            totalBatchesProcessed += dailyBatches.size();
            
            // Simular ejecución de vuelos del día
            simulateFlightExecution(currentSolution, currentDate);
        }
        
        System.out.println();
        System.out.println("=".repeat(80));
        System.out.println("SIMULACIÓN COMPLETADA");
        System.out.println("=".repeat(80));
        
        // Calcular métricas finales
        return calculateFinalMetrics(currentSolution);
    }
    
    /**
     * Obtiene los lotes que ingresan en una fecha específica.
     */
    private List<ShipmentBatch> getBatchesForDay(List<ShipmentBatch> allBatches, ZonedDateTime date) {
        return allBatches.stream()
            .filter(b -> b.ingressTime().toLocalDate().equals(date.toLocalDate()))
            .toList();
    }
    
    /**
     * Verifica si se necesita replanificación.
     * Criterios:
     * - Capacidad de vuelos excedida
     * - Capacidad de almacén excedida
     * - Rutas que no cumplen SLA
     */
    private boolean checkIfReplanNeeded(Solution solution, List<ShipmentBatch> newBatches) {
        // Por ahora, simplificado: siempre replanificar si hay nuevos lotes
        // En implementación completa, verificar capacidades y SLA
        return !newBatches.isEmpty() && totalReplans < 5; // Limitar replans para demo
    }
    
    /**
     * Simula la ejecución de vuelos en un día.
     */
    private void simulateFlightExecution(Solution solution, ZonedDateTime date) {
        // Contar vuelos que salen este día
        int flightsToday = 0;
        int bagsShipped = 0;
        
        for (AssignedRoute route : solution.getRoutes().values()) {
            for (Flight flight : route.getFlights()) {
                if (flight.departureTime().toLocalDate().equals(date.toLocalDate())) {
                    flightsToday++;
                    bagsShipped += route.getBatch().quantity();
                }
            }
        }
        
        if (flightsToday > 0) {
            System.out.println("  Vuelos ejecutados: " + flightsToday);
            System.out.println("  Maletas enviadas: " + bagsShipped);
        }
    }
    
    /**
     * Calcula métricas finales de la simulación.
     */
    private SimulationResult calculateFinalMetrics(Solution finalSolution) {
        SimulationResult result = new SimulationResult();
        
        result.totalDays = simulationDays;
        result.totalBatchesProcessed = totalBatchesProcessed;
        result.totalBatchesFailed = totalBatchesFailed;
        result.totalReplans = totalReplans;
        result.finalFitness = finalSolution.getFitness();
        result.finalSolution = finalSolution;
        result.events = events;
        
        // Calcular métricas de calidad
        int routesMeetingSLA = 0;
        int totalRoutes = finalSolution.getRoutes().size();
        
        for (AssignedRoute route : finalSolution.getRoutes().values()) {
            if (route.meetsSLA()) {
                routesMeetingSLA++;
            }
        }
        
        result.slaComplianceRate = totalRoutes > 0 ? (routesMeetingSLA * 100.0 / totalRoutes) : 0;
        
        return result;
    }
    
    /**
     * Configura el Algoritmo Genético.
     */
    public void configureGA(int populationSize, int generations, double mutationRate) {
        AlgorithmConfig config = new AlgorithmConfig();
        config.setInt("populationSize", populationSize);
        config.setInt("generations", generations);
        config.setDouble("mutationRate", mutationRate);
        config.setInt("tournamentSize", 4);
        config.setInt("eliteCount", 3);
        geneticAlgorithm.configure(config);
    }
    
    /**
     * Configura la Búsqueda Tabú.
     */
    public void configureTabu(int maxIterations, int tabuTenure, int neighborhoodSize) {
        AlgorithmConfig config = new AlgorithmConfig();
        config.setInt("maxIterations", maxIterations);
        config.setInt("tabuTenure", tabuTenure);
        config.setInt("neighborhoodSize", neighborhoodSize);
        tabuSearch.configure(config);
    }
    
    /**
     * Resultado de una simulación.
     */
    public static class SimulationResult {
        public int totalDays;
        public int totalBatchesProcessed;
        public int totalBatchesFailed;
        public int totalReplans;
        public double finalFitness;
        public double slaComplianceRate;
        public Solution finalSolution;
        public List<SimulationEvent> events;
        
        public void printSummary() {
            System.out.println("\n📊 RESUMEN DE SIMULACIÓN");
            System.out.println("=".repeat(80));
            System.out.println("Días simulados: " + totalDays);
            System.out.println("Lotes procesados: " + totalBatchesProcessed);
            System.out.println("Lotes fallidos: " + totalBatchesFailed);
            System.out.println("Replanificaciones: " + totalReplans);
            System.out.println("Fitness final: " + String.format("%.2f", finalFitness));
            System.out.println("Cumplimiento SLA: " + String.format("%.1f%%", slaComplianceRate));
            System.out.println("=".repeat(80));
        }
    }
    
    /**
     * Evento de simulación.
     */
    private static class SimulationEvent {
        ZonedDateTime timestamp;
        SimulationEventType type;
        String description;
        
        SimulationEvent(ZonedDateTime timestamp, SimulationEventType type, String description) {
            this.timestamp = timestamp;
            this.type = type;
            this.description = description;
        }
    }
    
    /**
     * Tipos de eventos de simulación.
     */
    private enum SimulationEventType {
        REPLAN,
        FLIGHT_DELAY,
        CAPACITY_EXCEEDED,
        SLA_VIOLATION
    }
}
