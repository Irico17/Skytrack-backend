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
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Experimentación Numérica: GATS vs Tabu Search Puro
 * 
 * <p>Ejecuta múltiples corridas de ambos algoritmos y guarda los resultados
 * en archivos CSV para análisis estadístico posterior.
 * 
 * <p>Basado en metodología de experimentación numérica con:
 * <ul>
 *   <li>Múltiples corridas independientes (n=10 por defecto)</li>
 *   <li>Parámetros configurables de algoritmos</li>
 *   <li>Métricas completas por corrida</li>
 *   <li>Exportación a CSV para análisis estadístico</li>
 * </ul>
 * 
 * <p><strong>Variables independientes:</strong>
 * <ul>
 *   <li>Algoritmo: GATS o Tabu Puro</li>
 *   <li>Iteraciones: 250, 500, etc.</li>
 *   <li>Instancia: cantidad de lotes procesados</li>
 * </ul>
 * 
 * <p><strong>Variables dependientes:</strong>
 * <ul>
 *   <li>Fitness de la solución</li>
 *   <li>Paquetes asignados (%)</li>
 *   <li>Tasa de entregas a tiempo (SLA compliance)</li>
 *   <li>Tiempo de ejecución</li>
 *   <li>Rutas generadas</li>
 * </ul>
 * 
 * <p><strong>IMPORTANTE - Configuración para variación de fitness:</strong>
 * <ul>
 *   <li>MAX_CYCLES = 1: Medir fitness del primer ciclo solamente (evita acumulación)</li>
 *   <li>K = 50: Ventana de consumo amplia para procesar más lotes por ciclo</li>
 *   <li>FILES_TO_LOAD = 2: Balance entre velocidad y tamaño de problema</li>
 *   <li>Modificación de ingressTime: Cada corrida modifica aleatoriamente los tiempos de ingreso</li>
 * </ul>
 * 
 * <p><strong>Por qué el fitness era idéntico antes:</strong>
 * <ol>
 *   <li>Múltiples ciclos acumulaban rutas, convergiendo al mismo fitness final</li>
 *   <li>consumeShipments() filtra por ingressTime, anulando el efecto del shuffling</li>
 *   <li>Siempre se procesaban los mismos lotes (aquellos en la ventana temporal)</li>
 *   <li>Problema sobre-restringido: una sola solución óptima factible</li>
 * </ol>
 * 
 * <p><strong>Solución implementada:</strong>
 * <ul>
 *   <li>Modificar aleatoriamente el ingressTime de cada lote (±60 minutos)</li>
 *   <li>Esto hace que diferentes lotes caigan en la ventana de consumo por corrida</li>
 *   <li>Genera instancias de problema diferentes para cada corrida</li>
 *   <li>Usar semillas diferentes para GATS y Tabu (offset de 50000)</li>
 * </ul>
 */
public class RunNumericalExperiment {
    
    // PARÁMETROS DE EXPERIMENTACIÓN - MODIFICAR AQUÍ
    private static final int NUM_RUNS = 10;             // Número de corridas por algoritmo
    private static final int FILES_TO_LOAD = 10;         // Archivos de envíos a cargar (reducido para velocidad)
    private static final int MAX_CYCLES = 1;            // Ciclos de planificación (1 = medir primer ciclo solamente)
    
    // Parámetros de simulación
    private static final int Ta = 2;  // Tiempo de algoritmo (minutos)
    private static final int Sa = 5;  // Salto entre ejecuciones (minutos)
    private static final int K = 100;  // Ventana de consumo (aumentado para más lotes por ciclo)
    
    // Parámetros de algoritmos - GATS
    private static final int GA_POPULATION = 50;
    private static final int GA_GENERATIONS = 100;
    private static final double GA_MUTATION_RATE = 0.3;  // Aumentado de 0.1 a 0.3 para más diversidad
    private static final int TABU_REFINE_ITERATIONS = 200;
    
    // Parámetros de algoritmos - Tabu Puro
    private static final int TABU_ITERATIONS = 300;
    private static final int TABU_TENURE = 20;
    private static final int TABU_NEIGHBORHOOD = 30;
    
    public static void main(String[] args) {
        System.out.println("=".repeat(80));
        System.out.println("EXPERIMENTACIÓN NUMÉRICA: GATS vs Tabu Search Puro");
        System.out.println("=".repeat(80));
        System.out.println();
        
        // Crear directorio de resultados
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String resultsDir = "experiment_results_" + timestamp;
        new File(resultsDir).mkdirs();
        
        System.out.println("📊 CONFIGURACIÓN DEL EXPERIMENTO");
        System.out.println("Número de corridas por algoritmo: " + NUM_RUNS);
        System.out.println("Archivos de envíos: " + FILES_TO_LOAD);
        System.out.println("Ciclos de planificación: " + MAX_CYCLES);
        System.out.println("Parámetros: Ta=" + Ta + " min, Sa=" + Sa + " min, K=" + K);
        System.out.println("Directorio de resultados: " + resultsDir);
        System.out.println();
        
        try {
            // Configurar modo lenient
            ValidationMode.setMode(ValidationMode.Mode.LENIENT);
            
            // Cargar datos una sola vez (reutilizar en todas las corridas)
            System.out.println(">>> CARGANDO DATOS <<<");
            ExperimentData data = loadData();
            System.out.println("✓ Datos cargados: " + data.batches.size() + " lotes");
            System.out.println();
            
            // Ejecutar experimentos para GATS
            System.out.println("=".repeat(80));
            System.out.println(">>> EJECUTANDO GATS (" + NUM_RUNS + " corridas) <<<");
            System.out.println("=".repeat(80));
            List<ExperimentResult> gatsResults = runMultipleExperiments(
                AlgorithmType.GATS, data, NUM_RUNS, resultsDir + "/gats_results.csv"
            );
            
            // Ejecutar experimentos para Tabu Puro
            System.out.println("\n" + "=".repeat(80));
            System.out.println(">>> EJECUTANDO TABU PURO (" + NUM_RUNS + " corridas) <<<");
            System.out.println("=".repeat(80));
            List<ExperimentResult> tabuResults = runMultipleExperiments(
                AlgorithmType.TABU_PURE, data, NUM_RUNS, resultsDir + "/tabu_results.csv"
            );
            
            // Guardar resumen
            saveExperimentSummary(gatsResults, tabuResults, resultsDir + "/summary.txt");
            
            // Imprimir resumen en consola
            printSummary(gatsResults, tabuResults);
            
            System.out.println("\n✅ Experimentación completada");
            System.out.println("📁 Resultados guardados en: " + resultsDir);
            System.out.println();
            System.out.println("Próximo paso: Ejecutar ExperimentAnalyzer para análisis estadístico");
            
        } catch (Exception e) {
            System.err.println("❌ Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static ExperimentData loadData() throws Exception {
        // Aeropuertos
        String airportFile = "data/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt";
        AirportUploader airportUploader = new AirportUploader();
        List<Airport> airports = airportUploader.loadAirports(airportFile);
        AirportManager airportManager = new AirportManager();
        for (Airport airport : airports) {
            airportManager.addAirport(airport);
        }
        System.out.println("✓ Aeropuertos: " + airports.size());
        
        // Vuelos
        String flightFile = "data/planes_vuelo.txt";
        FlightPlanUploader flightUploader = new FlightPlanUploader();
        FlightPlan flightPlan = flightUploader.loadFlights(flightFile, airportManager);
        System.out.println("✓ Vuelos: " + flightPlan.getAllFlights().size());
        
        // Clientes (pre-cargar)
        ClientRegistry clientRegistry = new ClientRegistry();
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
                                        clientId, "Airline " + clientId, 
                                        "contact@airline" + clientId + ".com", "+000000000"
                                    ));
                                }
                            }
                        }
                        reader.close();
                    } catch (Exception e) {
                        // Ignorar
                    }
                }
            }
        }
        System.out.println("✓ Clientes: " + clientRegistry.size());
        
        // Cargar envíos
        ShipmentUploader shipmentUploader = new ShipmentUploader();
        List<ShipmentBatch> allBatches = new ArrayList<>();
        int filesLoaded = 0;
        if (enviosDir.exists() && enviosDir.isDirectory()) {
            File[] files = enviosDir.listFiles((dir, name) -> name.endsWith(".txt"));
            if (files != null) {
                for (File file : files) {
                    if (filesLoaded >= FILES_TO_LOAD) break;
                    try {
                        List<ShipmentBatch> batches = shipmentUploader.loadShipments(
                            file.getAbsolutePath(), airportManager, clientRegistry
                        );
                        allBatches.addAll(batches);
                        System.out.println("✓ " + file.getName() + ": " + batches.size() + " lotes");
                        filesLoaded++;
                    } catch (Exception e) {
                        System.out.println("⚠ Error: " + file.getName());
                    }
                }
            }
        }
        System.out.println("✓ Total lotes: " + allBatches.size());
        
        return new ExperimentData(flightPlan, airportManager, allBatches);
    }
    
    private static List<ExperimentResult> runMultipleExperiments(
            AlgorithmType algorithmType,
            ExperimentData data,
            int numRuns,
            String outputFile) throws Exception {
        
        List<ExperimentResult> results = new ArrayList<>();
        
        // Crear archivo CSV
        PrintWriter writer = new PrintWriter(new FileWriter(outputFile));
        writer.println("run,fitness,execution_time_ms,routes_generated,sla_compliance_pct," +
                      "avg_flight_occupancy_pct,capacity_violations,packages_assigned");
        
        for (int run = 1; run <= numRuns; run++) {
            System.out.println("\n--- Corrida " + run + "/" + numRuns + " ---");
            
            // Crear cola (copia independiente para cada corrida)
            ShipmentQueue queue = new ShipmentQueue();
            
            // PROBLEMA: consumeShipments() filtra por ingressTime, así que shuffling no ayuda
            // SOLUCIÓN: Modificar aleatoriamente los ingressTime de los lotes para cada corrida
            // Esto hace que diferentes lotes caigan en la ventana de consumo
            
            // IMPORTANTE: Usar semilla diferente para cada algoritmo
            // Si GATS y Tabu usan la misma semilla, procesan los mismos lotes
            int baseSeed = run * 1000;
            int algorithmOffset = (algorithmType == AlgorithmType.GATS) ? 0 : 50000;
            int seed = baseSeed + algorithmOffset;
            
            List<ShipmentBatch> modifiedBatches = new ArrayList<>();
            Random timeRandom = new Random(seed);
            
            // Obtener tiempo base del primer lote
            ZonedDateTime baseTime = data.batches.get(0).ingressTime();
            
            System.out.println("Modificando ingressTime de lotes (semilla: " + seed + ")");
            
            for (ShipmentBatch originalBatch : data.batches) {
                // Agregar offset aleatorio de -60 a +60 minutos al ingressTime
                int offsetMinutes = timeRandom.nextInt(121) - 60;  // -60 a +60
                ZonedDateTime newIngressTime = originalBatch.ingressTime().plusMinutes(offsetMinutes);
                
                // Crear nuevo batch con ingressTime modificado
                ShipmentBatch modifiedBatch = new ShipmentBatch(
                    originalBatch.batchId(),
                    originalBatch.airportBatchId(),
                    originalBatch.clientId(),
                    originalBatch.origin(),
                    originalBatch.destination(),
                    originalBatch.quantity(),
                    newIngressTime  // Tiempo modificado
                );
                
                modifiedBatches.add(modifiedBatch);
            }
            
            // Agregar lotes modificados a la cola
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
            // IMPORTANTE: Usamos MAX_CYCLES=1 para medir fitness del primer ciclo solamente
            // Si usamos múltiples ciclos, el Scheduler acumula rutas y el fitness final
            // converge a valores similares independientemente del orden de procesamiento
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
            writer.printf("%d,%.2f,%d,%d,%.2f,%.2f,%d,%d%n",
                run,
                result.fitness,
                result.executionTimeMs,
                result.routesGenerated,
                result.slaComplianceRate,
                result.avgFlightOccupancy,
                result.capacityViolations,
                result.packagesAssigned
            );
            writer.flush();
            
            // Imprimir resultado
            System.out.printf("Fitness: %.2f | Tiempo: %.2f seg | Rutas: %d | SLA: %.1f%%%n",
                result.fitness, result.executionTimeMs / 1000.0, result.routesGenerated, result.slaComplianceRate);
        }
        
        writer.close();
        System.out.println("\n✓ Resultados guardados en: " + outputFile);
        
        return results;
    }
    
    private static void saveExperimentSummary(
            List<ExperimentResult> gatsResults,
            List<ExperimentResult> tabuResults,
            String outputFile) throws Exception {
        
        PrintWriter writer = new PrintWriter(new FileWriter(outputFile));
        
        writer.println("=".repeat(80));
        writer.println("RESUMEN DE EXPERIMENTACIÓN NUMÉRICA");
        writer.println("=".repeat(80));
        writer.println();
        writer.println("Fecha: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        writer.println();
        writer.println("CONFIGURACIÓN:");
        writer.println("  Corridas por algoritmo: " + NUM_RUNS);
        writer.println("  Archivos de envíos: " + FILES_TO_LOAD);
        writer.println("  Ciclos de planificación: " + MAX_CYCLES);
        writer.println("  Parámetros: Ta=" + Ta + " min, Sa=" + Sa + " min, K=" + K);
        writer.println();
        
        writer.println("GATS - Estadísticas:");
        printStatistics(writer, gatsResults);
        writer.println();
        
        writer.println("TABU PURO - Estadísticas:");
        printStatistics(writer, tabuResults);
        writer.println();
        
        writer.println("=".repeat(80));
        writer.println("NOTA: Ejecutar ExperimentAnalyzer para análisis estadístico completo");
        writer.println("=".repeat(80));
        
        writer.close();
    }
    
    private static void printStatistics(PrintWriter writer, List<ExperimentResult> results) {
        double avgFitness = results.stream().mapToDouble(r -> r.fitness).average().orElse(0);
        double stdFitness = calculateStdDev(results.stream().mapToDouble(r -> r.fitness).toArray());
        
        double avgTime = results.stream().mapToDouble(r -> r.executionTimeMs).average().orElse(0);
        double avgSLA = results.stream().mapToDouble(r -> r.slaComplianceRate).average().orElse(0);
        double avgRoutes = results.stream().mapToDouble(r -> r.routesGenerated).average().orElse(0);
        
        writer.printf("  Fitness promedio: %.2f%n", avgFitness);
        writer.printf("  Desviación estándar fitness: %.2f%n", stdFitness);
        writer.printf("  Tiempo promedio: %.2f seg%n", avgTime / 1000.0);
        writer.printf("  SLA compliance promedio: %.2f%%%n", avgSLA);
        writer.printf("  Rutas promedio: %.0f%n", avgRoutes);
    }
    
    private static void printSummary(List<ExperimentResult> gatsResults, List<ExperimentResult> tabuResults) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("RESUMEN DE RESULTADOS");
        System.out.println("=".repeat(80));
        System.out.println();
        
        double gatsAvgFitness = gatsResults.stream().mapToDouble(r -> r.fitness).average().orElse(0);
        double tabuAvgFitness = tabuResults.stream().mapToDouble(r -> r.fitness).average().orElse(0);
        
        double gatsAvgTime = gatsResults.stream().mapToDouble(r -> r.executionTimeMs).average().orElse(0);
        double tabuAvgTime = tabuResults.stream().mapToDouble(r -> r.executionTimeMs).average().orElse(0);
        
        System.out.printf("GATS  - Fitness promedio: %.2f | Tiempo promedio: %.2f seg%n", 
            gatsAvgFitness, gatsAvgTime / 1000.0);
        System.out.printf("TABU  - Fitness promedio: %.2f | Tiempo promedio: %.2f seg%n", 
            tabuAvgFitness, tabuAvgTime / 1000.0);
        System.out.println();
        
        if (gatsAvgFitness < tabuAvgFitness) {
            double improvement = ((tabuAvgFitness - gatsAvgFitness) / Math.abs(tabuAvgFitness)) * 100;
            System.out.printf("✓ GATS tiene mejor fitness promedio (%.2f%% mejor)%n", improvement);
        } else {
            double improvement = ((gatsAvgFitness - tabuAvgFitness) / Math.abs(gatsAvgFitness)) * 100;
            System.out.printf("✓ TABU tiene mejor fitness promedio (%.2f%% mejor)%n", improvement);
        }
    }
    
    private static double calculateStdDev(double[] values) {
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
