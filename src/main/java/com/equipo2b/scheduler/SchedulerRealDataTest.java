package com.equipo2b.scheduler;

import com.equipo2b.scheduler.algorithm.*;
import com.equipo2b.scheduler.execution.Scheduler;
import com.equipo2b.scheduler.logic.SolutionEvaluator;
import com.equipo2b.scheduler.model.*;
import com.equipo2b.scheduler.upload.*;
import com.equipo2b.scheduler.validation.RouteValidator;
import com.equipo2b.scheduler.validation.ValidationReport;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Prueba real del Scheduler con datos reales.
 * 
 * Demuestra:
 * - Carga de datos reales
 * - Configuración del Scheduler con parámetros Ta, Sa, K
 * - Ejecución de ciclos de planificación
 * - Validación de soluciones
 * - Métricas finales
 */
public class SchedulerRealDataTest {
    
    public static void main(String[] args) {
        System.out.println("=".repeat(80));
        System.out.println("PRUEBA REAL DEL SCHEDULER CON DATOS REALES");
        System.out.println("=".repeat(80));
        System.out.println();
        
        try {
            // Activar modo LENIENT para datos reales
            ValidationMode.setMode(ValidationMode.Mode.LENIENT);
            System.out.println("Modo de validación: " + ValidationMode.getMode());
            System.out.println();
            
            // ========================================
            // FASE 1: CARGA DE DATOS
            // ========================================
            System.out.println("FASE 1: Cargando datos reales...");
            System.out.println("-".repeat(80));
            
            // Cargar aeropuertos
            AirportUploader airportUploader = new AirportUploader();
            List<Airport> airports = airportUploader.loadAirports(
                "data/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt"
            );
            AirportManager airportManager = new AirportManager();
            for (Airport airport : airports) {
                airportManager.addAirport(airport);
            }
            System.out.println("✓ Aeropuertos cargados: " + airports.size());
            
            // Cargar plan de vuelos
            FlightPlanUploader flightPlanUploader = new FlightPlanUploader();
            FlightPlan flightPlan = flightPlanUploader.loadFlights(
                "data/planes_vuelo.txt",
                airportManager
            );
            System.out.println("✓ Vuelos cargados: " + flightPlan.getTotalFlights());
            
            // Cargar clientes (extraer de archivos de envíos)
            ClientRegistry clientRegistry = new ClientRegistry();
            
            // Extraer IDs de cliente del archivo
            java.util.Set<String> clientIds = new java.util.HashSet<>();
            java.nio.file.Path shipmentPath = java.nio.file.Paths.get("data/_envios_preliminar_/_envios_SCEL_.txt");
            java.util.stream.Stream<String> lines = java.nio.file.Files.lines(shipmentPath);
            lines.forEach(line -> {
                if (!line.isBlank()) {
                    String[] parts = line.split("-");
                    if (parts.length >= 7) {
                        clientIds.add(parts[6].trim());
                    }
                }
            });
            lines.close();
            
            // Registrar clientes
            for (String clientId : clientIds) {
                clientRegistry.addClient(new AirlineClient(
                    clientId,
                    "Client " + clientId,
                    "contact@client.com",
                    "+000000000"
                ));
            }
            System.out.println("✓ Clientes registrados: " + clientIds.size());
            
            // Cargar envíos
            ShipmentUploader shipmentUploader = new ShipmentUploader();
            List<ShipmentBatch> allBatches = shipmentUploader.loadShipments(
                "data/_envios_preliminar_/_envios_SCEL_.txt",  // Santiago de Chile
                airportManager,
                clientRegistry
            );
            
            // Tomar solo los primeros 20 lotes para prueba rápida
            List<ShipmentBatch> batches = allBatches.subList(0, Math.min(20, allBatches.size()));
            System.out.println("✓ Lotes cargados para prueba: " + batches.size() + " (de " + allBatches.size() + " totales)");
            System.out.println();
            
            // ========================================
            // FASE 2: CONFIGURACIÓN DEL SCHEDULER
            // ========================================
            System.out.println("FASE 2: Configurando Scheduler...");
            System.out.println("-".repeat(80));
            
            // Crear componentes
            GeneticAlgorithm geneticAlgorithm = new GeneticAlgorithm(flightPlan, airportManager);
            TabuSearch tabuSearch = new TabuSearch(flightPlan, airportManager);
            SolutionEvaluator evaluator = new SolutionEvaluator(flightPlan, airportManager);
            RouteValidator validator = new RouteValidator(airportManager);
            
            // Configurar algoritmos para ejecución rápida
            AlgorithmConfig gaConfig = new AlgorithmConfig();
            gaConfig.setInt("populationSize", 15);
            gaConfig.setInt("generations", 10);
            gaConfig.setDouble("mutationRate", 0.1);
            gaConfig.setInt("tournamentSize", 3);
            gaConfig.setInt("eliteCount", 2);
            geneticAlgorithm.configure(gaConfig);
            
            AlgorithmConfig tabuConfig = new AlgorithmConfig();
            tabuConfig.setInt("maxIterations", 50);
            tabuConfig.setInt("tabuTenure", 10);
            tabuConfig.setInt("neighborhoodSize", 15);
            tabuSearch.configure(tabuConfig);
            
            System.out.println("✓ Algoritmo Genético configurado: pop=15, gen=10");
            System.out.println("✓ Búsqueda Tabú configurada: iter=50, tenure=10");
            
            // Crear ShipmentQueue y agregar lotes
            ShipmentQueue shipmentQueue = new ShipmentQueue();
            for (ShipmentBatch batch : batches) {
                shipmentQueue.addShipment(batch);
            }
            System.out.println("✓ ShipmentQueue inicializada con " + batches.size() + " lotes");
            
            // Parámetros del Scheduler (escenario día a día simplificado)
            int Ta = 2;   // 2 minutos máximo por ciclo
            int Sa = 5;   // Ejecutar cada 5 minutos
            int K = 1;    // K=1 para día a día
            
            System.out.println("✓ Parámetros: Ta=" + Ta + " min, Sa=" + Sa + " min, K=" + K);
            System.out.println("  Sc (salto consumo) = Sa × K = " + (Sa * K) + " minutos");
            System.out.println();
            
            // Crear Scheduler
            Scheduler scheduler = new Scheduler(
                geneticAlgorithm,
                tabuSearch,
                shipmentQueue,
                evaluator,
                validator,
                Ta, Sa, K
            );
            System.out.println("✓ Scheduler creado exitosamente");
            System.out.println();
            
            // ========================================
            // FASE 3: EJECUCIÓN DE SIMULACIÓN
            // ========================================
            System.out.println("FASE 3: Ejecutando simulación...");
            System.out.println("-".repeat(80));
            
            // Fecha de inicio (primera fecha de los datos)
            ZonedDateTime startDate = batches.get(0).ingressTime();
            System.out.println("Fecha de inicio: " + startDate);
            
            // Ejecutar simulación (máximo 3 ciclos para prueba)
            int maxCycles = 3;
            System.out.println("Ciclos máximos: " + maxCycles);
            System.out.println();
            
            long startTime = System.currentTimeMillis();
            Solution finalSolution = scheduler.run(startDate, maxCycles);
            long totalTime = System.currentTimeMillis() - startTime;
            
            System.out.println();
            System.out.println("✓ Simulación completada en " + totalTime + " ms");
            System.out.println();
            
            // ========================================
            // FASE 4: ANÁLISIS DE RESULTADOS
            // ========================================
            System.out.println("FASE 4: Análisis de resultados...");
            System.out.println("-".repeat(80));
            
            // Validación final
            ValidationReport finalReport = validator.validate(finalSolution);
            
            System.out.println("\n📊 MÉTRICAS FINALES");
            System.out.println("=".repeat(80));
            System.out.println("Rutas generadas: " + finalSolution.getRoutes().size());
            System.out.println("Fitness final: " + String.format("%.2f", finalSolution.getFitness()));
            System.out.println("Maletas planificadas: " + finalSolution.getTotalBags());
            System.out.println("Vuelos utilizados: " + finalSolution.getUsedFlights().size());
            
            // Análisis de cumplimiento SLA
            int totalRoutes = finalSolution.getRoutes().size();
            int routesMeetingSLA = 0;
            long totalSlackHours = 0;
            
            for (AssignedRoute route : finalSolution.getRoutes().values()) {
                if (route.meetsSLA()) {
                    routesMeetingSLA++;
                    totalSlackHours += route.getSLASlack().toHours();
                }
            }
            
            double slaCompliance = totalRoutes > 0 ? (routesMeetingSLA * 100.0 / totalRoutes) : 0;
            double avgSlack = routesMeetingSLA > 0 ? (totalSlackHours / (double) routesMeetingSLA) : 0;
            
            System.out.println("\n📈 CUMPLIMIENTO SLA");
            System.out.println("Rutas que cumplen SLA: " + routesMeetingSLA + "/" + totalRoutes + 
                             " (" + String.format("%.1f%%", slaCompliance) + ")");
            System.out.println("Holgura promedio: " + String.format("%.1f", avgSlack) + " horas");
            
            // Distribución de rutas
            int directRoutes = 0;
            int oneStopRoutes = 0;
            int twoStopRoutes = 0;
            
            for (AssignedRoute route : finalSolution.getRoutes().values()) {
                int stops = route.getFlights().size() - 1;
                if (stops == 0) directRoutes++;
                else if (stops == 1) oneStopRoutes++;
                else twoStopRoutes++;
            }
            
            System.out.println("\n🛫 DISTRIBUCIÓN DE RUTAS");
            System.out.println("Rutas directas: " + directRoutes);
            System.out.println("Rutas con 1 escala: " + oneStopRoutes);
            System.out.println("Rutas con 2+ escalas: " + twoStopRoutes);
            
            // Reporte de validación
            System.out.println("\n✅ VALIDACIÓN");
            if (finalReport.isValid()) {
                System.out.println("✓ Solución VÁLIDA - Sin violaciones");
            } else {
                System.out.println("⚠ Solución con violaciones:");
                System.out.println(finalReport.getSummary());
            }
            
            System.out.println("\n" + "=".repeat(80));
            System.out.println("PRUEBA COMPLETADA EXITOSAMENTE");
            System.out.println("=".repeat(80));
            
        } catch (Exception e) {
            System.err.println("\n❌ ERROR EN LA PRUEBA:");
            e.printStackTrace();
            System.exit(1);
        }
    }
}
