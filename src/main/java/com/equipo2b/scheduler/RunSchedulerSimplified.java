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
 * Simulación REAL con Scheduler - Versión Simplificada
 * 
 * Distribuye los lotes uniformemente en el tiempo de simulación
 * para demostrar el funcionamiento de los ciclos Sa y ventanas Sc.
 */
public class RunSchedulerSimplified {
    
    public static void main(String[] args) {
        try {
            ValidationMode.setMode(ValidationMode.Mode.LENIENT);
            
            System.out.println("=".repeat(100));
            System.out.println("SIMULACIÓN REAL CON SCHEDULER - VERSIÓN SIMPLIFICADA");
            System.out.println("=".repeat(100));
            System.out.println();
            
            // Cargar datos
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
            List<ShipmentBatch> originalBatches = new ArrayList<>();
            
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
            
            // Cargar lotes (limitar a 200)
            for (String file : files) {
                List<ShipmentBatch> fileBatches = shipmentUploader.loadShipments(
                    file, airportManager, clientRegistry);
                originalBatches.addAll(fileBatches);
            }
            
            if (originalBatches.size() > 200) {
                originalBatches = originalBatches.subList(0, 200);
            }
            
            System.out.println("✓ Clientes: " + clientIds.size());
            System.out.println("✓ Lotes cargados: " + originalBatches.size());
            System.out.println();
            
            // ==================== ESCENARIO K=1 ====================
            System.out.println("=".repeat(100));
            System.out.println("ESCENARIO K=1 (OPERACIÓN DÍA A DÍA)");
            System.out.println("=".repeat(100));
            System.out.println();
            
            runScenario(originalBatches, flightPlan, airportManager, 1, "K1");
            
            // ==================== ESCENARIO K=14 ====================
            System.out.println("\n\n");
            System.out.println("=".repeat(100));
            System.out.println("ESCENARIO K=14 (PERIODO 2 SEMANAS)");
            System.out.println("=".repeat(100));
            System.out.println();
            
            runScenario(originalBatches, flightPlan, airportManager, 14, "K14");
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private static void runScenario(
            List<ShipmentBatch> originalBatches,
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
        
        // Distribuir lotes en el tiempo de simulación
        ZonedDateTime startTime = ZonedDateTime.now();
        int totalMinutes = 60; // Simular 1 hora
        List<ShipmentBatch> adjustedBatches = distributeInTime(
            originalBatches, startTime, totalMinutes);
        
        System.out.println("DISTRIBUCIÓN TEMPORAL:");
        System.out.println("  Lotes distribuidos en " + totalMinutes + " minutos");
        System.out.println("  Lotes por minuto: ~" + (adjustedBatches.size() / totalMinutes));
        System.out.println("  Lotes por ventana Sc (" + Sc + " min): ~" + 
            (adjustedBatches.size() * Sc / totalMinutes));
        System.out.println();
        
        // Crear ShipmentQueue y agregar lotes
        ShipmentQueue queue = new ShipmentQueue();
        for (ShipmentBatch batch : adjustedBatches) {
            queue.addShipment(batch);
        }
        
        System.out.println("✓ Lotes en cola: " + queue.getPendingCount());
        System.out.println();
        
        // Configurar algoritmos (muy reducidos para cumplir Ta)
        GeneticAlgorithm ga = new GeneticAlgorithm(flightPlan, airportManager);
        AlgorithmConfig gaConfig = new AlgorithmConfig();
        gaConfig.setInt("populationSize", 10);
        gaConfig.setInt("generations", 5);
        gaConfig.setDouble("mutationRate", 0.1);
        gaConfig.setInt("tournamentSize", 3);
        gaConfig.setInt("eliteCount", 2);
        ga.configure(gaConfig);
        
        TabuSearch tabu = new TabuSearch(flightPlan, airportManager);
        AlgorithmConfig tabuConfig = new AlgorithmConfig();
        tabuConfig.setInt("maxIterations", 15);
        tabuConfig.setInt("tabuTenure", 7);
        tabuConfig.setInt("neighborhoodSize", 8);
        tabu.configure(tabuConfig);
        
        // Crear Scheduler con GATS
        Scheduler scheduler = SchedulerFactory.createGATSScheduler(
            flightPlan, airportManager, queue,
            new SolutionEvaluator(flightPlan, airportManager),
            new RouteValidator(airportManager),
            Ta, Sa, K
        );
        
        // Ejecutar simulación
        int maxCycles = 8;  // Limitar ciclos
        
        System.out.println("INICIANDO SIMULACIÓN (máximo " + maxCycles + " ciclos)...");
        System.out.println("Cada ciclo consume lotes de ventana Sc = " + Sc + " minutos");
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
        System.out.println();
        
        // Análisis de ciclos
        System.out.println("ANÁLISIS:");
        System.out.println("  - Con K=" + K + ", cada ciclo consume ventana de " + Sc + " minutos");
        System.out.println("  - Ciclos cada " + Sa + " minutos");
        if (K == 1) {
            System.out.println("  - K=1: Ventana pequeña (5 min) = Pocos lotes por ciclo");
            System.out.println("  - Operación día a día, planificación incremental");
        } else {
            System.out.println("  - K=" + K + ": Ventana grande (" + Sc + " min) = Más lotes por ciclo");
            System.out.println("  - Planificación con visión de " + Sc + " minutos hacia adelante");
        }
        System.out.println("=".repeat(100));
    }
    
    /**
     * Distribuye lotes uniformemente en el tiempo de simulación.
     * Ajusta el ingressTime de cada lote para que caiga en la ventana temporal.
     */
    private static List<ShipmentBatch> distributeInTime(
            List<ShipmentBatch> originalBatches,
            ZonedDateTime startTime,
            int totalMinutes) {
        
        List<ShipmentBatch> adjusted = new ArrayList<>();
        int minutesPerBatch = Math.max(1, totalMinutes / originalBatches.size());
        
        for (int i = 0; i < originalBatches.size(); i++) {
            ShipmentBatch original = originalBatches.get(i);
            
            // Calcular nuevo ingressTime distribuido uniformemente
            ZonedDateTime newIngressTime = startTime.plusMinutes(i * minutesPerBatch);
            
            // Crear nuevo batch con timestamp ajustado
            ShipmentBatch adjustedBatch = new ShipmentBatch(
                original.batchId(),
                original.airportBatchId(),
                original.clientId(),
                original.origin(),
                original.destination(),
                original.quantity(),
                newIngressTime  // Timestamp ajustado
            );
            
            adjusted.add(adjustedBatch);
        }
        
        return adjusted;
    }
}
