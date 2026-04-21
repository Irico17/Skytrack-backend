package com.equipo2b.scheduler;

import com.equipo2b.scheduler.algorithm.AlgorithmType;
import com.equipo2b.scheduler.execution.Scheduler;
import com.equipo2b.scheduler.execution.SchedulerFactory;
import com.equipo2b.scheduler.logic.SolutionEvaluator;
import com.equipo2b.scheduler.model.*;
import com.equipo2b.scheduler.monitoring.CapacityMonitor;
import com.equipo2b.scheduler.upload.*;
import com.equipo2b.scheduler.validation.RouteValidator;
import com.equipo2b.scheduler.validation.ValidationReport;

import java.io.File;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Comparación de algoritmos GATS vs Tabu Puro con datos reales.
 * 
 * <p>Ejecuta ambos algoritmos con los mismos datos y compara:
 * <ul>
 *   <li>Tiempo de ejecución</li>
 *   <li>Fitness final</li>
 *   <li>Ocupación de vuelos y almacenes</li>
 *   <li>Cumplimiento SLA</li>
 * </ul>
 * 
 * <p><strong>Validates: Caso de estudio punto a, b - Experimentación numérica</strong>
 */
public class RunAlgorithmComparison {
    
    public static void main(String[] args) {
        System.out.println("=".repeat(80));
        System.out.println("EXPERIMENTACIÓN NUMÉRICA: GATS vs Tabu Puro");
        System.out.println("=".repeat(80));
        System.out.println();
        
        try {
            // Configurar modo lenient
            ValidationMode.setMode(ValidationMode.Mode.LENIENT);
            
            // Cargar datos reales
            System.out.println(">>> CARGANDO DATOS REALES <<<");
            System.out.println();
            
            // Cargar aeropuertos
            String airportFile = "data/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt";
            AirportUploader airportUploader = new AirportUploader();
            List<Airport> airports = airportUploader.loadAirports(airportFile);
            AirportManager airportManager = new AirportManager();
            for (Airport airport : airports) {
                airportManager.addAirport(airport);
            }
            System.out.println("✓ Aeropuertos cargados: " + airports.size());
            
            // Cargar plan de vuelos
            String flightFile = "data/planes_vuelo.txt";
            FlightPlanUploader flightUploader = new FlightPlanUploader();
            FlightPlan flightPlan = flightUploader.loadFlights(flightFile, airportManager);
            System.out.println("✓ Vuelos cargados: " + flightPlan.getAllFlights().size());
            
            // Cargar clientes (pre-cargar todos los IDs de clientes de los archivos)
            ClientRegistry clientRegistry = new ClientRegistry();
            
            System.out.println("Pre-cargando IDs de clientes...");
            File enviosDir = new File("data/_envios_preliminar_");
            if (enviosDir.exists() && enviosDir.isDirectory()) {
                File[] files = enviosDir.listFiles((dir, name) -> name.endsWith(".txt"));
                if (files != null) {
                    for (File file : files) {
                        try {
                            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file));
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.trim().isEmpty() || line.startsWith("#")) continue;
                                String[] parts = line.split("-");
                                if (parts.length >= 7) {
                                    String clientId = parts[6].trim();
                                    if (!clientRegistry.validateClientExists(clientId)) {
                                        clientRegistry.addClient(new AirlineClient(
                                            clientId, 
                                            "Airline " + clientId, 
                                            "contact@airline" + clientId + ".com", 
                                            "+000000000"
                                        ));
                                    }
                                }
                            }
                            reader.close();
                        } catch (Exception e) {
                            // Ignorar errores en pre-carga
                        }
                    }
                }
            }
            System.out.println("✓ Clientes pre-cargados: " + clientRegistry.size());
            
            // Ahora cargar TODOS los archivos de envíos
            ShipmentUploader shipmentUploader = new ShipmentUploader();
            List<ShipmentBatch> allBatches = new ArrayList<>();
            if (enviosDir.exists() && enviosDir.isDirectory()) {
                File[] files = enviosDir.listFiles((dir, name) -> name.endsWith(".txt"));
                if (files != null) {
                    for (File file : files) {
                        try {
                            List<ShipmentBatch> batches = shipmentUploader.loadShipments(
                                file.getAbsolutePath(), airportManager, clientRegistry
                            );
                            allBatches.addAll(batches);
                            System.out.println("✓ Cargado " + file.getName() + ": " + batches.size() + " lotes");
                        } catch (Exception e) {
                            System.out.println("⚠ Error cargando " + file.getName() + ": " + e.getMessage());
                        }
                    }
                }
            }
            System.out.println("✓ Total lotes cargados: " + allBatches.size());
            System.out.println();
            
            if (allBatches.isEmpty()) {
                System.err.println("❌ No se pudieron cargar lotes. Abortando.");
                return;
            }
            
            // Parámetros de simulación K=1
            int Ta = 1;  // 1 minuto
            int Sa = 5;  // 5 minutos
            int K = 1;
            
            System.out.println("Parámetros: Ta=" + Ta + " min, Sa=" + Sa + " min, K=" + K);
            System.out.println();
            
            // Ejecutar con GATS
            System.out.println("\n" + "=".repeat(80));
            System.out.println(">>> EJECUTANDO GATS (Genetic Algorithm + Tabu Search) <<<");
            System.out.println("=".repeat(80));
            ExperimentResult gatsResult = runExperiment(
                AlgorithmType.GATS, flightPlan, airportManager, allBatches, Ta, Sa, K
            );
            
            // Ejecutar con Tabu Puro
            System.out.println("\n" + "=".repeat(80));
            System.out.println(">>> EJECUTANDO TABU PURO (Tabu Search Standalone) <<<");
            System.out.println("=".repeat(80));
            ExperimentResult tabuResult = runExperiment(
                AlgorithmType.TABU_PURE, flightPlan, airportManager, allBatches, Ta, Sa, K
            );
            
            // Comparar resultados
            printComparison(gatsResult, tabuResult);
            
        } catch (Exception e) {
            System.err.println("❌ Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static ExperimentResult runExperiment(
            AlgorithmType algorithmType,
            FlightPlan flightPlan,
            AirportManager airportManager,
            List<ShipmentBatch> batches,
            int Ta, int Sa, int K) {
        
        // Crear cola de pedidos (copia para cada experimento)
        ShipmentQueue queue = new ShipmentQueue();
        for (ShipmentBatch batch : batches) {
            queue.addShipment(batch);
        }
        
        // Crear componentes
        SolutionEvaluator evaluator = new SolutionEvaluator(flightPlan, airportManager);
        RouteValidator validator = new RouteValidator(airportManager);
        
        // Crear Scheduler según tipo
        Scheduler scheduler;
        if (algorithmType == AlgorithmType.GATS) {
            scheduler = SchedulerFactory.createGATSScheduler(
                flightPlan, airportManager, queue, evaluator, validator, Ta, Sa, K
            );
        } else {
            scheduler = SchedulerFactory.createTabuScheduler(
                flightPlan, airportManager, queue, evaluator, validator, Ta, Sa, K
            );
        }
        
        // Ejecutar simulación y medir tiempo
        ZonedDateTime startTime = batches.get(0).ingressTime();
        long startTimeMs = System.currentTimeMillis();
        Solution solution = scheduler.run(startTime, 0);  // Sin límite de ciclos
        long executionTimeMs = System.currentTimeMillis() - startTimeMs;
        
        // Calcular métricas
        CapacityMonitor monitor = new CapacityMonitor(flightPlan, airportManager);
        double avgFlightOccupancy = monitor.calculateAverageFlightOccupancy(solution);
        
        ValidationReport report = validator.validate(solution);
        int capacityViolations = report.getViolations().size();
        
        // Calcular SLA compliance
        int totalRoutes = solution.getRoutes().size();
        int slaCompliant = (int) solution.getRoutes().values().stream()
            .filter(route -> route.meetsSLA())
            .count();
        double slaComplianceRate = totalRoutes > 0 ? (slaCompliant * 100.0 / totalRoutes) : 0;
        
        return new ExperimentResult(
            algorithmType,
            executionTimeMs,
            solution.getFitness(),
            avgFlightOccupancy,
            slaComplianceRate,
            capacityViolations,
            totalRoutes
        );
    }
    
    private static void printComparison(ExperimentResult gats, ExperimentResult tabu) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("RESULTADOS COMPARATIVOS");
        System.out.println("=".repeat(80));
        System.out.println();
        
        System.out.println("┌─────────────────────────────┬──────────────────┬──────────────────┐");
        System.out.println("│ Métrica                     │ GATS             │ Tabu Puro        │");
        System.out.println("├─────────────────────────────┼──────────────────┼──────────────────┤");
        
        printRow("Tiempo ejecución (ms)", gats.executionTimeMs, tabu.executionTimeMs);
        printRow("Tiempo ejecución (min)", gats.executionTimeMs / 60000.0, tabu.executionTimeMs / 60000.0);
        printRow("Fitness final", gats.finalFitness, tabu.finalFitness);
        printRow("Ocupación vuelos (%)", gats.avgFlightOccupancy, tabu.avgFlightOccupancy);
        printRow("SLA compliance (%)", gats.slaComplianceRate, tabu.slaComplianceRate);
        printRow("Violaciones capacidad", gats.capacityViolations, tabu.capacityViolations);
        printRow("Total rutas", gats.totalRoutes, tabu.totalRoutes);
        
        System.out.println("└─────────────────────────────┴──────────────────┴──────────────────┘");
        System.out.println();
        
        // Análisis
        System.out.println("🏆 ANÁLISIS:");
        System.out.println();
        
        // Fitness
        if (gats.finalFitness < tabu.finalFitness) {
            double improvement = ((tabu.finalFitness - gats.finalFitness) / tabu.finalFitness) * 100;
            System.out.println("   ✓ GATS tiene mejor fitness (" + String.format("%.2f", improvement) + "% mejor)");
        } else if (tabu.finalFitness < gats.finalFitness) {
            double improvement = ((gats.finalFitness - tabu.finalFitness) / gats.finalFitness) * 100;
            System.out.println("   ✓ Tabu Puro tiene mejor fitness (" + String.format("%.2f", improvement) + "% mejor)");
        } else {
            System.out.println("   = Fitness similar en ambos algoritmos");
        }
        
        // Tiempo
        if (gats.executionTimeMs < tabu.executionTimeMs) {
            double speedup = (tabu.executionTimeMs * 100.0 / gats.executionTimeMs) - 100;
            System.out.println("   ✓ GATS es más rápido (" + String.format("%.2f", speedup) + "% más rápido)");
        } else if (tabu.executionTimeMs < gats.executionTimeMs) {
            double speedup = (gats.executionTimeMs * 100.0 / tabu.executionTimeMs) - 100;
            System.out.println("   ✓ Tabu Puro es más rápido (" + String.format("%.2f", speedup) + "% más rápido)");
        } else {
            System.out.println("   = Tiempo similar en ambos algoritmos");
        }
        
        // SLA
        if (gats.slaComplianceRate > tabu.slaComplianceRate) {
            System.out.println("   ✓ GATS tiene mejor cumplimiento SLA");
        } else if (tabu.slaComplianceRate > gats.slaComplianceRate) {
            System.out.println("   ✓ Tabu Puro tiene mejor cumplimiento SLA");
        } else {
            System.out.println("   = Cumplimiento SLA similar");
        }
        
        System.out.println();
        System.out.println("=".repeat(80));
    }
    
    private static void printRow(String metric, double gats, double tabu) {
        System.out.printf("│ %-27s │ %16.2f │ %16.2f │%n", metric, gats, tabu);
    }
    
    private static void printRow(String metric, long gats, long tabu) {
        System.out.printf("│ %-27s │ %16d │ %16d │%n", metric, gats, tabu);
    }
    
    private static class ExperimentResult {
        final AlgorithmType algorithmType;
        final long executionTimeMs;
        final double finalFitness;
        final double avgFlightOccupancy;
        final double slaComplianceRate;
        final int capacityViolations;
        final int totalRoutes;
        
        ExperimentResult(AlgorithmType algorithmType, long executionTimeMs, double finalFitness,
                        double avgFlightOccupancy, double slaComplianceRate,
                        int capacityViolations, int totalRoutes) {
            this.algorithmType = algorithmType;
            this.executionTimeMs = executionTimeMs;
            this.finalFitness = finalFitness;
            this.avgFlightOccupancy = avgFlightOccupancy;
            this.slaComplianceRate = slaComplianceRate;
            this.capacityViolations = capacityViolations;
            this.totalRoutes = totalRoutes;
        }
    }
}
