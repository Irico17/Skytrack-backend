package com.equipo2b.scheduler;

import com.equipo2b.scheduler.algorithm.*;
import com.equipo2b.scheduler.logic.*;
import com.equipo2b.scheduler.model.*;
import com.equipo2b.scheduler.monitoring.*;
import com.equipo2b.scheduler.upload.*;
import com.equipo2b.scheduler.util.*;

import java.io.*;
import java.nio.file.*;
import java.time.ZonedDateTime;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;

/**
 * Ejecuta las 3 simulaciones con datos reales:
 * - Escenario K=1: Operación día a día con ~100-200 lotes
 * - Escenario K=14: Simulación periodo con ~300-500 lotes
 * - Escenario K=75: Simulación colapso con TODOS los lotes disponibles
 * 
 * Genera reportes detallados de cada escenario.
 */
public class RunAllSimulationsWithRealData {
    
    private static final String AIRPORT_FILE = "data/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt";
    private static final String FLIGHT_FILE = "data/planes_vuelo.txt";
    private static final String SHIPMENT_DIR = "data/_envios_preliminar_/";
    
    public static void main(String[] args) {
        try {
            // CRÍTICO: Activar modo LENIENT para datos reales
            ValidationMode.setMode(ValidationMode.Mode.LENIENT);
            
            System.out.println("=".repeat(100));
            System.out.println("EJECUCIÓN DE 3 SIMULACIONES CON DATOS REALES");
            System.out.println("Modo de validación: " + ValidationMode.getMode());
            System.out.println("=".repeat(100));
            System.out.println();
            
            // ==================== CARGAR DATOS BASE ====================
            System.out.println("CARGANDO DATOS BASE...");
            System.out.println("-".repeat(100));
            
            // Cargar aeropuertos
            AirportManager airportManager = new AirportManager();
            AirportUploader airportUploader = new AirportUploader();
            List<Airport> airports = airportUploader.loadAirports(AIRPORT_FILE);
            for (Airport airport : airports) {
                airportManager.addAirport(airport);
            }
            System.out.println("✓ Aeropuertos cargados: " + airports.size());
            
            // Cargar plan de vuelos
            FlightPlan flightPlan = new FlightPlan();
            FlightPlanUploader flightUploader = new FlightPlanUploader();
            flightPlan = flightUploader.loadFlights(FLIGHT_FILE, airportManager);
            System.out.println("✓ Vuelos cargados: " + flightPlan.getTotalFlights());
            
            // Cargar TODOS los envíos disponibles
            ClientRegistry clientRegistry = new ClientRegistry();
            ShipmentUploader shipmentUploader = new ShipmentUploader();
            List<ShipmentBatch> allBatches = loadAllShipments(shipmentUploader, airportManager, clientRegistry);
            System.out.println("✓ Total de lotes disponibles: " + allBatches.size());
            System.out.println();
            
            // ==================== ESCENARIO 1: K=1 ====================
            runScenarioK1(allBatches, flightPlan, airportManager);
            
            // ==================== ESCENARIO 2: K=14 ====================
            runScenarioK14(allBatches, flightPlan, airportManager);
            
            // ==================== ESCENARIO 3: K=75 ====================
            runScenarioK75(allBatches, flightPlan, airportManager);
            
            System.out.println();
            System.out.println("=".repeat(100));
            System.out.println("TODAS LAS SIMULACIONES COMPLETADAS");
            System.out.println("=".repeat(100));
            
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Carga TODOS los archivos de envíos disponibles.
     */
    private static List<ShipmentBatch> loadAllShipments(
            ShipmentUploader uploader, 
            AirportManager airportManager,
            ClientRegistry clientRegistry) throws Exception {
        
        List<ShipmentBatch> allBatches = new ArrayList<>();
        
        // Obtener todos los archivos de envíos
        File shipmentDir = new File(SHIPMENT_DIR);
        File[] shipmentFiles = shipmentDir.listFiles((dir, name) -> name.startsWith("_envios_") && name.endsWith(".txt"));
        
        if (shipmentFiles == null || shipmentFiles.length == 0) {
            throw new Exception("No se encontraron archivos de envíos en " + SHIPMENT_DIR);
        }
        
        System.out.println("  Archivos de envíos encontrados: " + shipmentFiles.length);
        
        // Primero, registrar todos los clientes
        Set<String> clientIds = new HashSet<>();
        for (File file : shipmentFiles) {
            try (Stream<String> lines = Files.lines(file.toPath())) {
                lines.forEach(line -> {
                    if (!line.isBlank()) {
                        String[] parts = line.split("-");
                        if (parts.length >= 7) {
                            clientIds.add(parts[6].trim());
                        }
                    }
                });
            }
        }
        
        for (String clientId : clientIds) {
            clientRegistry.addClient(new AirlineClient(
                clientId, 
                "Cliente " + clientId, 
                clientId + "@example.com",
                "+1234567890"
            ));
        }
        System.out.println("  ✓ Clientes registrados: " + clientIds.size());
        
        // Cargar todos los lotes
        for (File file : shipmentFiles) {
            try {
                List<ShipmentBatch> batches = uploader.loadShipments(file.getPath(), airportManager, clientRegistry);
                allBatches.addAll(batches);
                System.out.println("  ✓ " + file.getName() + ": " + batches.size() + " lotes");
            } catch (Exception e) {
                System.out.println("  ⚠ " + file.getName() + ": " + e.getMessage());
            }
        }
        
        return allBatches;
    }
    
    /**
     * ESCENARIO 1: K=1 (Operación día a día)
     * - Usar ~100-200 lotes para simular operación diaria
     * - Ta=1min, Sa=5min
     */
    private static void runScenarioK1(
            List<ShipmentBatch> allBatches,
            FlightPlan flightPlan,
            AirportManager airportManager) throws Exception {
        
        System.out.println("\n");
        System.out.println("=".repeat(100));
        System.out.println("ESCENARIO 1: K=1 (OPERACIÓN DÍA A DÍA)");
        System.out.println("=".repeat(100));
        System.out.println();
        
        // Seleccionar ~150 lotes para operación diaria
        int batchCount = Math.min(150, allBatches.size());
        List<ShipmentBatch> batches = new ArrayList<>(allBatches.subList(0, batchCount));
        
        System.out.println("Configuración:");
        System.out.println("  K = 1 día");
        System.out.println("  Lotes a procesar: " + batches.size());
        System.out.println("  Ta = 1 minuto");
        System.out.println("  Sa = 5 minutos");
        System.out.println();
        
        // Configurar algoritmos
        GeneticAlgorithm ga = new GeneticAlgorithm(flightPlan, airportManager);
        AlgorithmConfig gaConfig = new AlgorithmConfig();
        gaConfig.setInt("populationSize", 30);
        gaConfig.setInt("generations", 20);
        gaConfig.setDouble("mutationRate", 0.1);
        gaConfig.setInt("tournamentSize", 4);
        gaConfig.setInt("eliteCount", 3);
        ga.configure(gaConfig);
        
        TabuSearch tabu = new TabuSearch(flightPlan, airportManager);
        AlgorithmConfig tabuConfig = new AlgorithmConfig();
        tabuConfig.setInt("maxIterations", 50);
        tabuConfig.setInt("tabuTenure", 15);
        tabuConfig.setInt("neighborhoodSize", 20);
        tabu.configure(tabuConfig);
        
        // Ejecutar simulación
        long startTime = System.currentTimeMillis();
        
        System.out.println("Ejecutando Algoritmo Genético...");
        Solution gaSolution = ga.optimize(batches);
        long gaTime = System.currentTimeMillis() - startTime;
        System.out.println("✓ GA completado en " + gaTime + " ms");
        System.out.println("  Fitness: " + String.format("%.2f", gaSolution.getFitness()));
        System.out.println();
        
        System.out.println("Refinando con Búsqueda Tabú...");
        startTime = System.currentTimeMillis();
        Solution refinedSolution = tabu.refine(gaSolution);
        long tabuTime = System.currentTimeMillis() - startTime;
        System.out.println("✓ Tabú completado en " + tabuTime + " ms");
        System.out.println("  Fitness mejorado: " + String.format("%.2f", refinedSolution.getFitness()));
        System.out.println();
        
        // Calcular métricas
        printScenarioMetrics("K=1", refinedSolution, flightPlan, airportManager, batches.size(), 0);
        
        // Guardar reporte
        saveReport("scenario_K1_report.txt", "K=1", refinedSolution, flightPlan, airportManager, batches.size(), 0);
    }
    
    /**
     * ESCENARIO 2: K=14 (Simulación periodo)
     * - Usar ~300-500 lotes para simular 2 semanas
     */
    private static void runScenarioK14(
            List<ShipmentBatch> allBatches,
            FlightPlan flightPlan,
            AirportManager airportManager) throws Exception {
        
        System.out.println("\n");
        System.out.println("=".repeat(100));
        System.out.println("ESCENARIO 2: K=14 (SIMULACIÓN PERIODO 2 SEMANAS)");
        System.out.println("=".repeat(100));
        System.out.println();
        
        // Seleccionar ~400 lotes para periodo
        int batchCount = Math.min(400, allBatches.size());
        List<ShipmentBatch> batches = new ArrayList<>(allBatches.subList(0, batchCount));
        
        System.out.println("Configuración:");
        System.out.println("  K = 14 días");
        System.out.println("  Lotes a procesar: " + batches.size());
        System.out.println();
        
        // Configurar algoritmos con más iteraciones
        GeneticAlgorithm ga = new GeneticAlgorithm(flightPlan, airportManager);
        AlgorithmConfig gaConfig = new AlgorithmConfig();
        gaConfig.setInt("populationSize", 40);
        gaConfig.setInt("generations", 30);
        gaConfig.setDouble("mutationRate", 0.1);
        gaConfig.setInt("tournamentSize", 5);
        gaConfig.setInt("eliteCount", 4);
        ga.configure(gaConfig);
        
        TabuSearch tabu = new TabuSearch(flightPlan, airportManager);
        AlgorithmConfig tabuConfig = new AlgorithmConfig();
        tabuConfig.setInt("maxIterations", 75);
        tabuConfig.setInt("tabuTenure", 20);
        tabuConfig.setInt("neighborhoodSize", 25);
        tabu.configure(tabuConfig);
        
        // Ejecutar simulación
        long startTime = System.currentTimeMillis();
        
        System.out.println("Ejecutando Algoritmo Genético...");
        Solution gaSolution = ga.optimize(batches);
        long gaTime = System.currentTimeMillis() - startTime;
        System.out.println("✓ GA completado en " + gaTime + " ms");
        System.out.println("  Fitness: " + String.format("%.2f", gaSolution.getFitness()));
        System.out.println();
        
        System.out.println("Refinando con Búsqueda Tabú...");
        startTime = System.currentTimeMillis();
        Solution refinedSolution = tabu.refine(gaSolution);
        long tabuTime = System.currentTimeMillis() - startTime;
        System.out.println("✓ Tabú completado en " + tabuTime + " ms");
        System.out.println("  Fitness mejorado: " + String.format("%.2f", refinedSolution.getFitness()));
        System.out.println();
        
        // Calcular métricas
        printScenarioMetrics("K=14", refinedSolution, flightPlan, airportManager, batches.size(), 0);
        
        // Guardar reporte
        saveReport("scenario_K14_report.txt", "K=14", refinedSolution, flightPlan, airportManager, batches.size(), 0);
    }
    
    /**
     * ESCENARIO 3: K=75 (Simulación colapso)
     * - Usar TODOS los lotes disponibles para forzar colapso
     */
    private static void runScenarioK75(
            List<ShipmentBatch> allBatches,
            FlightPlan flightPlan,
            AirportManager airportManager) throws Exception {
        
        System.out.println("\n");
        System.out.println("=".repeat(100));
        System.out.println("ESCENARIO 3: K=75 (SIMULACIÓN COLAPSO)");
        System.out.println("=".repeat(100));
        System.out.println();
        
        // Usar TODOS los lotes disponibles
        List<ShipmentBatch> batches = new ArrayList<>(allBatches);
        
        System.out.println("Configuración:");
        System.out.println("  K = 75 días");
        System.out.println("  Lotes a procesar: " + batches.size() + " (TODOS)");
        System.out.println();
        
        // Configurar algoritmos con parámetros máximos
        GeneticAlgorithm ga = new GeneticAlgorithm(flightPlan, airportManager);
        AlgorithmConfig gaConfig = new AlgorithmConfig();
        gaConfig.setInt("populationSize", 50);
        gaConfig.setInt("generations", 40);
        gaConfig.setDouble("mutationRate", 0.1);
        gaConfig.setInt("tournamentSize", 5);
        gaConfig.setInt("eliteCount", 5);
        ga.configure(gaConfig);
        
        TabuSearch tabu = new TabuSearch(flightPlan, airportManager);
        AlgorithmConfig tabuConfig = new AlgorithmConfig();
        tabuConfig.setInt("maxIterations", 100);
        tabuConfig.setInt("tabuTenure", 25);
        tabuConfig.setInt("neighborhoodSize", 30);
        tabu.configure(tabuConfig);
        
        // Ejecutar simulación
        long startTime = System.currentTimeMillis();
        
        System.out.println("Ejecutando Algoritmo Genético...");
        Solution gaSolution = ga.optimize(batches);
        long gaTime = System.currentTimeMillis() - startTime;
        System.out.println("✓ GA completado en " + gaTime + " ms");
        System.out.println("  Fitness: " + String.format("%.2f", gaSolution.getFitness()));
        System.out.println();
        
        System.out.println("Refinando con Búsqueda Tabú...");
        startTime = System.currentTimeMillis();
        Solution refinedSolution = tabu.refine(gaSolution);
        long tabuTime = System.currentTimeMillis() - startTime;
        System.out.println("✓ Tabú completado en " + tabuTime + " ms");
        System.out.println("  Fitness mejorado: " + String.format("%.2f", refinedSolution.getFitness()));
        System.out.println();
        
        // Detectar colapso
        int failedBatches = batches.size() - refinedSolution.getRoutes().size();
        CollapseDetector collapseDetector = new CollapseDetector();
        CollapseStatus collapseStatus = collapseDetector.evaluateCollapse(refinedSolution, batches.size(), failedBatches);
        
        System.out.println("DETECCIÓN DE COLAPSO:");
        System.out.println("  Nivel: " + collapseStatus.level());
        System.out.println("  Ocupación: " + String.format("%.1f%%", collapseStatus.occupancyPercentage()));
        System.out.println("  No atendibles: " + String.format("%.1f%%", collapseStatus.unserviceablePercentage()));
        System.out.println("  Mensaje: " + collapseStatus.message());
        System.out.println();
        
        // Calcular métricas
        printScenarioMetrics("K=75", refinedSolution, flightPlan, airportManager, batches.size(), failedBatches);
        
        // Guardar reporte
        saveReport("scenario_K75_report.txt", "K=75", refinedSolution, flightPlan, airportManager, batches.size(), failedBatches);
    }
    
    /**
     * Imprime métricas detalladas del escenario.
     */
    private static void printScenarioMetrics(
            String scenarioName,
            Solution solution,
            FlightPlan flightPlan,
            AirportManager airportManager,
            int totalBatches,
            int failedBatches) {
        
        SolutionEvaluator evaluator = new SolutionEvaluator(flightPlan, airportManager);
        
        System.out.println("MÉTRICAS DEL ESCENARIO " + scenarioName);
        System.out.println("-".repeat(100));
        
        // Fitness
        double fitness = solution.getFitness();
        System.out.println("Fitness Total: " + String.format("%.2f", fitness));
        
        // Componentes del fitness
        double flightCapPenalty = evaluator.calculateFlightCapacityPenalties(solution);
        double storagePenalty = evaluator.calculateStorageCapacityPenalties(solution);
        double slaPenalty = evaluator.calculateSLAPenalties(solution);
        double layoverPenalty = evaluator.calculateLayoverPenalties(solution);
        double slackReward = evaluator.calculateTimeSlackRewards(solution);
        double unusedFlightReward = evaluator.calculateUnusedFlightRewards(solution);
        
        System.out.println();
        System.out.println("Penalizaciones:");
        System.out.println("  Capacidad vuelos: " + String.format("%.2f", flightCapPenalty));
        System.out.println("  Capacidad almacén: " + String.format("%.2f", storagePenalty));
        System.out.println("  Violaciones SLA: " + String.format("%.2f", slaPenalty));
        System.out.println("  Violaciones escala: " + String.format("%.2f", layoverPenalty));
        System.out.println("  TOTAL: " + String.format("%.2f", flightCapPenalty + storagePenalty + slaPenalty + layoverPenalty));
        
        System.out.println();
        System.out.println("Premios:");
        System.out.println("  Holgura tiempo: " + String.format("%.2f", slackReward));
        System.out.println("  Vuelos no usados: " + String.format("%.2f", unusedFlightReward));
        System.out.println("  TOTAL: " + String.format("%.2f", slackReward + unusedFlightReward));
        
        // Estadísticas de rutas
        Map<String, AssignedRoute> routes = solution.getRoutes();
        int routesMeetingSLA = 0;
        int totalFlightsUsed = 0;
        int totalBags = 0;
        
        for (AssignedRoute route : routes.values()) {
            if (route.meetsSLA()) {
                routesMeetingSLA++;
            }
            totalFlightsUsed += route.getFlights().size();
            totalBags += route.getBatch().quantity();
        }
        
        double slaCompliance = routes.size() > 0 ? (routesMeetingSLA * 100.0 / routes.size()) : 0;
        
        System.out.println();
        System.out.println("Estadísticas:");
        System.out.println("  Total lotes: " + totalBatches);
        System.out.println("  Lotes planificados: " + routes.size());
        System.out.println("  Lotes fallidos: " + failedBatches);
        System.out.println("  Cumplimiento SLA: " + String.format("%.1f%%", slaCompliance) + " (" + routesMeetingSLA + "/" + routes.size() + ")");
        System.out.println("  Vuelos utilizados: " + totalFlightsUsed);
        System.out.println("  Maletas transportadas: " + totalBags);
        System.out.println();
    }
    
    /**
     * Guarda reporte en archivo.
     */
    private static void saveReport(
            String filename,
            String scenarioName,
            Solution solution,
            FlightPlan flightPlan,
            AirportManager airportManager,
            int totalBatches,
            int failedBatches) throws IOException {
        
        try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
            writer.println("=".repeat(100));
            writer.println("REPORTE ESCENARIO " + scenarioName);
            writer.println("=".repeat(100));
            writer.println();
            
            SolutionEvaluator evaluator = new SolutionEvaluator(flightPlan, airportManager);
            
            // Fitness
            writer.println("FITNESS TOTAL: " + String.format("%.2f", solution.getFitness()));
            writer.println();
            
            // Componentes
            double flightCapPenalty = evaluator.calculateFlightCapacityPenalties(solution);
            double storagePenalty = evaluator.calculateStorageCapacityPenalties(solution);
            double slaPenalty = evaluator.calculateSLAPenalties(solution);
            double layoverPenalty = evaluator.calculateLayoverPenalties(solution);
            double slackReward = evaluator.calculateTimeSlackRewards(solution);
            double unusedFlightReward = evaluator.calculateUnusedFlightRewards(solution);
            
            writer.println("PENALIZACIONES:");
            writer.println("  Capacidad vuelos: " + String.format("%.2f", flightCapPenalty));
            writer.println("  Capacidad almacén: " + String.format("%.2f", storagePenalty));
            writer.println("  Violaciones SLA: " + String.format("%.2f", slaPenalty));
            writer.println("  Violaciones escala: " + String.format("%.2f", layoverPenalty));
            writer.println();
            
            writer.println("PREMIOS:");
            writer.println("  Holgura tiempo: " + String.format("%.2f", slackReward));
            writer.println("  Vuelos no usados: " + String.format("%.2f", unusedFlightReward));
            writer.println();
            
            // Estadísticas
            Map<String, AssignedRoute> routes = solution.getRoutes();
            int routesMeetingSLA = 0;
            for (AssignedRoute route : routes.values()) {
                if (route.meetsSLA()) {
                    routesMeetingSLA++;
                }
            }
            
            double slaCompliance = routes.size() > 0 ? (routesMeetingSLA * 100.0 / routes.size()) : 0;
            
            writer.println("ESTADÍSTICAS:");
            writer.println("  Total lotes: " + totalBatches);
            writer.println("  Lotes planificados: " + routes.size());
            writer.println("  Lotes fallidos: " + failedBatches);
            writer.println("  Cumplimiento SLA: " + String.format("%.1f%%", slaCompliance));
            writer.println();
            
            writer.println("=".repeat(100));
        }
        
        System.out.println("✓ Reporte guardado en: " + filename);
    }
}
