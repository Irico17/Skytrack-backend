package com.equipo2b.scheduler;

import com.equipo2b.scheduler.algorithm.AlgorithmType;
import com.equipo2b.scheduler.execution.Scheduler;
import com.equipo2b.scheduler.execution.SchedulerFactory;
import com.equipo2b.scheduler.logic.SolutionEvaluator;
import com.equipo2b.scheduler.model.*;
import com.equipo2b.scheduler.upload.*;
import com.equipo2b.scheduler.validation.RouteValidator;

import java.io.File;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Prueba rápida de Tabu Search Puro con datos reales limitados.
 */
public class RunTabuPureTest {
    
    public static void main(String[] args) {
        System.out.println("=".repeat(80));
        System.out.println("PRUEBA: Tabu Search Puro");
        System.out.println("=".repeat(80));
        System.out.println();
        
        try {
            // Configurar modo lenient
            ValidationMode.setMode(ValidationMode.Mode.LENIENT);
            
            // Cargar datos
            System.out.println(">>> CARGANDO DATOS <<<");
            
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
            
            // Cargar SOLO 3 archivos de envíos para prueba rápida
            ShipmentUploader shipmentUploader = new ShipmentUploader();
            List<ShipmentBatch> allBatches = new ArrayList<>();
            int filesLoaded = 0;
            if (enviosDir.exists() && enviosDir.isDirectory()) {
                File[] files = enviosDir.listFiles((dir, name) -> name.endsWith(".txt"));
                if (files != null) {
                    for (File file : files) {
                        if (filesLoaded >= 3) break;  // Solo 3 archivos
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
            System.out.println();
            
            if (allBatches.isEmpty()) {
                System.err.println("❌ No hay lotes");
                return;
            }
            
            // Crear cola
            ShipmentQueue queue = new ShipmentQueue();
            for (ShipmentBatch batch : allBatches) {
                queue.addShipment(batch);
            }
            
            // Parámetros K=1
            int Ta = 1;
            int Sa = 5;
            int K = 1;
            
            System.out.println("Parámetros: Ta=" + Ta + " min, Sa=" + Sa + " min, K=" + K);
            System.out.println();
            
            // Crear Scheduler con Tabu Puro
            System.out.println("=".repeat(80));
            System.out.println(">>> EJECUTANDO TABU SEARCH PURO <<<");
            System.out.println("=".repeat(80));
            
            SolutionEvaluator evaluator = new SolutionEvaluator(flightPlan, airportManager);
            RouteValidator validator = new RouteValidator(airportManager);
            
            Scheduler scheduler = SchedulerFactory.createTabuScheduler(
                flightPlan, airportManager, queue, evaluator, validator, Ta, Sa, K
            );
            
            // Ejecutar (limitar a 10 ciclos)
            ZonedDateTime startTime = allBatches.get(0).ingressTime();
            long startMs = System.currentTimeMillis();
            Solution solution = scheduler.run(startTime, 10);
            long totalMs = System.currentTimeMillis() - startMs;
            
            // Resultados
            System.out.println("\n" + "=".repeat(80));
            System.out.println("RESULTADOS");
            System.out.println("=".repeat(80));
            System.out.println("Tiempo total: " + (totalMs / 1000.0) + " segundos");
            System.out.println("Fitness final: " + String.format("%.2f", solution.getFitness()));
            System.out.println("Rutas generadas: " + solution.getRoutes().size());
            
            // Validación
            RouteValidator finalValidator = new RouteValidator(airportManager);
            var report = finalValidator.validate(solution);
            System.out.println("Violaciones: " + report.getViolations().size());
            
            if (!report.isValid()) {
                System.out.println("\nPrimeras 5 violaciones:");
                report.getViolations().stream().limit(5).forEach(v -> 
                    System.out.println("  - " + v.type() + ": " + v.message())
                );
            }
            
            System.out.println("=".repeat(80));
            
        } catch (Exception e) {
            System.err.println("❌ Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
