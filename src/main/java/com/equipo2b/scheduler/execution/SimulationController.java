package com.equipo2b.scheduler.execution;

import com.equipo2b.scheduler.algorithm.*;
import com.equipo2b.scheduler.logic.SolutionEvaluator;
import com.equipo2b.scheduler.model.*;
import com.equipo2b.scheduler.monitoring.*;
import com.equipo2b.scheduler.util.ShipmentGenerator;
import com.equipo2b.scheduler.validation.RouteValidator;

import java.time.ZonedDateTime;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
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
    private static final MemoryMXBean MEMORY_BEAN = ManagementFactory.getMemoryMXBean();
    
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
    private ScenarioType currentScenario;

    // Identificador único de la simulación activa
    private String simulationId;

    // Listener para WebSocket (notificaciones por ciclo)
    private SimulationListener listener;
    
    // Estadísticas
    private int currentCycle = 0;
    private ZonedDateTime simulatedTime;
    private int batchesProcessed = 0;
    private int batchesFailed = 0;
    
    // Almacena los lotes procesados para persistencia final
    private List<ShipmentBatch> currentBatches;

    // Fecha de inicio para calcular días transcurridos
    private ZonedDateTime startDate;
    private volatile double daysElapsed = 0.0;
    
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
        
        this.currentScenario = scenario;
        // Preparar datos según el escenario y guardarlos para persistencia final
        this.currentBatches = new ArrayList<>(prepareData(scenario, historicalBatches));
        
        // Configurar algoritmos según el escenario
        GeneticAlgorithm ga = new GeneticAlgorithm(flightPlan, airportManager);
        TabuSearch tabu = new TabuSearch(flightPlan, airportManager);
        configureAlgorithms(ga, tabu, scenario);
        
        // Guardar referencias para replanificación
        this.tabuSearch = tabu;
        this.validator = new RouteValidator(airportManager);
        
        // Crear ShipmentQueue
        ShipmentQueue queue = new ShipmentQueue();
        for (ShipmentBatch batch : currentBatches) {
            queue.addShipment(batch);
        }
        
        // Crear Scheduler con GATS (por defecto)
        SolutionEvaluator evaluator = new SolutionEvaluator(flightPlan, airportManager);
        
        this.scheduler = SchedulerFactory.createGATSScheduler(
            ga, tabu, queue, evaluator, validator,
            scenario.getTa(), scenario.getSa(), scenario.getK()
        );
        
        // Inicializar estado
        this.simulatedTime = startDate != null
            ? startDate
            : (currentBatches.isEmpty() ? ZonedDateTime.now() : currentBatches.get(0).ingressTime());
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
     * Registra un lote creado desde la operación día a día.
     * El lote queda disponible para consumo en el siguiente ciclo de planificación.
     */
    public synchronized ShipmentBatch addShipment(ShipmentBatch batch) {
        if (!running.get()) {
            throw new IllegalStateException("No hay simulación activa");
        }
        if (currentScenario != ScenarioType.DAY_TO_DAY) {
            throw new IllegalStateException("La carga transaccional de maletas solo está habilitada para DAY_TO_DAY");
        }
        if (scheduler == null) {
            throw new IllegalStateException("Scheduler no inicializado");
        }

        currentBatches.add(batch);
        scheduler.addShipment(batch);
        System.out.printf("✓ Lote registrado desde UI: %s (%s → %s, %d maletas)%n",
            batch.batchId(), batch.origin().id(), batch.destination().id(), batch.quantity());
        return batch;
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

    /**
     * Establece la fecha de inicio para cálculo de días transcurridos.
     * Llamar ANTES de startSimulation.
     */
    public void setStartDate(ZonedDateTime startDate) {
        this.startDate = startDate;
    }

    /**
     * Retorna los días transcurridos en la simulación (0.0–5.0).
     */
    public double getDaysElapsed() {
        return daysElapsed;
    }

    public ZonedDateTime getSimulatedTime() {
        return simulatedTime;
    }

    public int getPendingCount() {
        return scheduler != null ? scheduler.getPendingCount() : 0;
    }

    /**
     * Retorna los lotes actuales preparados para esta simulación.
     */
    public List<ShipmentBatch> getCurrentBatches() {
        return currentBatches;
    }
    
    /**
     * Retorna el tipo de algoritmo utilizado.
     */
    public String getAlgorithmType() {
        return scheduler != null ? scheduler.getAlgorithmType().name() : "UNKNOWN";
    }

    // ==================== MÉTODOS PRIVADOS ====================
    
    /**
     * Ejecuta la simulación en el thread separado.
     */
    private void runSimulation(ScenarioType scenario) {
        try {
            long simStartRealMs = System.currentTimeMillis();
            long simStartSimMs = simulatedTime.toInstant().toEpochMilli();

            while (running.get()) {
                // Esperar si está pausado
                while (paused.get() && running.get()) {
                    Thread.sleep(100);
                }
                
                if (!running.get()) break;
                
                // =========== INICIO DEL CICLO ===========
                currentCycle++;
                long cycleStartRealMs = System.currentTimeMillis();
                System.out.println("\n--- CICLO " + currentCycle + " ---");
                
                // 1. Ejecutar algoritmo (hasta Ta minutos reales máximo)
                currentSolution = scheduler.executePlanningCycle(simulatedTime);
                long algorithmMs = System.currentTimeMillis() - cycleStartRealMs;
                logResourceUsage(scenario, algorithmMs);

                // Actualizar estadísticas
                batchesProcessed = currentSolution.getRoutes().size();

                // 2. Avanzar tiempo simulado: basado en tiempo real transcurrido × K
                //    Esto asegura que el reloj del backend coincida con el del frontend
                updateSimulatedClock(simStartRealMs, simStartSimMs, scenario);

                // 3. Verificar colapso
                CollapseDetector detector = new CollapseDetector();
                CollapseStatus collapseStatus = detector.evaluateCollapse(
                    currentSolution, batchesProcessed, batchesFailed
                );

                // 4. Notificar listener (WebSocket) INMEDIATAMENTE cuando el algoritmo termina
                if (listener != null) {
                    try {
                        listener.onCycleCompleted(getStatus(), currentSolution);
                    } catch (Exception e) {
                        System.err.println("⚠️ Error notificando listener: " + e.getMessage());
                    }
                }

                if (collapseStatus.isCollapsed()) {
                    if (scenario == ScenarioType.COLLAPSE_SIMULATION) {
                        System.out.println("\n⚠️  COLAPSO DETECTADO - Deteniendo simulación");
                        break;
                    } else {
                        System.out.println("⚠️  Alerta de colapso (informativo) - la simulación continúa");
                    }
                }
                
                // 5. Condición de parada natural para simulación de 5 días
                if (scenario == ScenarioType.PERIOD_SIMULATION && daysElapsed >= 5.0) {
                    System.out.println("\n✅ Simulación de 5 días completada (días transcurridos: " 
                        + String.format("%.2f", daysElapsed) + ")");
                    break;
                }

                // 6. Esperar Sa minutos reales desde el INICIO del ciclo (no desde el fin del algoritmo)
                //    Esto mantiene el ritmo constante: ciclos cada Sa minutos reales
                long saMs = scenario.getSa() * 60_000L;
                long elapsedInCycleMs = System.currentTimeMillis() - cycleStartRealMs;
                long remainingMs = saMs - elapsedInCycleMs;

                if (remainingMs > 0) {
                    System.out.printf("⏳ Algoritmo terminó en %.1fs — esperando %.1fs hasta el siguiente ciclo (Sa=%d min)%n",
                        algorithmMs / 1000.0, remainingMs / 1000.0, scenario.getSa());
                    sleepUntilNextCycle(remainingMs, simStartRealMs, simStartSimMs, scenario);
                } else {
                    System.out.printf("⚠️ Algoritmo tardó %.1fs (> Sa=%d min) — siguiente ciclo inmediato%n",
                        algorithmMs / 1000.0, scenario.getSa());
                }
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

            releaseHeavyState(scenario);
        }
    }

    private void logResourceUsage(ScenarioType scenario, long algorithmMs) {
        MemoryUsage heap = MEMORY_BEAN.getHeapMemoryUsage();
        long usedMb = heap.getUsed() / (1024 * 1024);
        long committedMb = heap.getCommitted() / (1024 * 1024);
        long maxMb = heap.getMax() / (1024 * 1024);
        int routes = currentSolution != null ? currentSolution.getRoutes().size() : 0;
        int currentBatchCount = currentBatches != null ? currentBatches.size() : 0;

        System.out.printf(
            "📊 Recursos ciclo %d [%s]: heap=%d/%d MB committed=%d MB, rutas=%d, lotes=%d, algoritmo=%.1fs%n",
            currentCycle,
            scenario.name(),
            usedMb,
            maxMb,
            committedMb,
            routes,
            currentBatchCount,
            algorithmMs / 1000.0
        );
    }

    private void releaseHeavyState(ScenarioType scenario) {
        if (scenario == ScenarioType.PERIOD_SIMULATION || scenario == ScenarioType.DAY_TO_DAY) {
            currentBatches = Collections.emptyList();
            currentSolution = new Solution();
            scheduler = null;
            tabuSearch = null;
            validator = null;
            System.out.println("✓ Referencias pesadas liberadas tras finalizar " + scenario.name());
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
                // Usar todos los lotes reales cargados para la ventana seleccionada.
                // El filtrado por fecha/hora se realiza antes, en SimulationService.
                return historical;
                
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
                gaConfig.setBoolean("parallelEnabled", false);
                tabuConfig.setInt("maxIterations", 50);
                tabuConfig.setInt("tabuTenure", 10);
                tabuConfig.setInt("neighborhoodSize", 15);
                break;
                
            case PERIOD_SIMULATION:
                // Configuración ajustada para VM 2 CPU / 2 GB
                gaConfig.setInt("populationSize", 30);
                gaConfig.setInt("generations", 15);
                gaConfig.setDouble("mutationRate", 0.1);
                gaConfig.setBoolean("parallelEnabled", false);
                tabuConfig.setInt("maxIterations", 80);
                tabuConfig.setInt("tabuTenure", 12);
                tabuConfig.setInt("neighborhoodSize", 20);
                break;
                
            case COLLAPSE_SIMULATION:
                // Configuración intensiva
                gaConfig.setInt("populationSize", 40);
                gaConfig.setInt("generations", 25);
                gaConfig.setDouble("mutationRate", 0.1);
                gaConfig.setBoolean("parallelEnabled", true);
                gaConfig.setInt("parallelMinProcessors", 3);
                gaConfig.setInt("parallelPopulationThreshold", 40);
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
         * Llamado periodicamente mientras avanza el reloj simulado.
         */
        void onStorageUpdated(SimulationStatus status, Solution solution);

        /**
         * Llamado cuando la simulación termina (por fin de datos, colapso o stop manual).
         * @param status Estado final
         */
        void onSimulationFinished(SimulationStatus status);
    }

    /**
     * Duerme por el tiempo indicado pero revisa running/paused cada segundo,
     * permitiendo cancelación o pausa inmediata durante las esperas largas (Ta, Sa).
     */
    private void sleepInterruptibly(long totalMs) throws InterruptedException {
        long remaining = totalMs;
        while (remaining > 0 && running.get()) {
            // Si está pausado, no consumir el tiempo de espera
            while (paused.get() && running.get()) {
                Thread.sleep(100);
            }
            if (!running.get()) return;

            long chunk = Math.min(remaining, 1000);
            Thread.sleep(chunk);
            remaining -= chunk;
        }
    }

    private void sleepUntilNextCycle(
            long totalMs,
            long simStartRealMs,
            long simStartSimMs,
            ScenarioType scenario) throws InterruptedException {
        long remaining = totalMs;
        while (remaining > 0 && running.get()) {
            while (paused.get() && running.get()) {
                Thread.sleep(100);
            }
            if (!running.get()) return;

            long chunk = Math.min(remaining, 1000);
            Thread.sleep(chunk);
            remaining -= chunk;

            updateSimulatedClock(simStartRealMs, simStartSimMs, scenario);
            if (listener != null) {
                try {
                    listener.onStorageUpdated(buildLightweightStatus(), currentSolution);
                } catch (Exception e) {
                    System.err.println("⚠️ Error notificando inventario: " + e.getMessage());
                }
            }
        }
    }

    private void updateSimulatedClock(long simStartRealMs, long simStartSimMs, ScenarioType scenario) {
        long realElapsedMs = System.currentTimeMillis() - simStartRealMs;
        long simElapsedMs = realElapsedMs * scenario.getK();
        simulatedTime = java.time.Instant.ofEpochMilli(simStartSimMs + simElapsedMs)
            .atZone(startDate != null ? startDate.getZone() : java.time.ZoneOffset.UTC);

        if (startDate != null) {
            long minutesElapsed = java.time.Duration.between(startDate, simulatedTime).toMinutes();
            daysElapsed = Math.min(5.0, minutesElapsed / (24.0 * 60.0));
        }
    }

    private SimulationStatus buildLightweightStatus() {
        return new SimulationStatus(
            running.get(),
            paused.get(),
            currentCycle,
            simulatedTime,
            batchesProcessed,
            batchesFailed,
            currentSolution.getFitness(),
            CollapseLevel.NORMAL
        );
    }
}
