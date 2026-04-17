package com.equipo2b.scheduler;

import com.equipo2b.scheduler.execution.*;
import com.equipo2b.scheduler.model.*;
import com.equipo2b.scheduler.upload.*;

import java.util.*;

/**
 * Punto de entrada principal del sistema de planificación logística.
 * 
 * <p>Soporta tres escenarios de simulación:</p>
 * <ul>
 *   <li>Escenario 1 (K=1): Operación día a día</li>
 *   <li>Escenario 2 (K=14): Simulación de periodo (3-5 días)</li>
 *   <li>Escenario 3 (K=75): Simulación hasta colapso (2.5 meses)</li>
 * </ul>
 * 
 * <p><b>Uso:</b></p>
 * <pre>
 * java com.equipo2b.scheduler.Main
 * </pre>
 * 
 * <p><b>Requisitos implementados:</b></p>
 * <ul>
 *   <li>16.1: Punto de entrada con argumentos de línea de comandos</li>
 *   <li>21.1-21.5: Tres escenarios de simulación</li>
 *   <li>24.1-24.5: Cancelaciones de vuelos</li>
 * </ul>
 */
public class Main {
    
    // Rutas de archivos de datos
    private static final String AIRPORTS_FILE = "data/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt";
    private static final String FLIGHTS_FILE = "data/planes_vuelo.txt";
    private static final String SHIPMENTS_DIR = "data/_envios_preliminar_";
    
    private static SimulationController controller;
    private static Scanner scanner;
    
    public static void main(String[] args) {
        System.out.println("=".repeat(80));
        System.out.println("SISTEMA DE PLANIFICACIÓN LOGÍSTICA TASF.B2B");
        System.out.println("=".repeat(80));
        System.out.println();
        
        scanner = new Scanner(System.in);
        
        try {
            // Cargar datos base (UNA SOLA VEZ)
            System.out.println("📂 Cargando datos base del sistema...");
            AirportManager airportManager = loadAirports();
            FlightPlan flightPlan = loadFlights(airportManager);
            ClientRegistry clientRegistry = loadClients();
            List<ShipmentBatch> historicalBatches = loadHistoricalShipments(airportManager, clientRegistry);
            
            System.out.println("✓ Datos cargados:");
            System.out.println("  - Aeropuertos: " + airportManager.getAllAirports().size());
            System.out.println("  - Vuelos: " + flightPlan.getAllFlights().size());
            System.out.println("  - Clientes: " + clientRegistry.getAllClients().size());
            System.out.println("  - Lotes históricos: " + historicalBatches.size());
            System.out.println();
            
            // Crear controlador de simulación
            controller = new SimulationController(flightPlan, airportManager, clientRegistry);
            
            // Mostrar menú interactivo
            showInteractiveMenu(historicalBatches);
            
        } catch (Exception e) {
            System.err.println("\n❌ ERROR FATAL: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            if (scanner != null) {
                scanner.close();
            }
        }
    }
    
    /**
     * Muestra el menú interactivo principal.
     */
    private static void showInteractiveMenu(List<ShipmentBatch> historicalBatches) {
        boolean exit = false;
        
        while (!exit) {
            System.out.println("\n" + "=".repeat(80));
            System.out.println("MENÚ PRINCIPAL");
            System.out.println("=".repeat(80));
            System.out.println("1. Iniciar Escenario 1 (K=1 - Día a Día)");
            System.out.println("2. Iniciar Escenario 2 (K=14 - Período 3-5 días)");
            System.out.println("3. Iniciar Escenario 3 (K=75 - Hasta Colapso)");
            System.out.println("4. Ver estado de simulación");
            System.out.println("5. Pausar simulación");
            System.out.println("6. Reanudar simulación");
            System.out.println("7. Detener simulación");
            System.out.println("8. Registrar cancelación de vuelo");
            System.out.println("0. Salir");
            System.out.println("=".repeat(80));
            System.out.print("Seleccione una opción: ");
            
            try {
                int option = scanner.nextInt();
                scanner.nextLine(); // Consumir newline
                
                switch (option) {
                    case 1 -> startScenario(ScenarioType.DAY_TO_DAY, historicalBatches);
                    case 2 -> startScenario(ScenarioType.PERIOD_SIMULATION, historicalBatches);
                    case 3 -> startScenario(ScenarioType.COLLAPSE_SIMULATION, historicalBatches);
                    case 4 -> showStatus();
                    case 5 -> controller.pauseSimulation();
                    case 6 -> controller.resumeSimulation();
                    case 7 -> controller.stopSimulation();
                    case 8 -> registerCancellation();
                    case 0 -> {
                        System.out.println("\n👋 Saliendo del sistema...");
                        controller.stopSimulation();
                        exit = true;
                    }
                    default -> System.out.println("⚠️  Opción inválida");
                }
            } catch (InputMismatchException e) {
                System.out.println("⚠️  Entrada inválida. Por favor ingrese un número.");
                scanner.nextLine(); // Limpiar buffer
            }
        }
    }
    
    /**
     * Inicia un escenario de simulación.
     */
    private static void startScenario(ScenarioType scenario, List<ShipmentBatch> historicalBatches) {
        try {
            controller.startSimulation(scenario, historicalBatches);
            System.out.println("✓ Simulación iniciada");
            System.out.println("  Puede pausar/detener desde el menú principal");
        } catch (IllegalStateException e) {
            System.out.println("⚠️  " + e.getMessage());
        }
    }
    
    /**
     * Muestra el estado actual de la simulación.
     */
    private static void showStatus() {
        SimulationStatus status = controller.getStatus();
        System.out.println("\n" + "=".repeat(80));
        System.out.println("ESTADO DE LA SIMULACIÓN");
        System.out.println("=".repeat(80));
        System.out.println(status.getSummary());
        System.out.println("=".repeat(80));
    }
    
    /**
     * Registra una cancelación de vuelo.
     */
    private static void registerCancellation() {
        System.out.print("\nIngrese el ID del vuelo a cancelar: ");
        String flightId = scanner.nextLine();
        controller.registerCancellation(flightId);
    }
    
    /**
     * Carga aeropuertos desde archivo.
     */
    private static AirportManager loadAirports() {
        try {
            AirportUploader uploader = new AirportUploader();
            List<Airport> airports = uploader.loadAirports(AIRPORTS_FILE);
            
            AirportManager manager = new AirportManager();
            for (Airport airport : airports) {
                manager.addAirport(airport);
            }
            
            return manager;
        } catch (Exception e) {
            throw new RuntimeException("Error cargando aeropuertos: " + e.getMessage(), e);
        }
    }
    
    /**
     * Carga plan de vuelos desde archivo.
     */
    private static FlightPlan loadFlights(AirportManager airportManager) {
        try {
            FlightPlanUploader uploader = new FlightPlanUploader();
            return uploader.loadFlights(FLIGHTS_FILE, airportManager);
        } catch (Exception e) {
            throw new RuntimeException("Error cargando vuelos: " + e.getMessage(), e);
        }
    }
    
    /**
     * Carga registro de clientes.
     */
    private static ClientRegistry loadClients() {
        // Por ahora, retornar registro vacío
        // Los clientes se registrarán automáticamente al cargar envíos
        return new ClientRegistry();
    }
    
    /**
     * Carga envíos históricos desde archivos.
     */
    private static List<ShipmentBatch> loadHistoricalShipments(AirportManager airportManager,
                                                               ClientRegistry clientRegistry) {
        try {
            ShipmentUploader uploader = new ShipmentUploader();
            
            // Cargar primer archivo de envíos
            String shipmentFile = SHIPMENTS_DIR + "/c.1inf54.26-1.v1.envios.v1.20250818__estudiantes.txt";
            List<ShipmentBatch> batches = uploader.loadShipments(
                shipmentFile, airportManager, clientRegistry
            );
            
            return batches;
        } catch (Exception e) {
            throw new RuntimeException("Error cargando envíos: " + e.getMessage(), e);
        }
    }
    
    /**
     * Imprime instrucciones de uso.
     */
    private static void printUsage() {
        System.out.println("Uso: java com.equipo2b.scheduler.Main [escenario]");
        System.out.println();
        System.out.println("Escenarios disponibles:");
        System.out.println("  1 - Escenario K=1 (Día a Día)");
        System.out.println("  2 - Escenario K=14 (Período - 2 semanas)");
        System.out.println("  3 - Escenario K=75 (Colapso - 2.5 meses)");
        System.out.println("  4 - Ejecutar todos los escenarios");
        System.out.println();
        System.out.println("Ejemplo:");
        System.out.println("  java com.equipo2b.scheduler.Main 1");
    }
}
