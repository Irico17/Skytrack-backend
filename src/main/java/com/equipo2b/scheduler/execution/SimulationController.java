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
    private static final long STORAGE_UPDATE_INTERVAL_MS = 250L;
    /** Cola inicial máxima de lotes reales para el escenario de colapso (decisión PO). */
    private static final int COLLAPSE_INITIAL_QUEUE_CAP = 1000;
    /** Relleno de capacidad por sub-lotes (split en vuelos directos). Aditivo y seguro. */
    private static final boolean PARTIAL_FILL_ENABLED = true;
    /** Días de crecimiento generado sobre la semilla en el escenario de colapso. */
    private static final int COLLAPSE_GROWTH_DAYS = 5;
    
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
    private volatile ZonedDateTime simulatedClockHorizon;
    private volatile Thread storageUpdateThread;
    private volatile String lastErrorMessage;
    private volatile boolean completedNaturally;

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

    // Condiciones del colapso (cuándo, qué lo provocó y por qué) capturadas al detectarlo.
    private volatile CollapseInfo collapseInfo;
    
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
        
        com.equipo2b.scheduler.logic.RouteGenerator fillRouteGen = PARTIAL_FILL_ENABLED
            ? new com.equipo2b.scheduler.logic.RouteGenerator(flightPlan, airportManager)
            : null;
        this.scheduler = SchedulerFactory.createGATSScheduler(
            ga, tabu, queue, evaluator, validator,
            scenario.getTa(), scenario.getSa(), scenario.getK(),
            flightPlan, PARTIAL_FILL_ENABLED, fillRouteGen
        );
        
        // Inicializar estado
        this.simulatedTime = startDate != null
            ? startDate
            : (currentBatches.isEmpty() ? ZonedDateTime.now() : currentBatches.get(0).ingressTime());
        this.simulatedClockHorizon = this.simulatedTime;
        this.storageUpdateThread = null;
        this.lastErrorMessage = null;
        this.completedNaturally = false;
        this.currentCycle = 0;
        this.batchesProcessed = 0;
        this.batchesFailed = 0;
        this.collapseInfo = null;
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
    public ReplanResult registerCancellation(String flightId) {
        // 1. Validar que hay simulación activa
        if (!running.get()) {
            throw new IllegalStateException("No hay simulación activa");
        }
        
        System.out.println("\n" + "=".repeat(80));
        System.out.println("🚫 CANCELACIÓN DE VUELO: " + flightId);
        System.out.println("=".repeat(80));
        
        // 2. Buscar vuelo por ID
        Flight cancelledFlight = findFlightById(flightId);
        if (cancelledFlight == null) {
            System.out.println("❌ Error: Vuelo no encontrado: " + flightId);
            System.out.println("=".repeat(80));
            throw new IllegalArgumentException("Vuelo no encontrado: " + flightId);
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
            throw new IllegalArgumentException("No se puede cancelar: el vuelo ya despegó");
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
        this.batchesProcessed = currentSolution.getRoutes().size();
        
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

        if (listener != null) {
            try {
                listener.onCycleCompleted(getStatus(), currentSolution);
            } catch (Exception e) {
                System.err.println("⚠️ Error notificando replanificación: " + e.getMessage());
            }
        }

        return result;
    }
    
    /**
     * Busca un vuelo por su ID en el plan de vuelos.
     * 
     * @param flightId ID del vuelo a buscar
     * @return Vuelo encontrado o null si no existe
     */
    private Flight findFlightById(String flightId) {
        String baseId = flightId;
        Long dayOffset = null;

        int suffixIndex = flightId.lastIndexOf("-D");
        if (suffixIndex > 0 && suffixIndex + 2 < flightId.length()) {
            String candidateOffset = flightId.substring(suffixIndex + 2);
            try {
                dayOffset = Long.parseLong(candidateOffset);
                baseId = flightId.substring(0, suffixIndex);
            } catch (NumberFormatException ignored) {
                dayOffset = null;
                baseId = flightId;
            }
        }

        for (Flight flight : flightPlan.getAllFlights()) {
            if (flight.flightId().equals(baseId)) {
                if (dayOffset != null) {
                    return new Flight(
                        flightId,
                        flight.origin(),
                        flight.destination(),
                        flight.departureTime().plusDays(dayOffset),
                        flight.arrivalTime().plusDays(dayOffset),
                        flight.capacity(),
                        flight.type()
                    );
                }
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
            ZonedDateTime planningCursor = simulatedTime;
            ZonedDateTime simulationEndTime = scenario == ScenarioType.PERIOD_SIMULATION
                ? planningCursor.plusDays(5)
                : null;

            simulatedClockHorizon = planningCursor;
            startStorageUpdateLoop(simStartRealMs, simStartSimMs, scenario);

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
                ZonedDateTime cyclePlanningTime = planningCursor;
                ZonedDateTime cycleHorizon = cyclePlanningTime.plusMinutes(scenario.getSc());
                simulatedClockHorizon = cycleHorizon;
                
                // 1. Ejecutar algoritmo sobre una ventana de consumo discreta y secuencial.
                //    Si el algoritmo se demora, el siguiente ciclo no salta datos: continúa
                //    desde cycleHorizon, no desde el reloj de pared.
                currentSolution = scheduler.executePlanningCycle(cyclePlanningTime);
                long algorithmMs = System.currentTimeMillis() - cycleStartRealMs;
                planningCursor = cycleHorizon;
                if (currentCycle == 1) {
                    System.out.printf("⏱️ [arranque] primer ciclo de planificación listo en %d ms (algoritmo)%n", algorithmMs);
                }
                logResourceUsage(scenario, algorithmMs);

                // Actualizar estadísticas
                updateBatchCounters(planningCursor);

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

                // Nota: el algoritmo ahora respeta un presupuesto de tiempo duro (deadline=Ta),
                // así que el "colapso por complejidad algorítmica" deja de ser un gatillo válido
                // (daría falsos positivos). El colapso se declara solo por SATURACIÓN logística.
                if (scenario == ScenarioType.COLLAPSE_SIMULATION
                        && algorithmMs > scenario.getTa() * 60_000L * 3) {
                    System.out.println("\n⚠️  Ciclo de colapso muy lento (>3×Ta) — posible sobrecarga de la VM");
                }

                if (scenario == ScenarioType.COLLAPSE_SIMULATION) {
                    int[] sat = evaluateWarehouseSaturation(); // [críticos, total]
                    if (sat[1] > 0 && sat[0] * 2 > sat[1]) {
                        System.out.println("\n⚠️  COLAPSO POR SATURACIÓN DE ALMACENES - más del 50% de aeropuertos críticos");
                        this.collapseInfo = new CollapseInfo(
                            "WAREHOUSE_SATURATION",
                            "Saturación de la red de almacenes",
                            String.format("Se consideró colapso porque %d de %d aeropuertos (%.0f%%) superaron el 90%% "
                                + "de su capacidad de almacén simultáneamente, dejando la red sin espacio para recibir más maletas.",
                                sat[0], sat[1], sat[1] > 0 ? sat[0] * 100.0 / sat[1] : 0.0),
                            ZonedDateTime.now(),
                            simulatedTime,
                            collapseStatus.occupancyPercentage(),
                            collapseStatus.unserviceablePercentage(),
                            sat[0],
                            sat[1],
                            currentCycle
                        );
                        completedNaturally = true;
                        break;
                    }
                }

                if (collapseStatus.isCollapsed()) {
                    if (scenario == ScenarioType.COLLAPSE_SIMULATION) {
                        System.out.println("\n⚠️  COLAPSO DETECTADO - Deteniendo simulación");
                        this.collapseInfo = buildCollapseInfoFromStatus(collapseStatus);
                        completedNaturally = true;
                        break;
                    } else {
                        System.out.println("⚠️  Alerta de colapso (informativo) - la simulación continúa");
                    }
                }
                
                // 5. Condición de parada natural para simulación de 5 días.
                //    Usa el cursor planificado para completar toda la ventana sin saltos
                //    aunque la VM tarde más que Sa en algún ciclo.
                if (simulationEndTime != null && !planningCursor.isBefore(simulationEndTime)) {
                    simulatedClockHorizon = simulationEndTime;
                    simulatedTime = simulationEndTime;
                    daysElapsed = 5.0;
                    System.out.println("\n✅ Simulación de 5 días completada (días transcurridos: " 
                        + String.format("%.2f", daysElapsed) + ")");
                    completedNaturally = true;
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
                    sleepUntilNextCycle(remainingMs);
                } else {
                    System.out.printf("⚠️ Algoritmo tardó %.1fs (> Sa=%d min) — siguiente ciclo inmediato%n",
                        algorithmMs / 1000.0, scenario.getSa());
                }
            }
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("⚠️  Simulación interrumpida");
        } catch (Exception e) {
            lastErrorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            System.err.println("❌ Simulación falló: " + lastErrorMessage);
            e.printStackTrace();
        } finally {
            running.set(false);
            paused.set(false);
            if (storageUpdateThread != null) {
                storageUpdateThread.interrupt();
                storageUpdateThread = null;
            }
            state = SimulationState.STOPPED;
            System.out.println("\n✓ Simulación finalizada");
            printFinalSummary();

            // Notificar listener (WebSocket) al terminar
            if (listener != null) {
                try {
                    if (lastErrorMessage != null) {
                        listener.onSimulationError(getStatus(), lastErrorMessage);
                    } else if (completedNaturally) {
                        listener.onSimulationFinished(getStatus());
                    } else {
                        System.out.println("✓ Simulación detenida antes de completarse; no se exportan resultados finales");
                    }
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

    private void updateBatchCounters(ZonedDateTime plannedUntil) {
        int routed = currentSolution != null ? currentSolution.getRoutes().size() : 0;
        long delayed = currentSolution != null
            ? currentSolution.getRoutes().values().stream().filter(route -> !route.meetsSLA()).count()
            : 0;
        long released = currentBatches != null
            ? currentBatches.stream()
                .filter(batch -> !batch.ingressTime().isAfter(plannedUntil))
                .count()
            : routed;
        long releasedWithoutRoute = Math.max(0, released - routed);

        batchesProcessed = routed;
        batchesFailed = Math.toIntExact(Math.min(Integer.MAX_VALUE, delayed + releasedWithoutRoute));
    }

    /**
     * Evalúa la saturación de la red de almacenes.
     * @return arreglo [aeropuertos críticos (≥90% capacidad), aeropuertos evaluados].
     */
    private int[] evaluateWarehouseSaturation() {
        if (currentSolution == null || currentSolution.getRoutes().isEmpty() || simulatedTime == null) {
            return new int[]{0, 0};
        }

        StorageInventoryService inventoryService = new StorageInventoryService(airportManager);
        Map<Airport, Integer> currentBags = inventoryService.calculateCurrentBags(currentSolution, simulatedTime, currentBatches);
        if (currentBags.isEmpty()) {
            return new int[]{0, 0};
        }

        int criticalAirports = (int) currentBags.entrySet().stream()
            .filter(entry -> entry.getKey().storageCapacity() > 0
                && entry.getValue() >= entry.getKey().storageCapacity() * 0.90)
            .count();
        return new int[]{criticalAirports, currentBags.size()};
    }

    /**
     * Construye las condiciones del colapso a partir del estado del detector, traduciendo
     * la causa concreta (no atendibles / saturación de capacidad / fitness) a un motivo legible.
     */
    private CollapseInfo buildCollapseInfoFromStatus(CollapseStatus status) {
        int[] sat = evaluateWarehouseSaturation();
        String causeCode;
        String causeLabel;
        String reason;
        if (status.message() != null && status.message().toLowerCase().contains("fitness")) {
            causeCode = "ALGORITHM_FITNESS";
            causeLabel = "Penalizaciones superan recompensas";
            reason = "Se consideró colapso porque la solución dejó de ser viable: las penalizaciones "
                + "(retrasos y sobrecapacidad) superaron las recompensas por entregas a tiempo. " + status.message();
        } else if (status.unserviceablePercentage() > 20.0) {
            causeCode = "UNSERVICEABLE_BATCHES";
            causeLabel = "Demasiados lotes no atendibles";
            reason = String.format("Se consideró colapso porque el %.0f%% de los lotes no pudo planificarse a tiempo "
                + "(umbral de no atendibles: 20%%), con una ocupación promedio del sistema del %.0f%%.",
                status.unserviceablePercentage(), status.occupancyPercentage());
        } else if (status.occupancyPercentage() >= 80.0) {
            causeCode = "CAPACITY_SATURATION";
            causeLabel = "Saturación de capacidad de la red";
            reason = String.format("Se consideró colapso porque la ocupación promedio del sistema alcanzó el %.0f%% "
                + "(umbral de saturación: 80%%), superando la capacidad operable de vuelos y almacenes.",
                status.occupancyPercentage());
        } else {
            causeCode = "ALGORITHM_FITNESS";
            causeLabel = "Penalizaciones superan recompensas";
            reason = String.format("Se consideró colapso porque la solución dejó de ser viable: las penalizaciones "
                + "(retrasos/sobrecapacidad) superaron las recompensas. %s", status.message());
        }
        return new CollapseInfo(
            causeCode, causeLabel, reason,
            ZonedDateTime.now(), simulatedTime,
            status.occupancyPercentage(), status.unserviceablePercentage(),
            sat[0], sat[1], currentCycle
        );
    }

    /** Condiciones del colapso si la simulación colapsó; null si no hubo colapso. */
    public CollapseInfo getCollapseInfo() {
        return collapseInfo;
    }

    private void releaseHeavyState(ScenarioType scenario) {
        // Los tres escenarios liberan el motor pesado al terminar; la última solución
        // y los lotes quedan disponibles para consulta REST / exportación.
        scheduler = null;
        tabuSearch = null;
        validator = null;
        System.out.println("✓ Referencias pesadas liberadas tras finalizar " + scenario.name()
            + "; última solución y lotes preservados para consulta REST");
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
                // Semilla real acotada como cola inicial (cap 1000) + crecimiento generado
                // que empuja la red hacia la saturación. La semilla llega ya recortada
                // en streaming (≤50k) desde SimulationService; aquí limitamos la cola inicial.
                List<ShipmentBatch> collapseBase = historical.isEmpty()
                    ? historical
                    : new ArrayList<>(historical.subList(0, Math.min(COLLAPSE_INITIAL_QUEUE_CAP, historical.size())));
                List<ShipmentBatch> collapseAll = new ArrayList<>(collapseBase);
                if (!collapseBase.isEmpty()) {
                    // Crecimiento (factor 1.23) sobre la semilla recortada — barato y suficiente
                    // para provocar el colapso por saturación/complejidad algorítmica.
                    ShipmentGenerator collapseGenerator = new ShipmentGenerator();
                    List<ShipmentBatch> collapseBatches = collapseGenerator.generateFutureShipments(
                        collapseBase, COLLAPSE_GROWTH_DAYS, 1.23
                    );
                    collapseAll.addAll(collapseBatches);
                }
                return collapseAll;
                
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
                gaConfig.setInt("populationSize", 20);
                gaConfig.setInt("generations", 10);
                gaConfig.setDouble("mutationRate", 0.1);
                gaConfig.setInt("stagnationLimit", 4);
                gaConfig.setBoolean("parallelEnabled", false);
                gaConfig.setInt("routeSearchAttempts", 10);
                gaConfig.setInt("routeCachedVariants", 3);
                tabuConfig.setInt("maxIterations", 32);
                tabuConfig.setInt("tabuTenure", 12);
                tabuConfig.setInt("neighborhoodSize", 8);
                tabuConfig.setInt("routeSearchAttempts", 8);
                tabuConfig.setInt("routeCachedVariants", 2);
                break;
                
            case COLLAPSE_SIMULATION:
                // Mismos parámetros/velocidad que PERIOD (decisión PO) y config liviana
                // apta para VM 2 CPU / 2 GB; el deadline duro garantiza Ta.
                gaConfig.setInt("populationSize", 20);
                gaConfig.setInt("generations", 10);
                gaConfig.setDouble("mutationRate", 0.1);
                gaConfig.setInt("stagnationLimit", 4);
                gaConfig.setBoolean("parallelEnabled", false);
                gaConfig.setInt("routeSearchAttempts", 10);
                gaConfig.setInt("routeCachedVariants", 3);
                tabuConfig.setInt("maxIterations", 32);
                tabuConfig.setInt("tabuTenure", 12);
                tabuConfig.setInt("neighborhoodSize", 8);
                tabuConfig.setInt("routeSearchAttempts", 8);
                tabuConfig.setInt("routeCachedVariants", 2);
                break;
        }

        // Presupuesto de tiempo duro por ciclo (deadline): el algoritmo nunca excede Ta.
        // GA ~70% y Tabú ~25% de Ta (5% de margen para evaluación/acumulación).
        long taMs = scenario.getTa() * 60_000L;
        gaConfig.setInt("maxTimeMillis", (int) Math.round(taMs * 0.70));
        tabuConfig.setInt("maxTimeMillis", (int) Math.round(taMs * 0.25));

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
         * Llamado cuando la simulación termina por un error no recuperable.
         */
        void onSimulationError(SimulationStatus status, String errorMessage);

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

    private void sleepUntilNextCycle(long totalMs) throws InterruptedException {
        sleepInterruptibly(totalMs);
    }

    private void startStorageUpdateLoop(long simStartRealMs, long simStartSimMs, ScenarioType scenario) {
        storageUpdateThread = new Thread(() -> {
            while (running.get()) {
                try {
                    while (paused.get() && running.get()) {
                        Thread.sleep(100);
                    }
                    if (!running.get()) return;

                    updateSimulatedClock(simStartRealMs, simStartSimMs, scenario);
                    notifyStorageUpdated();
                    Thread.sleep(STORAGE_UPDATE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "simulation-storage-updates-" + simulationId);
        storageUpdateThread.setDaemon(true);
        storageUpdateThread.start();
    }

    private void notifyStorageUpdated() {
        if (listener == null) return;
        try {
            listener.onStorageUpdated(buildLightweightStatus(), currentSolution);
        } catch (Exception e) {
            System.err.println("⚠️ Error notificando inventario: " + e.getMessage());
        }
    }

    private void updateSimulatedClock(long simStartRealMs, long simStartSimMs, ScenarioType scenario) {
        long realElapsedMs = System.currentTimeMillis() - simStartRealMs;
        long simElapsedMs = realElapsedMs * scenario.getK();
        ZonedDateTime wallClockTime = java.time.Instant.ofEpochMilli(simStartSimMs + simElapsedMs)
            .atZone(startDate != null ? startDate.getZone() : java.time.ZoneOffset.UTC);

        ZonedDateTime horizon = simulatedClockHorizon;
        if (horizon != null && wallClockTime.isAfter(horizon)) {
            wallClockTime = horizon;
        }

        simulatedTime = wallClockTime;

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
