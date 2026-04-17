package com.equipo2b.scheduler;

import com.equipo2b.scheduler.execution.SimulationRunner;
import com.equipo2b.scheduler.execution.SimulationRunner.SimulationResult;
import com.equipo2b.scheduler.logic.*;
import com.equipo2b.scheduler.model.*;
import com.equipo2b.scheduler.upload.*;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Pruebas de simulación con datos reales.
 * 
 * Tres escenarios según especificaciones:
 * 1. K=1: Día a día (planificación diaria)
 * 2. K=14-23: Período (2-3 semanas)
 * 3. K=75: Colapso (2.5 meses)
 */
public class SimulationTest {
    
    public static void main(String[] args) {
        try {
            // Activar modo LENIENT para datos reales
            ValidationMode.setMode(ValidationMode.Mode.LENIENT);
            
            System.out.println("=".repeat(80));
            System.out.println("PRUEBAS DE SIMULACIÓN - SISTEMA DE PLANIFICACIÓN LOGÍSTICA");
            System.out.println("=".repeat(80));
            System.out.println();
            
            // Cargar datos
            System.out.println("Cargando datos del sistema...");
            DataLoader loader = loadSystemData();
            System.out.println("✓ Datos cargados exitosamente");
            System.out.println("  - Aeropuertos: " + loader.airportManager.getAllAirports().size());
            System.out.println("  - Vuelos: " + loader.flightPlan.getTotalFlights());
            System.out.println("  - Lotes disponibles: " + loader.allBatches.size());
            System.out.println();
            
            // Menú de selección
            System.out.println("Seleccione el escenario de simulación:");
            System.out.println("1. K=1 (Día a día - 100 lotes)");
            System.out.println("2. K=14 (Período 2 semanas - 500 lotes)");
            System.out.println("3. K=75 (Colapso 2.5 meses - 2000 lotes)");
            System.out.println("4. Ejecutar todos los escenarios");
            System.out.println();
            
            // Por defecto, ejecutar escenario 1 (día a día)
            String scenario = args.length > 0 ? args[0] : "1";
            
            switch (scenario) {
                case "1":
                    runScenario1_DayToDay(loader);
                    break;
                case "2":
                    runScenario2_Period(loader);
                    break;
                case "3":
                    runScenario3_Collapse(loader);
                    break;
                case "4":
                    runScenario1_DayToDay(loader);
                    System.out.println("\n\n");
                    runScenario2_Period(loader);
                    System.out.println("\n\n");
                    runScenario3_Collapse(loader);
                    break;
                default:
                    System.out.println("Escenario inválido. Ejecutando escenario 1 por defecto.");
                    runScenario1_DayToDay(loader);
            }
            
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Escenario 1: K=1 (Día a día)
     * - 100 lotes
     * - Planificación diaria
     * - Replanificación según necesidad
     */
    private static void runScenario1_DayToDay(DataLoader loader) {
        System.out.println("=".repeat(80));
        System.out.println("ESCENARIO 1: K=1 (DÍA A DÍA)");
        System.out.println("=".repeat(80));
        System.out.println();
        
        // Seleccionar primeros 100 lotes
        List<ShipmentBatch> batches = loader.allBatches.subList(0, Math.min(100, loader.allBatches.size()));
        
        // Configurar simulador
        SimulationRunner simulator = new SimulationRunner(loader.flightPlan, loader.airportManager);
        simulator.configureGA(20, 15, 0.1);  // Configuración rápida para K=1
        simulator.configureTabu(30, 10, 15);
        
        // Ejecutar simulación de 1 día
        ZonedDateTime startDate = batches.get(0).ingressTime();
        SimulationResult result = simulator.runSimulation(batches, 1, startDate);
        
        // Mostrar resultados
        result.printSummary();
        printDetailedMetrics(result, loader);
    }
    
    /**
     * Escenario 2: K=14-23 (Período)
     * - 500 lotes
     * - Simulación de 2 semanas
     * - Múltiples replanificaciones
     */
    private static void runScenario2_Period(DataLoader loader) {
        System.out.println("=".repeat(80));
        System.out.println("ESCENARIO 2: K=14 (PERÍODO - 2 SEMANAS)");
        System.out.println("=".repeat(80));
        System.out.println();
        
        // Seleccionar primeros 500 lotes
        List<ShipmentBatch> batches = loader.allBatches.subList(0, Math.min(500, loader.allBatches.size()));
        
        // Configurar simulador
        SimulationRunner simulator = new SimulationRunner(loader.flightPlan, loader.airportManager);
        simulator.configureGA(30, 20, 0.1);  // Configuración balanceada
        simulator.configureTabu(50, 15, 20);
        
        // Ejecutar simulación de 14 días
        ZonedDateTime startDate = batches.get(0).ingressTime();
        SimulationResult result = simulator.runSimulation(batches, 14, startDate);
        
        // Mostrar resultados
        result.printSummary();
        printDetailedMetrics(result, loader);
    }
    
    /**
     * Escenario 3: K=75 (Colapso)
     * - 2000 lotes
     * - Simulación de 2.5 meses
     * - Estrés del sistema
     */
    private static void runScenario3_Collapse(DataLoader loader) {
        System.out.println("=".repeat(80));
        System.out.println("ESCENARIO 3: K=75 (COLAPSO - 2.5 MESES)");
        System.out.println("=".repeat(80));
        System.out.println();
        
        // Seleccionar primeros 2000 lotes
        List<ShipmentBatch> batches = loader.allBatches.subList(0, Math.min(2000, loader.allBatches.size()));
        
        // Configurar simulador
        SimulationRunner simulator = new SimulationRunner(loader.flightPlan, loader.airportManager);
        simulator.configureGA(40, 25, 0.15);  // Configuración intensiva
        simulator.configureTabu(100, 20, 30);
        
        // Ejecutar simulación de 75 días
        ZonedDateTime startDate = batches.get(0).ingressTime();
        SimulationResult result = simulator.runSimulation(batches, 75, startDate);
        
        // Mostrar resultados
        result.printSummary();
        printDetailedMetrics(result, loader);
    }
    
    /**
     * Imprime métricas detalladas de la simulación.
     */
    private static void printDetailedMetrics(SimulationResult result, DataLoader loader) {
        System.out.println("\n📈 MÉTRICAS DETALLADAS");
        System.out.println("-".repeat(80));
        
        SolutionEvaluator evaluator = new SolutionEvaluator(loader.flightPlan, loader.airportManager);
        Solution solution = result.finalSolution;
        
        // Componentes del fitness
        double flightCapPenalty = evaluator.calculateFlightCapacityPenalties(solution);
        double storagePenalty = evaluator.calculateStorageCapacityPenalties(solution);
        double slaPenalty = evaluator.calculateSLAPenalties(solution);
        double layoverPenalty = evaluator.calculateLayoverPenalties(solution);
        double slackReward = evaluator.calculateTimeSlackRewards(solution);
        double unusedFlightReward = evaluator.calculateUnusedFlightRewards(solution);
        
        System.out.println("Penalizaciones:");
        System.out.println("  - Capacidad vuelos: " + String.format("%.2f", flightCapPenalty));
        System.out.println("  - Capacidad almacén: " + String.format("%.2f", storagePenalty));
        System.out.println("  - Violaciones SLA: " + String.format("%.2f", slaPenalty));
        System.out.println("  - Violaciones escala: " + String.format("%.2f", layoverPenalty));
        System.out.println("  TOTAL: " + String.format("%.2f", 
            flightCapPenalty + storagePenalty + slaPenalty + layoverPenalty));
        System.out.println();
        
        System.out.println("Premios:");
        System.out.println("  - Holgura tiempo: " + String.format("%.2f", slackReward));
        System.out.println("  - Vuelos no usados: " + String.format("%.2f", unusedFlightReward));
        System.out.println("  TOTAL: " + String.format("%.2f", slackReward + unusedFlightReward));
        System.out.println();
        
        // Estadísticas de rutas
        int totalRoutes = solution.getRoutes().size();
        int directRoutes = 0;
        int oneHopRoutes = 0;
        int twoHopRoutes = 0;
        int threeHopRoutes = 0;
        
        for (AssignedRoute route : solution.getRoutes().values()) {
            int hops = route.getFlights().size();
            switch (hops) {
                case 1: directRoutes++; break;
                case 2: oneHopRoutes++; break;
                case 3: twoHopRoutes++; break;
                default: threeHopRoutes++; break;
            }
        }
        
        System.out.println("Distribución de rutas:");
        System.out.println("  - Directas (1 vuelo): " + directRoutes + " (" + 
            String.format("%.1f%%", directRoutes * 100.0 / totalRoutes) + ")");
        System.out.println("  - 1 escala (2 vuelos): " + oneHopRoutes + " (" + 
            String.format("%.1f%%", oneHopRoutes * 100.0 / totalRoutes) + ")");
        System.out.println("  - 2 escalas (3 vuelos): " + twoHopRoutes + " (" + 
            String.format("%.1f%%", twoHopRoutes * 100.0 / totalRoutes) + ")");
        if (threeHopRoutes > 0) {
            System.out.println("  - 3+ escalas: " + threeHopRoutes + " (" + 
                String.format("%.1f%%", threeHopRoutes * 100.0 / totalRoutes) + ")");
        }
        System.out.println();
    }
    
    /**
     * Carga todos los datos del sistema.
     */
    private static DataLoader loadSystemData() throws Exception {
        DataLoader loader = new DataLoader();
        
        // Cargar aeropuertos
        AirportUploader airportUploader = new AirportUploader();
        String airportFile = "data/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt";
        List<Airport> airports = airportUploader.loadAirports(airportFile);
        
        loader.airportManager = new AirportManager();
        for (Airport airport : airports) {
            loader.airportManager.addAirport(airport);
        }
        
        // Cargar plan de vuelos
        FlightPlanUploader flightUploader = new FlightPlanUploader();
        String flightFile = "data/planes_vuelo.txt";
        loader.flightPlan = flightUploader.loadFlights(flightFile, loader.airportManager);
        
        // Cargar envíos de múltiples aeropuertos
        ClientRegistry clientRegistry = new ClientRegistry();
        ShipmentUploader shipmentUploader = new ShipmentUploader();
        
        String[] shipmentFiles = {
            "data/_envios_preliminar_/_envios_SKBO_.txt",
            "data/_envios_preliminar_/_envios_EDDI_.txt",
            "data/_envios_preliminar_/_envios_SCEL_.txt"
        };
        
        // Registrar clientes
        java.util.Set<String> clientIds = new java.util.HashSet<>();
        for (String file : shipmentFiles) {
            try {
                java.nio.file.Path path = java.nio.file.Paths.get(file);
                java.util.stream.Stream<String> lines = java.nio.file.Files.lines(path);
                lines.forEach(line -> {
                    if (!line.isBlank()) {
                        String[] parts = line.split("-");
                        if (parts.length >= 7) {
                            clientIds.add(parts[6].trim());
                        }
                    }
                });
                lines.close();
            } catch (Exception e) {
                // Ignorar errores
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
        
        // Cargar lotes
        loader.allBatches = new java.util.ArrayList<>();
        for (String file : shipmentFiles) {
            try {
                List<ShipmentBatch> fileBatches = shipmentUploader.loadShipments(
                    file, loader.airportManager, clientRegistry);
                loader.allBatches.addAll(fileBatches);
            } catch (Exception e) {
                System.err.println("⚠ No se pudo cargar " + file + ": " + e.getMessage());
            }
        }
        
        return loader;
    }
    
    /**
     * Contenedor de datos cargados.
     */
    private static class DataLoader {
        AirportManager airportManager;
        FlightPlan flightPlan;
        List<ShipmentBatch> allBatches;
    }
}
