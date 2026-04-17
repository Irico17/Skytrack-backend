package com.equipo2b.scheduler;

import com.equipo2b.scheduler.algorithm.*;
import com.equipo2b.scheduler.logic.*;
import com.equipo2b.scheduler.model.*;
import com.equipo2b.scheduler.monitoring.*;
import com.equipo2b.scheduler.upload.*;

import java.io.*;
import java.nio.file.*;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Stream;

/**
 * Ejecuta el escenario K=75 con un número ajustado de lotes (3000)
 * para simular colapso de manera práctica.
 */
public class RunScenarioK75Adjusted {
    
    private static final String AIRPORT_FILE = "data/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt";
    private static final String FLIGHT_FILE = "data/planes_vuelo.txt";
    private static final String SHIPMENT_DIR = "data/_envios_preliminar_/";
    
    public static void main(String[] args) {
        try {
            // CRÍTICO: Activar modo LENIENT para datos reales
            ValidationMode.setMode(ValidationMode.Mode.LENIENT);
            
            System.out.println("=".repeat(100));
            System.out.println("ESCENARIO K=75 AJUSTADO (SIMULACIÓN COLAPSO CON 3000 LOTES)");
            System.out.println("Modo de validación: " + ValidationMode.getMode());
            System.out.println("=".repeat(100));
            System.out.println();
            
            // Cargar datos base
            System.out.println("CARGANDO DATOS BASE...");
            System.out.println("-".repeat(100));
            
            AirportManager airportManager = new AirportManager();
            AirportUploader airportUploader = new AirportUploader();
            List<Airport> airports = airportUploader.loadAirports(AIRPORT_FILE);
            for (Airport airport : airports) {
                airportManager.addAirport(airport);
            }
            System.out.println("✓ Aeropuertos cargados: " + airports.size());
            
            FlightPlan flightPlan = new FlightPlan();
            FlightPlanUploader flightUploader = new FlightPlanUploader();
            flightPlan = flightUploader.loadFlights(FLIGHT_FILE, airportManager);
            System.out.println("✓ Vuelos cargados: " + flightPlan.getTotalFlights());
            
            // Cargar lotes (limitado a 3000 para simulación práctica)
            ClientRegistry clientRegistry = new ClientRegistry();
            ShipmentUploader shipmentUploader = new ShipmentUploader();
            List<ShipmentBatch> allBatches = loadLimitedShipments(shipmentUploader, airportManager, clientRegistry, 3000);
            System.out.println("✓ Total de lotes cargados: " + allBatches.size());
            System.out.println();
            
            // Ejecutar escenario K=75
            System.out.println("=".repeat(100));
            System.out.println("INICIANDO SIMULACIÓN K=75");
            System.out.println("=".repeat(100));
            System.out.println();
            
            System.out.println("Configuración:");
            System.out.println("  K = 75 días");
            System.out.println("  Lotes a procesar: " + allBatches.size());
            System.out.println("  Objetivo: Detectar punto de colapso");
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
            Solution gaSolution = ga.optimize(allBatches);
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
            int failedBatches = allBatches.size() - refinedSolution.getRoutes().size();
            CollapseDetector collapseDetector = new CollapseDetector();
            CollapseStatus collapseStatus = collapseDetector.evaluateCollapse(
                refinedSolution, allBatches.size(), failedBatches);
            
            System.out.println("=".repeat(100));
            System.out.println("DETECCIÓN DE COLAPSO");
            System.out.println("=".repeat(100));
            System.out.println("  Nivel: " + collapseStatus.level());
            System.out.println("  Ocupación: " + String.format("%.1f%%", collapseStatus.occupancyPercentage()));
            System.out.println("  No atendibles: " + String.format("%.1f%%", collapseStatus.unserviceablePercentage()));
            System.out.println("  Mensaje: " + collapseStatus.message());
            System.out.println();
            
            // Calcular métricas
            printMetrics(refinedSolution, flightPlan, airportManager, allBatches.size(), failedBatches);
            
            // Guardar reporte
            saveReport("scenario_K75_adjusted_report.txt", refinedSolution, flightPlan, airportManager, 
                      allBatches.size(), failedBatches, collapseStatus);
            
            System.out.println();
            System.out.println("=".repeat(100));
            System.out.println("SIMULACIÓN K=75 COMPLETADA");
            System.out.println("=".repeat(100));
            
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Carga un número limitado de lotes para simulación práctica.
     */
    private static List<ShipmentBatch> loadLimitedShipments(
            ShipmentUploader uploader,
            AirportManager airportManager,
            ClientRegistry clientRegistry,
            int maxBatches) throws Exception {
        
        List<ShipmentBatch> allBatches = new ArrayList<>();
        
        File shipmentDir = new File(SHIPMENT_DIR);
        File[] shipmentFiles = shipmentDir.listFiles((dir, name) -> 
            name.startsWith("_envios_") && name.endsWith(".txt"));
        
        if (shipmentFiles == null || shipmentFiles.length == 0) {
            throw new Exception("No se encontraron archivos de envíos");
        }
        
        System.out.println("  Cargando lotes desde múltiples aeropuertos...");
        
        // Registrar clientes primero
        Set<String> clientIds = new HashSet<>();
        for (File file : shipmentFiles) {
            if (allBatches.size() >= maxBatches) break;
            
            try (Stream<String> lines = Files.lines(file.toPath())) {
                lines.limit(maxBatches / shipmentFiles.length + 100).forEach(line -> {
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
                clientId, "Cliente " + clientId, 
                clientId + "@example.com", "+1234567890"
            ));
        }
        System.out.println("  ✓ Clientes registrados: " + clientIds.size());
        
        // Cargar lotes distribuyendo entre archivos
        int batchesPerFile = maxBatches / shipmentFiles.length;
        for (File file : shipmentFiles) {
            if (allBatches.size() >= maxBatches) break;
            
            try {
                List<ShipmentBatch> batches = uploader.loadShipments(
                    file.getPath(), airportManager, clientRegistry);
                
                int toAdd = Math.min(batchesPerFile, batches.size());
                allBatches.addAll(batches.subList(0, toAdd));
                
                System.out.println("  ✓ " + file.getName() + ": " + toAdd + " lotes");
            } catch (Exception e) {
                System.out.println("  ⚠ " + file.getName() + ": " + e.getMessage());
            }
        }
        
        return allBatches;
    }
    
    /**
     * Imprime métricas detalladas.
     */
    private static void printMetrics(
            Solution solution,
            FlightPlan flightPlan,
            AirportManager airportManager,
            int totalBatches,
            int failedBatches) {
        
        SolutionEvaluator evaluator = new SolutionEvaluator(flightPlan, airportManager);
        
        System.out.println("MÉTRICAS DEL ESCENARIO K=75");
        System.out.println("-".repeat(100));
        
        double fitness = solution.getFitness();
        System.out.println("Fitness Total: " + String.format("%.2f", fitness));
        
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
        System.out.println("  TOTAL: " + String.format("%.2f", 
            flightCapPenalty + storagePenalty + slaPenalty + layoverPenalty));
        
        System.out.println();
        System.out.println("Premios:");
        System.out.println("  Holgura tiempo: " + String.format("%.2f", slackReward));
        System.out.println("  Vuelos no usados: " + String.format("%.2f", unusedFlightReward));
        System.out.println("  TOTAL: " + String.format("%.2f", slackReward + unusedFlightReward));
        
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
        System.out.println("  Tasa de fallo: " + String.format("%.1f%%", 
            (failedBatches * 100.0 / totalBatches)));
        System.out.println("  Cumplimiento SLA: " + String.format("%.1f%%", slaCompliance) + 
            " (" + routesMeetingSLA + "/" + routes.size() + ")");
        System.out.println("  Vuelos utilizados: " + totalFlightsUsed);
        System.out.println("  Maletas transportadas: " + totalBags);
        System.out.println();
    }
    
    /**
     * Guarda reporte en archivo.
     */
    private static void saveReport(
            String filename,
            Solution solution,
            FlightPlan flightPlan,
            AirportManager airportManager,
            int totalBatches,
            int failedBatches,
            CollapseStatus collapseStatus) throws IOException {
        
        try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
            writer.println("=".repeat(100));
            writer.println("REPORTE ESCENARIO K=75 AJUSTADO (3000 LOTES)");
            writer.println("=".repeat(100));
            writer.println();
            
            SolutionEvaluator evaluator = new SolutionEvaluator(flightPlan, airportManager);
            
            writer.println("FITNESS TOTAL: " + String.format("%.2f", solution.getFitness()));
            writer.println();
            
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
            writer.println("  Tasa de fallo: " + String.format("%.1f%%", 
                (failedBatches * 100.0 / totalBatches)));
            writer.println("  Cumplimiento SLA: " + String.format("%.1f%%", slaCompliance));
            writer.println();
            
            writer.println("DETECCIÓN DE COLAPSO:");
            writer.println("  Nivel: " + collapseStatus.level());
            writer.println("  Ocupación: " + String.format("%.1f%%", collapseStatus.occupancyPercentage()));
            writer.println("  No atendibles: " + String.format("%.1f%%", collapseStatus.unserviceablePercentage()));
            writer.println("  Mensaje: " + collapseStatus.message());
            writer.println();
            
            writer.println("=".repeat(100));
        }
        
        System.out.println("✓ Reporte guardado en: " + filename);
    }
}
