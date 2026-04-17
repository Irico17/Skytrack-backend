package com.equipo2b.scheduler;

import com.equipo2b.scheduler.algorithm.*;
import com.equipo2b.scheduler.execution.*;
import com.equipo2b.scheduler.logic.*;
import com.equipo2b.scheduler.model.*;
import com.equipo2b.scheduler.upload.*;
import com.equipo2b.scheduler.validation.*;

import java.time.ZonedDateTime;
import java.util.*;

/**
 * Ejecuta simulación REAL usando Scheduler con ciclos Sa y ventanas Sc.
 * 
 * Esto simula el comportamiento real del sistema:
 * - Cada Sa minutos ejecuta un ciclo de planificación
 * - Cada ciclo consume lotes de una ventana Sc = Sa × K
 * - El algoritmo debe completar en tiempo Ta
 */
public class RunRealSchedulerSimulation {
    
    public static void main(String[] args) {
        try {
            // CRÍTICO: Activar modo LENIENT
            ValidationMode.setMode(ValidationMode.Mode.LENIENT);
            
            System.out.println("=".repeat(100));
            System.out.println("SIMULACIÓN REAL CON SCHEDULER");
            System.out.println("=".repeat(100));
            System.out.println();
            
            // ==================== CARGAR DATOS ====================
            System.out.println("CARGANDO DATOS...");
            
            AirportManager airportManager = new AirportManager();
            AirportUploader airportUploader = new AirportUploader();
            List<Airport> airports = airportUploader.loadAirports(
                "data/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt");
            for (Airport airport : airports) {
                airportManager.addAirport(airport);
            }
            
            FlightPlan flightPlan = new FlightPlan();
            FlightPlanUploader flightUploader = new FlightPlanUploader();
            flightPlan = flightUploader.loadFlights("data/planes_vuelo.txt", airportManager);
            
            System.out.println("✓ Aeropuertos: " + airports.size());
            System.out.println("✓ Vuelos: " + flightPlan.getTotalFlights());
            
            // Cargar envíos
            ClientRegistry clientRegistry = new ClientRegistry();
            ShipmentUploader shipmentUploader = new ShipmentUploader();
            List<ShipmentBatch> allBatches = new ArrayList<>();
            
            String[] files = {
                "data/_envios_preliminar_/_envios_SKBO_.txt",
                "data/_envios_preliminar_/_envios_EDDI_.txt",
                "data/_envios_preliminar_/_envios_SCEL_.txt"
            };
            
            // Registrar clientes
            Set<String> clientIds = new HashSet<>();
            for (String file : files) {
                try (java.util.stream.Stream<String> lines = java.nio.file.Files.lines(java.nio.file.Paths.get(file))) {
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
                    clientId, "Cliente " + clientId, 
                    clientId + "@example.com", "+1234567890"
                ));
            }
            
            // Cargar lotes (limitar a 300 para demo rápida)
            for (String file : files) {
                List<ShipmentBatch> fileBatches = shipmentUploader.loadShipments(
                    file, airportManager, clientRegistry);
                allBatches.addAll(fileBatches);
            }
            
            if (allBatches.size() > 300) {
                allBatches = allBatches.subList(0, 300);
            }
            
            System.out.println("✓ Clientes: " + clientIds.size());
            System.out.println("✓ Lotes totales: " + allBatches.size());
            System.out.println();
            
            // ==================== ESCENARIO K=1 ====================
            System.out.println("=".repeat(100));
            System.out.println("ESCENARIO K=1 (OPERACIÓN DÍA A DÍA)");
            System.out.println("=".repeat(100));
            System.out.println();
            
            runScenario(allBatches, flightPlan, airportManager, 1, "K1");
            
            // ==================== ESCENARIO K=14 ====================
            System.out.println("\n\n");
            System.out.println("=".repeat(100));
            System.out.println("ESCENARIO K=14 (PERIODO 2 SEMANAS)");
            System.out.println("=".repeat(100));
            System.out.println();
            
            runScenario(allBatches, flightPlan, airportManager, 14, "K14");
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private static void runScenario(
            List<ShipmentBatch> allBatches,
            FlightPlan flightPlan,
            AirportManager airportManager,
            int K,
            String scenarioName) {
        
        // Parámetros
        int Ta = 1;  // 1 minuto
        int Sa = 5;  // 5 minutos
        int Sc = Sa * K;
        
        System.out.println("CONFIGURACIÓN:");
        System.out.println("  K = " + K);
        System.out.println("  Ta = " + Ta + " minuto (tiempo máximo algoritmo)");
        System.out.println("  Sa = " + Sa + " minutos (salto entre ejecuciones)");
        System.out.println("  Sc = Sa × K = " + Sc + " minutos (ventana de consumo)");
        System.out.println();
        System.out.println("COMPORTAMIENTO:");
        System.out.println("  - Cada " + Sa + " minutos ejecuta un ciclo");
        System.out.println("  - Cada ciclo consume lotes de ventana de " + Sc + " minutos");
        System.out.println("  - El algoritmo debe completar en ≤ " + Ta + " minuto");
        System.out.println();
        
        // Crear ShipmentQueue y agregar lotes
        ShipmentQueue queue = new ShipmentQueue();
        for (ShipmentBatch batch : allBatches) {
            queue.addShipment(batch);
        }
        
        System.out.println("✓ Lotes en cola: " + queue.getPendingCount());
        System.out.println();
        
        // Configurar algoritmos (reducidos para cumplir Ta)
        GeneticAlgorithm ga = new GeneticAlgorithm(flightPlan, airportManager);
        AlgorithmConfig gaConfig = new AlgorithmConfig();
        gaConfig.setInt("populationSize", 15);  // Reducido
        gaConfig.setInt("generations", 8);      // Reducido
        gaConfig.setDouble("mutationRate", 0.1);
        gaConfig.setInt("tournamentSize", 3);
        gaConfig.setInt("eliteCount", 2);
        ga.configure(gaConfig);
        
        TabuSearch tabu = new TabuSearch(flightPlan, airportManager);
        AlgorithmConfig tabuConfig = new AlgorithmConfig();
        tabuConfig.setInt("maxIterations", 20);  // Reducido
        tabuConfig.setInt("tabuTenure", 8);
        tabuConfig.setInt("neighborhoodSize", 10);
        tabu.configure(tabuConfig);
        
        // Crear Scheduler
        Scheduler scheduler = new Scheduler(
            ga, tabu, queue,
            new SolutionEvaluator(flightPlan, airportManager),
            new RouteValidator(airportManager),
            Ta, Sa, K
        );
        
        // Ejecutar simulación (limitar a 10 ciclos para demo)
        ZonedDateTime startTime = ZonedDateTime.now();
        int maxCycles = 10;
        
        System.out.println("INICIANDO SIMULACIÓN (máximo " + maxCycles + " ciclos)...");
        System.out.println();
        
        long simStart = System.currentTimeMillis();
        Solution finalSolution = scheduler.run(startTime, maxCycles);
        long simTime = System.currentTimeMillis() - simStart;
        
        // Resultados
        System.out.println("\n");
        System.out.println("=".repeat(100));
        System.out.println("RESULTADOS ESCENARIO " + scenarioName);
        System.out.println("=".repeat(100));
        System.out.println("Tiempo total simulación: " + simTime + " ms");
        System.out.println("Fitness final: " + String.format("%.2f", finalSolution.getFitness()));
        System.out.println("Rutas planificadas: " + finalSolution.getRoutes().size());
        
        // Calcular SLA compliance
        int slaOk = 0;
        for (AssignedRoute route : finalSolution.getRoutes().values()) {
            if (route.meetsSLA()) slaOk++;
        }
        
        double slaCompliance = finalSolution.getRoutes().size() > 0 
            ? (slaOk * 100.0 / finalSolution.getRoutes().size()) 
            : 0;
        
        System.out.println("Cumplimiento SLA: " + String.format("%.1f%%", slaCompliance) + 
            " (" + slaOk + "/" + finalSolution.getRoutes().size() + ")");
        System.out.println("Lotes restantes en cola: " + queue.getPendingCount());
        System.out.println("=".repeat(100));
    }
}
