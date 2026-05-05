package com.equipo2b.scheduler.execution;

import com.equipo2b.scheduler.algorithm.*;
import com.equipo2b.scheduler.logic.SolutionEvaluator;
import com.equipo2b.scheduler.model.*;
import com.equipo2b.scheduler.monitoring.*;
import com.equipo2b.scheduler.util.ShipmentGenerator;
import com.equipo2b.scheduler.validation.RouteValidator;

import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;

/**
 * Controlador principal para gestionar simulaciones del sistema de planificación logística.
 * 
 * Responsabilidades:
 * - Iniciar/detener/pausar simulaciones
 * - Gestionar los 3 escenarios (K=1, K=14, K=75)
 * - Permitir cancelaciones durante la ejecución
 * - Exponer estado actual de la simulación
 * 
 * **Validates: Requirements 21.1-21.5, 22.1-22.5, 24.1-24.5**
 */
public class SimulationController {
    
    // Componentes del sistema
    private final FlightPlan flightPlan;
    private final AirportManager airportManager;
    private final ClientRegistry clientRegistry;
    
    // Estado de la simulación
    private SimulationState state;
    private Thread simulationThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    
    // Scheduler y componentes
    private Scheduler scheduler;
    private volatile Solution currentSolution;
    private TabuSearch tabuSearch;
    private RouteValidator validator;

    // Identificador único de la simulación activa
    private String simulationId;

    // Listener para WebSocket (notificaciones por ciclo)
    private SimulationListener listener;
    
    // Estadísticas
    private int currentCycle = 0;
    private ZonedDateTime simulatedTime;
    private int batchesProcessed = 0;
    private int batchesFailed = 0;
    
    /**
     * Constructor del SimulationController.
     */
    public SimulationController(FlightPlan flightPlan,
                               AirportManager airportManager,
                               ClientRegistry clientRegistry) {
        this.flightPlan = Objects.requireNonNull(flightPlan);
        this.airportManager = Objects.requireNonNull(airportManager);
        this.clientRegistry = Objects.requireNonNull(clientRegistry);
        this.state = SimulationState.STOPPED;
        this.currentSolution = new Solution();
        this.simulationId = UUID.randomUUID().toString();
    }
    
    /**
     * Inicia una simulación con el escenario especificado.
     * 
     * @param scenario Tipo de escenario a ejecutar
     * @param historicalBatches Lotes históricos para la simulación
     * @throws IllegalStateException Si ya hay una simulación en ejecución
     */
    public void startSimulation(ScenarioType scenario, List<ShipmentBatch> historicalBatches) {
        if (running.get()) {
            throw new IllegalStateException("Ya hay una simulación en ejecución");
        }
        
        System.out.println("\n" + "=".repeat(80));
        System.out.println("INICIANDO SIMULACIÓN: " + scenario.getDescription());
        System.out.println("=".repeat(80));
        
        // Preparar datos según el escenario
        List<ShipmentBatch> batches = prepareData(scenario, historicalBatches);
        
        // Configurar algoritmos según el escenario
        GeneticAlgorithm ga = new GeneticAlgorithm(flightPlan, airportManager);
        TabuSearch tabu = new TabuSearch(flightPlan, airportManager);
        configureAlgorithms(ga, tabu, scenario);
        
        // Guardar referencias para replanificación
        this.tabuSearch = tabu;
        this.validator = new RouteValidator(airportManager);
        
        // Crear ShipmentQueue
        ShipmentQueue queue = new ShipmentQueue();
        for (ShipmentBatch batch : batches) {
            queue.addShipment(batch);
        }
        
        // Crear Scheduler con GATS (por defecto)
        SolutionEvaluator evaluator = new SolutionEvaluator(flightPlan, airportManager);
        
        this.scheduler = SchedulerFactory.createGATSScheduler(
            flightPlan, airportManager, queue, evaluator, validator,
            scenario.getTa(), scenario.getSa(), scenario.getK()
        );
        
        // Inicializar estado
        this.simulatedTime = batches.get(0).ingressTime();
        this.currentCycle = 0;
        this.batchesProcessed = 0;
        this.batchesFailed = 0;
        this.state = SimulationState.RUNNING;
        this.running.set(true);
        
        // Ejecutar en thread separado
        simulationThread = new Thread(() -> runSimulation(scenario));
        simulationThread.start();
    }
    
    /**
     * Detiene la simulación actual.
     */
    public void stopSimulation() {
        if (!running.get()) {
            System.out.println("⚠️  No hay simulación en ejecución");
            return;
        }
        
        System.out.println("\n🛑 Deteniendo simulación...");
        running.set(false);
        paused.set(false);
        state = SimulationState.STOPPED;
        
        if (simulationThread != null) {
            try {
                simulationThread.join(5000); // Esperar máximo 5 segundos
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        System.out.println("✓ Simulación detenida");
    }
    
    /**
     * Pausa la simulación actual.
     */
    public void pauseSimulation() {
        if (!running.get()) {
            System.out.println("⚠️  No hay simulación en ejecución");
            return;
        }
        
        if (paused.get()) {
            System.out.println("⚠️  La simulación ya está pausada");
            return;
        }
        
        System.out.println("\n⏸️  Pausando simulación...");
        paused.set(true);
        state = SimulationState.PAUSED;
        System.out.println("✓ Simulación pausada");
    }
    
    /**
     * Reanuda la simulación pausada.
     */
    public void resumeSimulation() {
        if (!running.get()) {
            System.out.println("⚠️  No hay simulación en ejecución");
            return;
        }
        
        if (!paused.get()) {
            System.out.println("⚠️  La simulación no está pausada");
            return;
        }
        
        System.out.println("\n▶️  Reanudando simulación...");
        paused.set(false);
        state = SimulationState.RUNNING;
        System.out.println("✓ Simulación reanudada");
    }
    
    /**
     * Registra una cancelación de vuelo durante la simulación.
     * 
     * Proceso:
     * 1. Validar que hay simulación activa
     * 2. Buscar vuelo por ID
     * 3. Validar que no haya despegado
     * 4. Obtener solución actual
     * 5. Ejecutar replanificación
     * 6. Actualizar solución en Scheduler
     * 7. Logging de resultados
     * 
     * @param flightId ID del vuelo a cancelar
     * 
     * **Validates: Requirements 24.1, 24.4, 24.5, 12.1-12.6, 25.1-25.4**
     */
    public void registerCancellation(String flightId) {
        // 1. Validar que hay simulación activa
        if (!running.get()) {
            System.out.println("⚠️  No hay simulación activa");
            return;
        }
        
        System.out.println("\n" + "=".repeat(80));
        System.out.println("🚫 CANCELACIÓN DE VUELO: " + flightId);
        System.out.println("=".repeat(80));
        
        // 2. Buscar vuelo por ID
        Flight cancelledFlight = findFlightById(flightId);
        if (cancelledFlight == null) {
            System.out.println("❌ Error: Vuelo no encontrado: " + flightId);
            System.out.println("=".repeat(80));
            return;
        }
        
        System.out.println("✓ Vuelo encontrado:");
        System.out.println("  Origen: " + cancelledFlight.origin().id());
        System.out.println("  Destino: " + cancelledFlight.destination().id());
        System.out.println("  Salida: " + cancelledFlight.departureTime());
        
        // 3. Validar que no haya despegado
        if (simulatedTime.isAfter(cancelledFlight.departureTime())) {
            System.out.println("❌ Error: No se puede cancelar - vuelo ya despegó");
            System.out.println("  Tiempo simulado: " + simulatedTime);
            System.out.println("  Hora de salida: " + cancelledFlight.departureTime());
            System.out.println("=".repeat(80));
            return;
        }
        
        System.out.println("✓ Vuelo puede ser cancelado (no ha despegado)");
        
        // 4. Obtener solución actual
        Solution currentSol = scheduler.getCurrentSolution();
        
        // 5. Crear Replanner y ejecutar replanificación
        System.out.println("\n🔄 Iniciando replanificación de emergencia...");
        Replanner replanner = new Replanner(flightPlan, tabuSearch, validator);
        ReplanResult result = replanner.replan(currentSol, cancelledFlight);
        
        // 6. Actualizar solución en Scheduler
        scheduler.updateSolution(result.updatedSolution());
        this.currentSolution = result.updatedSolution();
        
        // 7. Logging de resultados
        System.out.println("\n📊 RESULTADO DE REPLANIFICACIÓN:");
        System.out.println("  Lotes replanificados: " + result.replanedBatches().size());
        System.out.println("  Lotes no replanificables: " + result.unreplannableBatches().size());
        
        if (!result.unreplannableBatches().isEmpty()) {
            System.out.println("\n⚠️  LOTES NO REPLANIFICABLES:");
            for (ShipmentBatch batch : result.unreplannableBatches()) {
                System.out.println("    - " + batch.batchId() + 
                                 " (" + batch.origin().id() + " → " + batch.destination().id() + ")");
            }
        }
        
        // Usar PlanningLogger
        com.equipo2b.scheduler.util.PlanningLogger.logCancellation(
            cancelledFlight, 
            result.replanedBatches().size() + result.unreplannableBatches().size()
        );
        com.equipo2b.scheduler.util.PlanningLogger.logReplanningResult(
            result.replanedBatches().size(),
            result.unreplannableBatches().size()
        );
        
        System.out.println("\n✓ Replanificación completada exitosamente");
        System.out.println("=".repeat(80));
    }
    
    /**
     * Busca un vuelo por su ID en el plan de vuelos.
     * 
     * @param flightId ID del vuelo a buscar
     * @return Vuelo encontrado o null si no existe
     */
    private Flight findFlightById(String flightId) {
        for (Flight flight : flightPlan.getAllFlights()) {
            if (flight.flightId().equals(flightId)) {
                return flight;
            }
        }
        return null;
    }
    
    /**
     * Obtiene el estado actual de la simulación.
     * 
     * @return Estado actual
     */
    public SimulationStatus getStatus() {
        CollapseDetector detector = new CollapseDetector();
        CollapseStatus collapseStatus = detector.evaluateCollapse(
            currentSolution, batchesProcessed, batchesFailed
        );
        
        return new SimulationStatus(
            running.get(),
            paused.get(),
            currentCycle,
            simulatedTime,
            batchesProcessed,
            batchesFailed,
            currentSolution.getFitness(),
            collapseStatus.level()
        );
    }
    
    /**
     * Obtiene la solución actual.
     * 
     * @return Solución actual
     */
    public Solution getCurrentSolution() {
        return currentSolution;
    }

    /**
     * Verifica si hay una simulación en ejecución.
     */
    public boolean isRunning() {
        return running.get();
    }

    // ==================== MÉTODOS PRIVADOS ====================
    
    /**
     * Ejecuta la simulación en el thread separado.
     */
    private void runSimulation(ScenarioType scenario) {
        try {
            while (running.get()) {
                // Esperar si está pausado
                while (paused.get() && running.get()) {
                    Thread.sleep(100);
                }
                
                if (!running.get()) break;
                
                // Ejecutar ciclo de planificación
                currentCycle++;
                System.out.println("\n--- CICLO " + currentCycle + " ---");
                
                currentSolution = scheduler.executePlanningCycle(simulatedTime);

                // Actualizar estadísticas
                batchesProcessed = currentSolution.getRoutes().size();

                // Avanzar tiempo simulado
                simulatedTime = simulatedTime.plusMinutes(scenario.getSa());

                // Verificar colapso
                CollapseDetector detector = new CollapseDetector();
                CollapseStatus collapseStatus = detector.evaluateCollapse(
                    currentSolution, batchesProcessed, batchesFailed
                );

                // Notificar listener (WebSocket) al final de cada ciclo
                if (listener != null) {
                    try {
                        listener.onCycleCompleted(getStatus(), currentSolution);
                    } catch (Exception e) {
                        System.err.println("⚠️ Error notificando listener: " + e.getMessage());
                    }
                }

                if (collapseStatus.isCollapsed()) {
                    System.out.println("\n⚠️  COLAPSO DETECTADO - Deteniendo simulación");
                    break;
                }
                
                // Simular tiempo de espera entre ciclos (para visualización)
                Thread.sleep(1000); // 1 segundo entre ciclos
            }
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("⚠️  Simulación interrumpida");
        } finally {
            running.set(false);
            paused.set(false);
            state = SimulationState.STOPPED;
            System.out.println("\n✓ Simulación finalizada");
            printFinalSummary();

            // Notificar listener (WebSocket) al terminar
            if (listener != null) {
                try {
                    listener.onSimulationFinished(getStatus());
                } catch (Exception e) {
                    System.err.println("⚠️ Error notificando listener en finish: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Prepara los datos según el escenario.
     */
    private List<ShipmentBatch> prepareData(ScenarioType scenario, List<ShipmentBatch> historical) {
        switch (scenario) {
            case DAY_TO_DAY:
                // Usar solo datos históricos limitados
                int numBatches = Math.min(100, historical.size());
                return historical.subList(0, numBatches);
                
            case PERIOD_SIMULATION:
                // Generar datos futuros con factor de crecimiento moderado
                ShipmentGenerator generator = new ShipmentGenerator();
                List<ShipmentBatch> futureBatches = generator.generateFutureShipments(
                    historical, scenario.getK(), 1.20
                );
                List<ShipmentBatch> allBatches = new ArrayList<>(historical);
                allBatches.addAll(futureBatches);
                return allBatches.subList(0, Math.min(500, allBatches.size()));
                
            case COLLAPSE_SIMULATION:
                // Generar datos futuros con factor de crecimiento alto
                ShipmentGenerator collapseGenerator = new ShipmentGenerator();
                List<ShipmentBatch> collapseBatches = collapseGenerator.generateFutureShipments(
                    historical, scenario.getK(), 1.23
                );
                List<ShipmentBatch> collapseAll = new ArrayList<>(historical);
                collapseAll.addAll(collapseBatches);
                return collapseAll.subList(0, Math.min(2000, collapseAll.size()));
                
            default:
                throw new IllegalArgumentException("Escenario desconocido: " + scenario);
        }
    }
    
    /**
     * Configura los algoritmos según el escenario.
     */
    private void configureAlgorithms(GeneticAlgorithm ga, TabuSearch tabu, ScenarioType scenario) {
        AlgorithmConfig gaConfig = new AlgorithmConfig();
        AlgorithmConfig tabuConfig = new AlgorithmConfig();
        
        switch (scenario) {
            case DAY_TO_DAY:
                // Configuración rápida
                gaConfig.setInt("populationSize", 20);
                gaConfig.setInt("generations", 15);
                gaConfig.setDouble("mutationRate", 0.1);
                tabuConfig.setInt("maxIterations", 50);
                tabuConfig.setInt("tabuTenure", 10);
                tabuConfig.setInt("neighborhoodSize", 15);
                break;
                
            case PERIOD_SIMULATION:
                // Configuración balanceada
                gaConfig.setInt("populationSize", 30);
                gaConfig.setInt("generations", 20);
                gaConfig.setDouble("mutationRate", 0.1);
                tabuConfig.setInt("maxIterations", 100);
                tabuConfig.setInt("tabuTenure", 12);
                tabuConfig.setInt("neighborhoodSize", 20);
                break;
                
            case COLLAPSE_SIMULATION:
                // Configuración intensiva
                gaConfig.setInt("populationSize", 40);
                gaConfig.setInt("generations", 25);
                gaConfig.setDouble("mutationRate", 0.1);
                tabuConfig.setInt("maxIterations", 150);
                tabuConfig.setInt("tabuTenure", 15);
                tabuConfig.setInt("neighborhoodSize", 25);
                break;
        }
        
        ga.configure(gaConfig);
        tabu.configure(tabuConfig);
    }
    
    /**
     * Imprime resumen final de la simulación.
     */
    private void printFinalSummary() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("RESUMEN FINAL DE LA SIMULACIÓN");
        System.out.println("=".repeat(80));
        System.out.println("Ciclos ejecutados: " + currentCycle);
        System.out.println("Lotes procesados: " + batchesProcessed);
        System.out.println("Lotes fallidos: " + batchesFailed);
        System.out.println("Fitness final: " + String.format("%.2f", currentSolution.getFitness()));
        
        CollapseDetector detector = new CollapseDetector();
        CollapseStatus collapseStatus = detector.evaluateCollapse(
            currentSolution, batchesProcessed, batchesFailed
        );
        System.out.println("\nEstado del Sistema: " + collapseStatus.level());
        System.out.println("=".repeat(80));
    }
    
    /**
     * Estado de la simulación.
     */
    public enum SimulationState {
        STOPPED,
        RUNNING,
        PAUSED
    }

    // ===== MÉTODOS ADICIONALES =====

    /**
     * Retorna el identificador único de la simulación activa.
     */
    public String getSimulationId() {
        return simulationId;
    }

    /**
     * Registra un listener para recibir notificaciones por ciclo.
     * Usado por el WebSocket handler para emitir estado en tiempo real.
     */
    public void setListener(SimulationListener listener) {
        this.listener = listener;
    }

    /**
     * Interface de listener para notificaciones de simulación.
     * Implementada por SimulationWebSocketHandler.
     */
    public interface SimulationListener {
        /**
         * Llamado al finalizar cada ciclo de planificación.
         * @param status Estado actual de la simulación
         * @param solution Solución acumulada actual
         */
        void onCycleCompleted(SimulationStatus status, Solution solution);

        /**
         * Llamado cuando la simulación termina (por fin de datos, colapso o stop manual).
         * @param status Estado final
         */
        void onSimulationFinished(SimulationStatus status);
    }
}
