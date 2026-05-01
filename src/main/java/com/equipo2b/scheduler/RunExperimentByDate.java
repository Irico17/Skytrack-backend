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
import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * FASE 4: Experimentación Numérica por Fecha Específica
 * 
 * <p>Ejecuta múltiples corridas de GATS vs Tabu Search con los lotes de una fecha específica.
 * Diseñado para probar el sistema a diferentes niveles de capacidad (70%, 80%, 90%, etc.)
 * 
 * <p><strong>Uso:</strong>
 * <pre>
 * java -cp "build/classes/java/main" com.equipo2b.scheduler.RunExperimentByDate 2027-03-12
 * </pre>
 * 
 * <p><strong>Características:</strong>
 * <ul>
 *   <li>Carga TODOS los archivos de envíos (30 archivos)</li>
 *   <li>Filtra solo los lotes de la fecha especificada</li>
 *   <li>Ejecuta 10 corridas de GATS y 10 de Tabu</li>
 *   <li>Guarda resultados en CSV y log completo en TXT</li>
 *   <li>Análisis de capacidad y SLA compliance</li>
 * </ul>
 */
public class RunExperimentByDate {
    
    // PARÁMETROS DE EXPERIMENTACIÓN
    private static final int NUM_RUNS = 10;
    private static final int MAX_CYCLES = 1;
    
    // Parámetros de simulación
    private static final int Ta = 2;
    private static final int Sa = 5;
    private static final int K = 1440;  // 24 horas = 1440 minutos (procesar todo el día)
    
    // Logger para archivo
    private static PrintWriter logWriter;
    
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("ERROR: Debe especificar una fecha");
            System.err.println("Uso: java RunExperimentByDate <fecha>");
            System.err.println("Ejemplo: java RunExperimentByDate 2027-03-12");
            System.exit(1);
        }
        
        String dateStr = args[0];
        LocalDate targetDate;
        
        try {
            targetDate = LocalDate.parse(dateStr);
        } catch (Exception e) {
            System.err.println("ERROR: Formato de fecha inválido. Use YYYY-MM-DD");
            System.err.println("Ejemplo: 2027-03-12");
            System.exit(1);
            return;
        }
        
        // Crear directorio de resultados
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String resultsDir = "experiment_results_" + dateStr + "_" + timestamp;
        new File(resultsDir).mkdirs();
        
        try {
            // Abrir archivo de log
            logWriter = new PrintWriter(new FileWriter(resultsDir + "/experiment_log.txt"));
            
            log("=".repeat(80));
            log("EXPERIMENTACIÓN NUMÉRICA POR FECHA: " + dateStr);
            log("=".repeat(80));
            log("");
            
            // Cargar datos
            log(">>> CARGANDO DATOS <<<");
            ExperimentData data = loadDataForDate(targetDate);
            
            if (data.batches.isEmpty()) {
                log("ERROR: No se encontraron lotes para la fecha " + dateStr);
                log("Verifique que la fecha existe en los archivos de envíos");
                logWriter.close();
                System.err.println("ERROR: No se encontraron lotes para la fecha " + dateStr);
                System.exit(1);
                return;
            }
            
            log("✓ Datos cargados para fecha: " + dateStr);
            log("✓ Lotes encontrados: " + data.batches.size());
            log("✓ Maletas totales: " + data.batches.stream().mapToInt(ShipmentBatch::quantity).sum());
            log("");
            
            log("📊 CONFIGURACIÓN DEL EXPERIMENTO");
            log("Fecha objetivo: " + dateStr);
            log("Número de corridas por algoritmo: " + NUM_RUNS);
            log("Ciclos de planificación: " + MAX_CYCLES);
            log("Parámetros: Ta=" + Ta + " min, Sa=" + Sa + " min, K=" + K);
            log("Directorio de resultados: " + resultsDir);
            log("");
            
            // Ejecutar experimentos para GATS
            log("=".repeat(80));
            log(">>> EJECUTANDO GATS (" + NUM_RUNS + " corridas) <<<");
            log("=".repeat(80));
            List<ExperimentResult> gatsResults = runMultipleExperiments(
                AlgorithmType.GATS, data, NUM_RUNS, resultsDir + "/gats_results.csv", targetDate
            );
            
            // Ejecutar experimentos para Tabu Puro
            log("");
            log("=".repeat(80));
            log(">>> EJECUTANDO TABU PURO (" + NUM_RUNS + " corridas) <<<");
            log("=".repeat(80));
            List<ExperimentResult> tabuResults = runMultipleExperiments(
                AlgorithmType.TABU_PURE, data, NUM_RUNS, resultsDir + "/tabu_results.csv", targetDate
            );
            
            // Guardar resumen
            saveExperimentSummary(gatsResults, tabuResults, resultsDir + "/summary.txt", targetDate, data);
            
            // Imprimir resumen
            printSummary(gatsResults, tabuResults, data);
            
            log("");
            log("✅ Experimentación completada");
            log("📁 Resultados guardados en: " + resultsDir);
            log("");
            
            logWriter.close();
            
            System.out.println("✅ Experimentación completada");
            System.out.println("📁 Resultados guardados en: " + resultsDir);
            System.out.println("📄 Log completo: " + resultsDir + "/experiment_log.txt");
            
        } catch (Exception e) {
            log("❌ Error: " + e.getMessage());
            e.printStackTrace(logWriter);
            if (logWriter != null) logWriter.close();
            System.err.println("❌ Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static void log(String message) {
        System.out.println(message);
        if (logWriter != null) {
            logWriter.println(message);
            logWriter.flush();
        }
    }
    
    private static ExperimentData loadDataForDate(LocalDate targetDate) throws Exception {
        // Aeropuertos
        String airportFile = "data/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt";
        AirportUploader airportUploader = new AirportUploader();
        List<Airport> airports = airportUploader.loadAirports(airportFile);
        AirportManager airportManager = new AirportManager();
        for (Airport airport : airports) {
            airportManager.addAirport(airport);
        }
        log("✓ Aeropuertos: " + airports.size());
        
        // Vuelos
        String flightFile = "data/planes_vuelo.txt";
        FlightPlanUploader flightUploader = new FlightPlanUploader();
        FlightPlan flightPlan = flightUploader.loadFlights(flightFile, airportManager);
        log("✓ Vuelos: " + flightPlan.getAllFlights().size());
        
        // Clientes (auto-registro)
        ClientRegistry clientRegistry = new ClientRegistry() {
            @Override
            public boolean validateClientExists(String clientId) {
                if (!super.validateClientExists(clientId)) {
                    addClient(new AirlineClient(
                        clientId, "Airline " + clientId, 
                        "contact@airline" + clientId + ".com", "+000000000"
                    ));
                }
                return true;
            }
        };
        
        // Cargar TODOS los archivos de envíos y filtrar por fecha
        ShipmentUploader shipmentUploader = new ShipmentUploader();
        List<ShipmentBatch> filteredBatches = new ArrayList<>();
        int totalBatchesLoaded = 0;
        int filesProcessed = 0;
        
        File enviosDir = new File("data/_envios_preliminar_");
        if (enviosDir.exists() && enviosDir.isDirectory()) {
            File[] files = enviosDir.listFiles((dir, name) -> name.endsWith(".txt"));
            if (files != null) {
                log("Cargando archivos de envíos...");
                for (File file : files) {
                    try {
                        List<ShipmentBatch> batches = shipmentUploader.loadShipments(
                            file.getAbsolutePath(), airportManager, clientRegistry
                        );
                        totalBatchesLoaded += batches.size();
                        
                        // Filtrar solo lotes de la fecha objetivo
                        for (ShipmentBatch batch : batches) {
                            if (batch.ingressTime().toLocalDate().equals(targetDate)) {
                                filteredBatches.add(batch);
                            }
                        }
                        
                        filesProcessed++;
                        if (filesProcessed % 5 == 0) {
                            log("  Procesados " + filesProcessed + "/" + files.length + " archivos...");
                        }
                    } catch (Exception e) {
                        log("⚠ Error cargando " + file.getName() + ": " + e.getMessage());
                    }
                }
            }
        }
        
        log("✓ Archivos procesados: " + filesProcessed);
        log("✓ Total lotes cargados: " + totalBatchesLoaded);
        log("✓ Lotes filtrados para " + targetDate + ": " + filteredBatches.size());
        
        return new ExperimentData(flightPlan, airportManager, filteredBatches);
    }
    
    private static List<ExperimentResult> runMultipleExperiments(
            AlgorithmType algorithmType,
            ExperimentData data,
            int numRuns,
            String outputFile,
            LocalDate targetDate) throws Exception {
        
        List<ExperimentResult> results = new ArrayList<>();
        
        // Crear archivo CSV
        PrintWriter csvWriter = new PrintWriter(new FileWriter(outputFile));
        csvWriter.println("run,fitness,execution_time_ms,routes_generated,sla_compliance_pct," +
                      "avg_flight_occupancy_pct,capacity_violations,packages_assigned,packages_total");
        
        for (int run = 1; run <= numRuns; run++) {
            log("");
            log("--- Corrida " + run + "/" + numRuns + " ---");
            
            // Crear cola
            ShipmentQueue queue = new ShipmentQueue();
            
            // Modificar ingressTime para variación
            int baseSeed = run * 1000;
            int algorithmOffset = (algorithmType == AlgorithmType.GATS) ? 0 : 50000;
            int seed = baseSeed + algorithmOffset;
            
            List<ShipmentBatch> modifiedBatches = new ArrayList<>();
            Random timeRandom = new Random(seed);
            
            for (ShipmentBatch originalBatch : data.batches) {
                int offsetMinutes = timeRandom.nextInt(121) - 60;
                ZonedDateTime newIngressTime = originalBatch.ingressTime().plusMinutes(offsetMinutes);
                
                ShipmentBatch modifiedBatch = new ShipmentBatch(
                    originalBatch.batchId(),
                    originalBatch.airportBatchId(),
                    originalBatch.clientId(),
                    originalBatch.origin(),
                    originalBatch.destination(),
                    originalBatch.quantity(),
                    newIngressTime
                );
                
                modifiedBatches.add(modifiedBatch);
            }
            
            for (ShipmentBatch batch : modifiedBatches) {
                queue.addShipment(batch);
            }
            
            // Crear componentes
            SolutionEvaluator evaluator = new SolutionEvaluator(data.flightPlan, data.airportManager);
            RouteValidator validator = new RouteValidator(data.airportManager);
            
            // Crear Scheduler
            Scheduler scheduler;
            if (algorithmType == AlgorithmType.GATS) {
                scheduler = SchedulerFactory.createGATSScheduler(
                    data.flightPlan, data.airportManager, queue, evaluator, validator, Ta, Sa, K
                );
            } else {
                scheduler = SchedulerFactory.createTabuScheduler(
                    data.flightPlan, data.airportManager, queue, evaluator, validator, Ta, Sa, K
                );
            }
            
            // Ejecutar y medir tiempo
            ZonedDateTime startTime = data.batches.get(0).ingressTime();
            long startMs = System.currentTimeMillis();
            Solution solution = scheduler.run(startTime, MAX_CYCLES);
            long executionTimeMs = System.currentTimeMillis() - startMs;
            
            // Calcular métricas
            CapacityMonitor monitor = new CapacityMonitor(data.flightPlan, data.airportManager);
            double avgFlightOccupancy = monitor.calculateAverageFlightOccupancy(solution);
            
            ValidationReport report = validator.validate(solution);
            int capacityViolations = report.getViolations().size();
            
            int totalRoutes = solution.getRoutes().size();
            int slaCompliant = (int) solution.getRoutes().values().stream()
                .filter(route -> route.meetsSLA())
                .count();
            double slaComplianceRate = totalRoutes > 0 ? (slaCompliant * 100.0 / totalRoutes) : 0;
            
            int totalPackages = data.batches.stream().mapToInt(ShipmentBatch::quantity).sum();
            int assignedPackages = solution.getRoutes().values().stream()
                .mapToInt(route -> route.getBatch().quantity())
                .sum();
            double assignmentRate = totalPackages > 0 ? (assignedPackages * 100.0 / totalPackages) : 0;
            
            // Crear resultado
            ExperimentResult result = new ExperimentResult(
                run,
                algorithmType,
                solution.getFitness(),
                executionTimeMs,
                totalRoutes,
                slaComplianceRate,
                avgFlightOccupancy,
                capacityViolations,
                assignedPackages,
                totalPackages
            );
            results.add(result);
            
            // Escribir a CSV
            csvWriter.printf("%d,%.2f,%d,%d,%.2f,%.2f,%d,%d,%d%n",
                run,
                result.fitness,
                result.executionTimeMs,
                result.routesGenerated,
                result.slaComplianceRate,
                result.avgFlightOccupancy,
                result.capacityViolations,
                result.packagesAssigned,
                result.totalPackages
            );
            csvWriter.flush();
            
            // Log resultado con maletas
            log(String.format("Fitness: %.2f | Tiempo: %.2f seg | Rutas: %d | Maletas: %d/%d (%.1f%%) | SLA: %.1f%% | Violaciones: %d",
                result.fitness, result.executionTimeMs / 1000.0, result.routesGenerated, 
                result.packagesAssigned, result.totalPackages, assignmentRate,
                result.slaComplianceRate, result.capacityViolations));
        }
        
        csvWriter.close();
        log("");
        log("✓ Resultados guardados en: " + outputFile);
        
        return results;
    }
    
    private static void saveExperimentSummary(
            List<ExperimentResult> gatsResults,
            List<ExperimentResult> tabuResults,
            String outputFile,
            LocalDate targetDate,
            ExperimentData data) throws Exception {
        
        PrintWriter writer = new PrintWriter(new FileWriter(outputFile));
        
        writer.println("=".repeat(80));
        writer.println("RESUMEN DE EXPERIMENTACIÓN POR FECHA");
        writer.println("=".repeat(80));
        writer.println();
        writer.println("Fecha: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        writer.println("Fecha objetivo: " + targetDate);
        writer.println();
        
        int totalBags = data.batches.stream().mapToInt(ShipmentBatch::quantity).sum();
        double capacityPct = (totalBags * 100.0) / 12930;
        
        writer.println("DATOS DE ENTRADA:");
        writer.println("  Lotes procesados: " + data.batches.size());
        writer.println("  Maletas totales: " + totalBags);
        writer.println("  Capacidad del sistema: 12,930 maletas/día");
        writer.println(String.format("  Carga del sistema: %.1f%%", capacityPct));
        writer.println();
        writer.println("CONFIGURACIÓN:");
        writer.println("  Corridas por algoritmo: " + NUM_RUNS);
        writer.println("  Ciclos de planificación: " + MAX_CYCLES);
        writer.println("  Parámetros: Ta=" + Ta + " min, Sa=" + Sa + " min, K=" + K);
        writer.println();
        
        writer.println("GATS - Estadísticas:");
        printStatistics(writer, gatsResults);
        writer.println();
        
        writer.println("TABU PURO - Estadísticas:");
        printStatistics(writer, tabuResults);
        writer.println();
        
        // Comparación
        double gatsAvgFitness = gatsResults.stream().mapToDouble(r -> r.fitness).average().orElse(0);
        double tabuAvgFitness = tabuResults.stream().mapToDouble(r -> r.fitness).average().orElse(0);
        
        writer.println("COMPARACIÓN:");
        if (gatsAvgFitness < tabuAvgFitness) {
            double improvement = ((tabuAvgFitness - gatsAvgFitness) / Math.abs(tabuAvgFitness)) * 100;
            writer.printf("  GATS tiene mejor fitness promedio (%.2f%% mejor)%n", improvement);
        } else {
            double improvement = ((gatsAvgFitness - tabuAvgFitness) / Math.abs(gatsAvgFitness)) * 100;
            writer.printf("  TABU tiene mejor fitness promedio (%.2f%% mejor)%n", improvement);
        }
        writer.println();
        
        writer.println("=".repeat(80));
        
        writer.close();
    }
    
    private static void printStatistics(PrintWriter writer, List<ExperimentResult> results) {
        double avgFitness = results.stream().mapToDouble(r -> r.fitness).average().orElse(0);
        double minFitness = results.stream().mapToDouble(r -> r.fitness).min().orElse(0);
        double maxFitness = results.stream().mapToDouble(r -> r.fitness).max().orElse(0);
        double stdFitness = calculateStdDev(results.stream().mapToDouble(r -> r.fitness).toArray());
        
        double avgTime = results.stream().mapToDouble(r -> r.executionTimeMs).average().orElse(0);
        double avgSLA = results.stream().mapToDouble(r -> r.slaComplianceRate).average().orElse(0);
        double avgRoutes = results.stream().mapToDouble(r -> r.routesGenerated).average().orElse(0);
        double avgViolations = results.stream().mapToDouble(r -> r.capacityViolations).average().orElse(0);
        double avgPackagesAssigned = results.stream().mapToDouble(r -> r.packagesAssigned).average().orElse(0);
        double avgPackagesTotal = results.stream().mapToDouble(r -> r.totalPackages).average().orElse(0);
        double avgAssignmentRate = avgPackagesTotal > 0 ? (avgPackagesAssigned * 100.0 / avgPackagesTotal) : 0;
        
        writer.printf("  Fitness promedio: %.2f%n", avgFitness);
        writer.printf("  Fitness mínimo: %.2f%n", minFitness);
        writer.printf("  Fitness máximo: %.2f%n", maxFitness);
        writer.printf("  Desviación estándar: %.2f%n", stdFitness);
        writer.printf("  Tiempo promedio: %.2f seg%n", avgTime / 1000.0);
        writer.printf("  SLA compliance promedio: %.2f%%%n", avgSLA);
        writer.printf("  Rutas promedio: %.0f%n", avgRoutes);
        writer.printf("  Maletas asignadas promedio: %.0f / %.0f (%.1f%%)%n", 
                     avgPackagesAssigned, avgPackagesTotal, avgAssignmentRate);
        writer.printf("  Violaciones promedio: %.1f%n", avgViolations);
    }
    
    private static void printSummary(List<ExperimentResult> gatsResults, 
                                    List<ExperimentResult> tabuResults,
                                    ExperimentData data) {
        log("");
        log("=".repeat(80));
        log("RESUMEN DE RESULTADOS");
        log("=".repeat(80));
        log("");
        
        int totalBags = data.batches.stream().mapToInt(ShipmentBatch::quantity).sum();
        double capacityPct = (totalBags * 100.0) / 12930;
        log(String.format("Carga del sistema: %d maletas (%.1f%% de capacidad)", totalBags, capacityPct));
        log("");
        
        double gatsAvgFitness = gatsResults.stream().mapToDouble(r -> r.fitness).average().orElse(0);
        double tabuAvgFitness = tabuResults.stream().mapToDouble(r -> r.fitness).average().orElse(0);
        
        double gatsAvgTime = gatsResults.stream().mapToDouble(r -> r.executionTimeMs).average().orElse(0);
        double tabuAvgTime = tabuResults.stream().mapToDouble(r -> r.executionTimeMs).average().orElse(0);
        
        double gatsAvgViolations = gatsResults.stream().mapToDouble(r -> r.capacityViolations).average().orElse(0);
        double tabuAvgViolations = tabuResults.stream().mapToDouble(r -> r.capacityViolations).average().orElse(0);
        
        log(String.format("GATS  - Fitness: %.2f | Tiempo: %.2f seg | Violaciones: %.1f", 
            gatsAvgFitness, gatsAvgTime / 1000.0, gatsAvgViolations));
        log(String.format("TABU  - Fitness: %.2f | Tiempo: %.2f seg | Violaciones: %.1f", 
            tabuAvgFitness, tabuAvgTime / 1000.0, tabuAvgViolations));
        log("");
        
        if (gatsAvgFitness < tabuAvgFitness) {
            double improvement = ((tabuAvgFitness - gatsAvgFitness) / Math.abs(tabuAvgFitness)) * 100;
            log(String.format("✓ GATS tiene mejor fitness promedio (%.2f%% mejor)", improvement));
        } else {
            double improvement = ((gatsAvgFitness - tabuAvgFitness) / Math.abs(gatsAvgFitness)) * 100;
            log(String.format("✓ TABU tiene mejor fitness promedio (%.2f%% mejor)", improvement));
        }
    }
    
    private static double calculateStdDev(double[] values) {
        if (values.length == 0) return 0;
        
        double mean = 0;
        for (double v : values) mean += v;
        mean /= values.length;
        
        double variance = 0;
        for (double v : values) {
            variance += Math.pow(v - mean, 2);
        }
        variance /= values.length;
        
        return Math.sqrt(variance);
    }
    
    private static class ExperimentData {
        final FlightPlan flightPlan;
        final AirportManager airportManager;
        final List<ShipmentBatch> batches;
        
        ExperimentData(FlightPlan flightPlan, AirportManager airportManager, List<ShipmentBatch> batches) {
            this.flightPlan = flightPlan;
            this.airportManager = airportManager;
            this.batches = batches;
        }
    }
    
    private static class ExperimentResult {
        final int run;
        final AlgorithmType algorithmType;
        final double fitness;
        final long executionTimeMs;
        final int routesGenerated;
        final double slaComplianceRate;
        final double avgFlightOccupancy;
        final int capacityViolations;
        final int packagesAssigned;
        final int totalPackages;
        
        ExperimentResult(int run, AlgorithmType algorithmType, double fitness, long executionTimeMs,
                        int routesGenerated, double slaComplianceRate, double avgFlightOccupancy,
                        int capacityViolations, int packagesAssigned, int totalPackages) {
            this.run = run;
            this.algorithmType = algorithmType;
            this.fitness = fitness;
            this.executionTimeMs = executionTimeMs;
            this.routesGenerated = routesGenerated;
            this.slaComplianceRate = slaComplianceRate;
            this.avgFlightOccupancy = avgFlightOccupancy;
            this.capacityViolations = capacityViolations;
            this.packagesAssigned = packagesAssigned;
            this.totalPackages = totalPackages;
        }
    }
}
