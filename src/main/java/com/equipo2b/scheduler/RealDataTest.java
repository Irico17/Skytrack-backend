package com.equipo2b.scheduler;

import com.equipo2b.scheduler.algorithm.*;
import com.equipo2b.scheduler.logic.*;
import com.equipo2b.scheduler.model.*;
import com.equipo2b.scheduler.upload.*;

import java.time.ZonedDateTime;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Prueba con datos reales del sistema de planificación logística.
 * 
 * Carga:
 * - Aeropuertos desde archivo de datos
 * - Plan de vuelos desde archivo de datos
 * - Envíos desde archivos de datos (selecciona algunos aeropuertos)
 * 
 * Ejecuta:
 * - Algoritmo Genético para generar solución inicial
 * - Búsqueda Tabú para refinar la solución
 * 
 * Muestra:
 * - Estadísticas de la solución
 * - Fitness y componentes
 * - Rutas generadas
 */
public class RealDataTest {
    
    public static void main(String[] args) {
        try {
            // IMPORTANTE: Activar modo LENIENT para pruebas con datos reales
            ValidationMode.setMode(ValidationMode.Mode.LENIENT);
            
            System.out.println("=".repeat(80));
            System.out.println("PRUEBA CON DATOS REALES - SISTEMA DE PLANIFICACIÓN LOGÍSTICA");
            System.out.println("Modo de validación: " + ValidationMode.getMode());
            System.out.println("=".repeat(80));
            System.out.println();
            
            // ==================== PASO 1: Cargar Aeropuertos ====================
            System.out.println("PASO 1: Cargando aeropuertos...");
            AirportManager airportManager = new AirportManager();
            AirportUploader airportUploader = new AirportUploader();
            
            String airportFile = "data/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt";
            List<Airport> airports = airportUploader.loadAirports(airportFile);
            
            // Agregar aeropuertos al manager
            for (Airport airport : airports) {
                airportManager.addAirport(airport);
            }
            
            System.out.println("✓ Aeropuertos cargados: " + airports.size());
            System.out.println();
            
            // ==================== PASO 2: Cargar Plan de Vuelos ====================
            System.out.println("PASO 2: Cargando plan de vuelos...");
            FlightPlan flightPlan = new FlightPlan();
            FlightPlanUploader flightUploader = new FlightPlanUploader();
            
            String flightFile = "data/planes_vuelo.txt";
            flightPlan = flightUploader.loadFlights(flightFile, airportManager);
            System.out.println("✓ Vuelos cargados: " + flightPlan.getTotalFlights());
            System.out.println();
            
            // ==================== PASO 3: Cargar Envíos ====================
            System.out.println("PASO 3: Cargando envíos de muestra...");
            
            // Crear registro de clientes vacío (se llenará automáticamente)
            ClientRegistry clientRegistry = new ClientRegistry();
            
            ShipmentUploader shipmentUploader = new ShipmentUploader();
            
            // Cargar envíos de 3 aeropuertos diferentes para tener variedad
            List<ShipmentBatch> batches = new java.util.ArrayList<>();
            
            String[] shipmentFiles = {
                "data/_envios_preliminar_/_envios_SKBO_.txt",  // Bogotá (América del Sur)
                "data/_envios_preliminar_/_envios_EDDI_.txt",  // Berlín (Europa)
                "data/_envios_preliminar_/_envios_SCEL_.txt"   // Santiago (América del Sur)
            };
            
            // Primero, extraer todos los IDs de cliente únicos y registrarlos
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
                    // Ignorar errores en esta fase
                }
            }
            
            // Registrar todos los clientes encontrados
            for (String clientId : clientIds) {
                clientRegistry.addClient(new AirlineClient(
                    clientId, 
                    "Cliente " + clientId, 
                    clientId + "@example.com",
                    "+1234567890"
                ));
            }
            System.out.println("  ✓ Clientes registrados: " + clientIds.size());
            
            // Ahora cargar los envíos
            for (String file : shipmentFiles) {
                try {
                    List<ShipmentBatch> fileBatches = shipmentUploader.loadShipments(file, airportManager, clientRegistry);
                    batches.addAll(fileBatches);
                    System.out.println("  ✓ Cargados " + fileBatches.size() + " lotes desde " + file);
                } catch (Exception e) {
                    System.out.println("  ⚠ No se pudo cargar " + file + ": " + e.getMessage());
                }
            }
            
            // Limitar a 50 lotes para una prueba más representativa
            if (batches.size() > 50) {
                batches = batches.subList(0, 50);
                System.out.println("  → Limitando a 50 lotes para prueba");
            }
            
            System.out.println("✓ Total de lotes a procesar: " + batches.size());
            System.out.println();
            
            if (batches.isEmpty()) {
                System.out.println("⚠ No se cargaron lotes. Verifica los archivos de datos.");
                return;
            }
            
            // ==================== PASO 4: Ejecutar Algoritmo Genético ====================
            System.out.println("PASO 4: Ejecutando Algoritmo Genético...");
            System.out.println("-".repeat(80));
            
            GeneticAlgorithm ga = new GeneticAlgorithm(flightPlan, airportManager);
            AlgorithmConfig gaConfig = new AlgorithmConfig();
            gaConfig.setInt("populationSize", 30);   // Población más grande
            gaConfig.setInt("generations", 20);       // Más generaciones
            gaConfig.setDouble("mutationRate", 0.1);
            gaConfig.setInt("tournamentSize", 4);
            gaConfig.setInt("eliteCount", 3);         // Más élites
            ga.configure(gaConfig);
            
            long startTime = System.currentTimeMillis();
            Solution gaSolution = ga.optimize(batches);
            long gaTime = System.currentTimeMillis() - startTime;
            
            System.out.println("-".repeat(80));
            System.out.println("✓ Algoritmo Genético completado en " + gaTime + " ms");
            System.out.println();
            
            // ==================== PASO 5: Refinar con Búsqueda Tabú ====================
            System.out.println("PASO 5: Refinando solución con Búsqueda Tabú...");
            System.out.println("-".repeat(80));
            
            TabuSearch tabu = new TabuSearch(flightPlan, airportManager);
            AlgorithmConfig tabuConfig = new AlgorithmConfig();
            tabuConfig.setInt("maxIterations", 50);      // Más iteraciones
            tabuConfig.setInt("tabuTenure", 15);
            tabuConfig.setInt("neighborhoodSize", 20);
            tabu.configure(tabuConfig);
            
            startTime = System.currentTimeMillis();
            Solution refinedSolution = tabu.refine(gaSolution);
            long tabuTime = System.currentTimeMillis() - startTime;
            
            System.out.println("-".repeat(80));
            System.out.println("✓ Búsqueda Tabú completada en " + tabuTime + " ms");
            System.out.println();
            
            // ==================== PASO 6: Mostrar Resultados ====================
            System.out.println("=".repeat(80));
            System.out.println("RESULTADOS FINALES");
            System.out.println("=".repeat(80));
            System.out.println();
            
            printSolutionStatistics(refinedSolution, flightPlan, airportManager);
            
            System.out.println();
            System.out.println("=".repeat(80));
            System.out.println("PRUEBA COMPLETADA EXITOSAMENTE");
            System.out.println("=".repeat(80));
            
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Imprime estadísticas detalladas de la solución.
     */
    private static void printSolutionStatistics(Solution solution, FlightPlan flightPlan, 
                                               AirportManager airportManager) {
        SolutionEvaluator evaluator = new SolutionEvaluator(flightPlan, airportManager);
        
        System.out.println("📊 ESTADÍSTICAS DE LA SOLUCIÓN");
        System.out.println("-".repeat(80));
        System.out.println();
        
        // Fitness total
        double fitness = solution.getFitness();
        System.out.println("Fitness Total: " + String.format("%.2f", fitness) + " (menor es mejor)");
        System.out.println();
        
        // Componentes del fitness
        System.out.println("Componentes del Fitness:");
        System.out.println("  Penalizaciones:");
        
        double flightCapPenalty = evaluator.calculateFlightCapacityPenalties(solution);
        System.out.println("    - Capacidad de vuelos excedida: " + 
                          String.format("%.2f", flightCapPenalty));
        
        double storagePenalty = evaluator.calculateStorageCapacityPenalties(solution);
        System.out.println("    - Capacidad de almacén excedida: " + 
                          String.format("%.2f", storagePenalty));
        
        double slaPenalty = evaluator.calculateSLAPenalties(solution);
        System.out.println("    - Violaciones de SLA: " + 
                          String.format("%.2f", slaPenalty));
        
        double layoverPenalty = evaluator.calculateLayoverPenalties(solution);
        System.out.println("    - Violaciones de tiempo de escala: " + 
                          String.format("%.2f", layoverPenalty));
        
        double totalPenalties = flightCapPenalty + storagePenalty + slaPenalty + layoverPenalty;
        System.out.println("    TOTAL PENALIZACIONES: " + String.format("%.2f", totalPenalties));
        System.out.println();
        
        System.out.println("  Premios:");
        
        double slackReward = evaluator.calculateTimeSlackRewards(solution);
        System.out.println("    - Holgura de tiempo: " + 
                          String.format("%.2f", slackReward));
        
        double unusedFlightReward = evaluator.calculateUnusedFlightRewards(solution);
        System.out.println("    - Vuelos no utilizados: " + 
                          String.format("%.2f", unusedFlightReward));
        
        double totalRewards = slackReward + unusedFlightReward;
        System.out.println("    TOTAL PREMIOS: " + String.format("%.2f", totalRewards));
        System.out.println();
        
        // Estadísticas de rutas
        Map<String, AssignedRoute> routes = solution.getRoutes();
        System.out.println("📦 ESTADÍSTICAS DE RUTAS");
        System.out.println("-".repeat(80));
        System.out.println("Total de rutas: " + routes.size());
        
        int routesMeetingSLA = 0;
        int totalFlightsUsed = 0;
        int totalBags = 0;
        Duration totalSlack = Duration.ZERO;
        
        for (AssignedRoute route : routes.values()) {
            if (route.meetsSLA()) {
                routesMeetingSLA++;
                totalSlack = totalSlack.plus(route.getSLASlack());
            }
            totalFlightsUsed += route.getFlights().size();
            totalBags += route.getBatch().quantity();
        }
        
        System.out.println("Rutas que cumplen SLA: " + routesMeetingSLA + " / " + routes.size() + 
                          " (" + String.format("%.1f", (routesMeetingSLA * 100.0 / routes.size())) + "%)");
        System.out.println("Total de vuelos utilizados: " + totalFlightsUsed);
        System.out.println("Total de maletas transportadas: " + totalBags);
        
        if (routesMeetingSLA > 0) {
            long avgSlackHours = totalSlack.toHours() / routesMeetingSLA;
            System.out.println("Holgura promedio (rutas con SLA): " + avgSlackHours + " horas");
        }
        
        System.out.println();
        
        // Detalles de algunas rutas
        System.out.println("📋 MUESTRA DE RUTAS (primeras 5)");
        System.out.println("-".repeat(80));
        
        int count = 0;
        for (Map.Entry<String, AssignedRoute> entry : routes.entrySet()) {
            if (count >= 5) break;
            
            AssignedRoute route = entry.getValue();
            ShipmentBatch batch = route.getBatch();
            
            System.out.println((count + 1) + ". Lote: " + batch.batchId());
            System.out.println("   Origen: " + batch.origin().id() + " → Destino: " + 
                             batch.destination().id());
            System.out.println("   Cantidad: " + batch.quantity() + " maletas");
            System.out.println("   Vuelos: " + route.getFlights().size());
            System.out.println("   Cumple SLA: " + (route.meetsSLA() ? "✓ Sí" : "✗ No"));
            
            if (route.meetsSLA()) {
                long slackHours = route.getSLASlack().toHours();
                System.out.println("   Holgura: " + slackHours + " horas");
            }
            
            System.out.println();
            count++;
        }
    }
}
