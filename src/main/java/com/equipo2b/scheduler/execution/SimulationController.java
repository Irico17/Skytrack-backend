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
    /** Intervalo normal de STORAGE_UPDATE (reloj + inventario). */
    private static final long STORAGE_UPDATE_INTERVAL_MS = 1_000L;
    /**
     * Durante executePlanningCycle el hilo de inventario NO debe competir con el GA/Tabú
     * en VMs de 1 CPU: cada calculateCurrentBags + métricas + JSON robaba el núcleo y
     * alargaba el ciclo a minutos. Solo tick de reloj liviano.
     */
    private static final long STORAGE_UPDATE_INTERVAL_PLANNING_MS = 2_000L;
    /** Relleno de capacidad por sub-lotes (split en vuelos directos). Aditivo y seguro. */
    private static final boolean PARTIAL_FILL_ENABLED = true;
    /**
     * Tamaño de cada bloque de carga incremental para el escenario de colapso, en días
     * reales de calendario. En vez de precargar TODO el volumen (semilla + crecimiento de
     * varios días) de una sola vez —lo que antes disparaba el ciclo 1 con una ráfaga enorme
     * y arriesgaba quedarse sin memoria—, colapso carga solo este bloque al arrancar y va
     * pidiendo el siguiente a medida que el reloj simulado se acerca al borde de lo cargado.
     * Esto permite que colapso corra indefinidamente (hasta que el backend detecte colapso
     * real) sin ningún tope arbitrario de cantidad de registros.
     */
    private static final int COLLAPSE_CHUNK_DAYS = 2;
    /** Margen de aviso: se pide el siguiente bloque cuando falta esto para agotar el actual. */
    private static final int COLLAPSE_REFILL_MARGIN_DAYS = 1;
    /** Factor de crecimiento aplicado sobre cada bloque real cargado (Requisito 29.2). */
    private static final double COLLAPSE_GROWTH_FACTOR = 1.23;
    /**
     * Carga por bloques también para la simulación de 5 DÍAS: bloques de 1 día con margen
     * de medio día. El primer ciclo arranca tras cargar SOLO el día 1 (~17-21K lotes en
     * época pico vs ~85K de los 5 días completos) y el resto se pide durante la simulación
     * — mismo mecanismo que colapso, pero sin factor de crecimiento y acotado a 5 días.
     */
    private static final int PERIOD_CHUNK_DAYS = 1;
    private static final int PERIOD_REFILL_MARGIN_HOURS = 12;
    /**
     * Umbral de sobrecarga SEVERA por aeropuerto (120% de su capacidad de almacén) y
     * cantidad mínima de aeropuertos en ese estado para declarar colapso. Complementa
     * evaluateWarehouseSaturation (que exige más del 50% de TODA la red): un puñado de
     * hubs importantes desbordados muy por encima del 100% (ej. varios entre 120% y 172%,
     * visto en pruebas reales) representa un colapso real aunque sean pocos frente al total
     * de 30 aeropuertos — sin este chequeo, ese escenario nunca disparaba colapso.
     */
    private static final double SEVERE_OVERLOAD_RATIO = 1.20;
    private static final int SEVERE_OVERLOAD_MIN_AIRPORTS = 3;

    // Componentes del sistema
    private final FlightPlan flightPlan;
    private final AirportManager airportManager;
    private final ClientRegistry clientRegistry;
    
    // Estado de la simulación
    private SimulationState state;
    private Thread simulationThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    /** True mientras corre GA/Tabú/split del ciclo actual (el hilo de storage se aligera). */
    private final AtomicBoolean planningInProgress = new AtomicBoolean(false);
    
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
    /** Número de ciclos COMPLETADOS. Durante el primer planning permanece en 0. */
    private volatile int currentCycle = 0;
    private ZonedDateTime simulatedTime;
    private int batchesProcessed = 0;
    private int batchesFailed = 0;
    
    // Almacena los lotes procesados para persistencia final
    private List<ShipmentBatch> currentBatches;

    // Fecha de inicio para calcular días transcurridos
    private ZonedDateTime startDate;
    private volatile double daysElapsed = 0.0;

    // Carga incremental por bloques de fecha real para COLAPSO y 5 DÍAS (evita precargar
    // todo el volumen de una sola vez). null para día a día (no usa dataset histórico).
    private java.util.function.BiFunction<ZonedDateTime, ZonedDateTime, List<ShipmentBatch>> chunkLoader;
    private ZonedDateTime chunkLoadedHorizon;
    private List<ShipmentBatch> collapseLastNonEmptyChunk;
    /** Tope de carga por bloques (solo 5 días: inicio + 5 días). Null = sin tope (colapso). */
    private ZonedDateTime chunkLoadEndBound;

    // Condiciones del colapso (cuándo, qué lo provocó y por qué) capturadas al detectarlo.
    private volatile CollapseInfo collapseInfo;

    // Componentes REUTILIZABLES (antes se instanciaban por ciclo/consulta):
    // - CollapseDetector es puro (solo umbrales inmutables) → una instancia basta.
    // - StorageInventoryService cachea internamente los eventos ordenados por solución;
    //   crear uno nuevo por llamada tiraba ese cache y re-ordenaba O(E log E) cada vez.
    //   Reutilizarlo elimina ese allocation rate en la Young Gen y aprovecha el cache.
    private final CollapseDetector collapseDetector;
    private volatile StorageInventoryService inventoryService;

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
        // CapacityMonitor real (ocupación de vuelos genuina) en vez del fallback basado
        // en fitness — evita que la ocupación reportada/usada para colapso sea una
        // estimación indirecta y ruidosa del fitness de un solo ciclo.
        this.collapseDetector = new CollapseDetector(new CapacityMonitor(this.flightPlan, this.airportManager));
    }

    /**
     * Define la función que carga un bloque de envíos reales por rango de fecha
     * [start, end). Debe llamarse ANTES de {@link #startSimulation} para
     * {@link ScenarioType#COLLAPSE_SIMULATION} y {@link ScenarioType#PERIOD_SIMULATION}
     * — permite cargar la semilla y los refuerzos posteriores en bloques pequeños en
     * vez de todo de una vez (arranque mucho más rápido).
     */
    public void setChunkLoader(
            java.util.function.BiFunction<ZonedDateTime, ZonedDateTime, List<ShipmentBatch>> loader) {
        this.chunkLoader = loader;
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
            scenario.getTaSeconds(), scenario.getSaSeconds(), scenario.getK(),
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
        this.inventoryService = new StorageInventoryService(airportManager);
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
        CollapseStatus collapseStatus = collapseDetector.evaluateCollapse(
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
            // El reloj se ancla DESPUÉS de terminar el primer ciclo. Mientras se prepara
            // la solución inicial la UI permanece en "Preparando…" y el tiempo simulado
            // sigue exactamente en startDate.
            long simClockStartRealMs = -1L;
            long simStartSimMs = simulatedTime.toInstant().toEpochMilli();
            ZonedDateTime planningCursor = simulatedTime;
            ZonedDateTime simulationEndTime = scenario == ScenarioType.PERIOD_SIMULATION
                ? planningCursor.plusDays(5)
                : null;

            // Después del warm-up, el reloj de pantalla sigue wall×K y no se ata a la
            // ventana Sc del ciclo en curso. Así no se congela durante un planning largo.
            simulatedClockHorizon = simulationEndTime;

            while (running.get()) {
                // Esperar si está pausado
                while (paused.get() && running.get()) {
                    Thread.sleep(100);
                }
                
                if (!running.get()) break;
                
                // =========== INICIO DEL CICLO ===========
                int cycleNumber = currentCycle + 1;
                long cycleStartRealMs = System.currentTimeMillis();
                System.out.println("\n--- CICLO " + cycleNumber + " ---");
                ZonedDateTime cyclePlanningTime = planningCursor;
                ZonedDateTime cycleHorizon = cyclePlanningTime.plusMinutes(scenario.getSc());
                
                // 0b. Línea base de almacenes: ocupación REAL de cada aeropuerto al inicio de
                //     la ventana, según las rutas YA planificadas en ciclos anteriores. Sin
                //     esto, el GA/Tabú evaluaba cada ciclo en el vacío (un hub casi lleno por
                //     rutas previas parecía vacío) y seguía concentrando carga hasta el
                //     colapso. Con la base, el desborde duro y el balanceo convexo del
                //     evaluador ven la ocupación absoluta.
                if (cycleNumber > 1 && inventoryService != null && currentSolution != null
                        && !currentSolution.getRoutes().isEmpty()) {
                    scheduler.setStorageBaseline(inventoryService.calculateCurrentBags(
                        currentSolution, cyclePlanningTime, currentBatches));
                }

                // 1. Ejecutar algoritmo sobre una ventana de consumo discreta y secuencial.
                //    Si el algoritmo se demora, el siguiente ciclo no salta datos: continúa
                //    desde cycleHorizon, no desde el reloj de pared.
                planningInProgress.set(true);
                try {
                    currentSolution = scheduler.executePlanningCycle(cyclePlanningTime);
                } finally {
                    planningInProgress.set(false);
                }
                // Publicar el número solo cuando el ciclo está completo. Esto evita que
                // /status haga creer al frontend que ya existe CYCLE_UPDATE durante el warm-up.
                currentCycle = cycleNumber;
                long algorithmMs = System.currentTimeMillis() - cycleStartRealMs;
                planningCursor = cycleHorizon;
                if (cycleNumber == 1) {
                    System.out.printf("⏱️ [arranque] primer ciclo de planificación listo en %d ms (algoritmo)%n", algorithmMs);
                }
                logResourceUsage(scenario, algorithmMs);

                // Actualizar estadísticas
                updateBatchCounters(planningCursor);

                // 1b. Colapso y 5 días: si el reloj de planificación se acerca al borde de lo
                //     cargado, pedir el siguiente bloque de datos reales e inyectarlo a la cola
                //     en marcha — colapso corre indefinidamente; 5 días se acota a su ventana.
                if (chunkLoader != null && chunkLoadedHorizon != null) {
                    ZonedDateTime refillTrigger = scenario == ScenarioType.COLLAPSE_SIMULATION
                        ? chunkLoadedHorizon.minusDays(COLLAPSE_REFILL_MARGIN_DAYS)
                        : chunkLoadedHorizon.minusHours(PERIOD_REFILL_MARGIN_HOURS);
                    if (!planningCursor.isBefore(refillTrigger)) {
                        refillChunk(scenario);
                    }
                }

                // El ciclo 1 es warm-up: su frame se publica exactamente en startDate.
                // Desde el ciclo 2, el reloj ya está anclado al final del warm-up.
                if (simClockStartRealMs >= 0) {
                    updateSimulatedClock(simClockStartRealMs, simStartSimMs, scenario);
                }

                // 3. Verificar colapso (instancia compartida — el detector es sin estado)
                CollapseStatus collapseStatus = collapseDetector.evaluateCollapse(
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

                // Arrancar reloj/ticks SOLO después de que el frontend recibió el primer
                // CYCLE_UPDATE. El siguiente ciclo se agenda Sa segundos desde este instante.
                if (cycleNumber == 1) {
                    simClockStartRealMs = System.currentTimeMillis();
                    startStorageUpdateLoop(simClockStartRealMs, simStartSimMs, scenario);
                }

                // Nota: el algoritmo ahora respeta un presupuesto de tiempo duro (deadline=Ta),
                // así que el "colapso por complejidad algorítmica" deja de ser un gatillo válido
                // (daría falsos positivos). El colapso se declara solo por SATURACIÓN logística.
                if (scenario == ScenarioType.COLLAPSE_SIMULATION
                        && algorithmMs > scenario.getTaSeconds() * 1000L * 3) {
                    System.out.println("\n⚠️  Ciclo de colapso muy lento (>3×Ta) — posible sobrecarga de la VM");
                }

                if (scenario == ScenarioType.COLLAPSE_SIMULATION) {
                    List<Airport> severeAirports = evaluateSevereOverload();
                    if (severeAirports.size() >= SEVERE_OVERLOAD_MIN_AIRPORTS) {
                        String names = severeAirports.stream().map(Airport::id)
                            .collect(java.util.stream.Collectors.joining(", "));
                        System.out.println("\n⚠️  COLAPSO POR SOBRECARGA SEVERA - " + severeAirports.size()
                            + " aeropuertos por encima del " + (int) (SEVERE_OVERLOAD_RATIO * 100) + "% de capacidad: " + names);
                        this.collapseInfo = new CollapseInfo(
                            "SEVERE_OVERLOAD",
                            "Sobrecarga severa en varios aeropuertos",
                            String.format("Se consideró colapso porque %d aeropuertos (%s) superaron el %.0f%% de su "
                                + "capacidad de almacén simultáneamente — aunque no lleguen a ser la mitad de la red, "
                                + "esos hubs ya no pueden recibir más carga.",
                                severeAirports.size(), names, SEVERE_OVERLOAD_RATIO * 100),
                            ZonedDateTime.now(),
                            simulatedTime,
                            collapseStatus.occupancyPercentage(),
                            collapseStatus.unserviceablePercentage(),
                            severeAirports.size(),
                            airportManager.getAllAirports().size(),
                            currentCycle
                        );
                        completedNaturally = true;
                        break;
                    }

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

                // 6. Esperar Sa segundos reales desde el INICIO del ciclo (no desde el fin del algoritmo)
                //    Esto mantiene el ritmo constante: ciclos cada Sa segundos reales
                long saMs = scenario.getSaSeconds() * 1000L;
                long cadenceStartRealMs = cycleNumber == 1 ? simClockStartRealMs : cycleStartRealMs;
                long elapsedInCycleMs = System.currentTimeMillis() - cadenceStartRealMs;
                long remainingMs = saMs - elapsedInCycleMs;

                if (remainingMs > 0) {
                    System.out.printf("⏳ Algoritmo terminó en %.1fs — esperando %.1fs hasta el siguiente ciclo (Sa=%ds)%n",
                        algorithmMs / 1000.0, remainingMs / 1000.0, scenario.getSaSeconds());
                    sleepUntilNextCycle(remainingMs);
                } else {
                    System.out.printf("⚠️ Algoritmo tardó %.1fs (> Sa=%ds) — siguiente ciclo inmediato%n",
                        algorithmMs / 1000.0, scenario.getSaSeconds());
                }
            }
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("⚠️  Simulación interrumpida");
        } catch (OutOfMemoryError e) {
            // Error no recuperable, pero sí debe llegar como SIMULATION_ERROR al frontend.
            // Antes quedaba como un STOP silencioso porque Exception no captura Error.
            lastErrorMessage = "Memoria insuficiente durante la planificación del ciclo "
                + (currentCycle + 1);
            System.err.println("❌ " + lastErrorMessage);
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
        Map<String, AssignedRoute> routes = currentSolution != null
            ? currentSolution.getRoutes()
            : Map.of();
        int routed = routes.size();
        long delayed = routes.values().stream().filter(route -> !route.meetsSLA()).count();

        // "Sin ruta" debe contar LOTES ORIGINALES distintos sin ninguna ruta — no
        // "released - routed" (unidades distintas: released cuenta lotes originales,
        // routed cuenta ENTRADAS del mapa de solución, que se inflan con los sub-lotes
        // -S1/-S2 de applyCapacityAwareSplitting). Esa resta podía enmascarar lotes
        // realmente sin ruta con Math.max(0, ...) cuando había suficientes divisiones.
        Set<String> routedBaseIds = routedBaseIds(routes);
        long releasedWithoutRoute = currentBatches != null
            ? currentBatches.stream()
                .filter(batch -> !batch.ingressTime().isAfter(plannedUntil))
                .filter(batch -> !routedBaseIds.contains(batch.batchId()))
                .count()
            : 0;

        batchesProcessed = routed;
        batchesFailed = Math.toIntExact(Math.min(Integer.MAX_VALUE, delayed + releasedWithoutRoute));
    }

    /**
     * Ids base (sin sufijo "-S&lt;n&gt;") de todos los lotes que tienen AL MENOS una ruta
     * en la solución, incluyendo los que solo quedaron parcialmente ubicados vía sub-lotes.
     */
    private static Set<String> routedBaseIds(Map<String, AssignedRoute> routes) {
        Set<String> ids = new HashSet<>();
        for (String key : routes.keySet()) {
            ids.add(stripSplitSuffix(key));
        }
        return ids;
    }

    /** Quita los sufijos "-S&lt;n&gt;" finales de un id de lote (mismo criterio que Scheduler). */
    private static String stripSplitSuffix(String id) {
        String s = id;
        while (true) {
            int idx = s.lastIndexOf("-S");
            if (idx < 0 || idx + 2 >= s.length()) break;
            String suffix = s.substring(idx + 2);
            if (!suffix.chars().allMatch(Character::isDigit)) break;
            s = s.substring(0, idx);
        }
        return s;
    }

    /**
     * Evalúa la saturación de la red de almacenes.
     * @return arreglo [aeropuertos críticos (≥90% capacidad), aeropuertos evaluados].
     */
    private int[] evaluateWarehouseSaturation() {
        if (currentSolution == null || currentSolution.getRoutes().isEmpty() || simulatedTime == null) {
            return new int[]{0, 0};
        }

        StorageInventoryService inventory = inventoryService != null
            ? inventoryService
            : new StorageInventoryService(airportManager);
        Map<Airport, Integer> currentBags = inventory.calculateCurrentBags(currentSolution, simulatedTime, currentBatches);
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
     * Devuelve los aeropuertos con sobrecarga SEVERA (≥ {@link #SEVERE_OVERLOAD_RATIO} de
     * su capacidad de almacén) en el instante simulado actual — no solo "casi llenos", sino
     * genuinamente desbordados.
     */
    private List<Airport> evaluateSevereOverload() {
        if (currentSolution == null || currentSolution.getRoutes().isEmpty() || simulatedTime == null) {
            return List.of();
        }

        StorageInventoryService inventory = inventoryService != null
            ? inventoryService
            : new StorageInventoryService(airportManager);
        Map<Airport, Integer> currentBags = inventory.calculateCurrentBags(currentSolution, simulatedTime, currentBatches);

        return currentBags.entrySet().stream()
            .filter(entry -> entry.getKey().storageCapacity() > 0
                && entry.getValue() >= entry.getKey().storageCapacity() * SEVERE_OVERLOAD_RATIO)
            .map(Map.Entry::getKey)
            .toList();
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
        inventoryService = null; // libera el cache de eventos ordenados (puede ser grande)
        System.out.println("✓ Referencias pesadas liberadas tras finalizar " + scenario.name()
            + "; última solución y lotes preservados para consulta REST");
    }
    
    /**
     * Prepara los datos según el escenario.
     */
    private List<ShipmentBatch> prepareData(ScenarioType scenario, List<ShipmentBatch> historical) {
        switch (scenario) {
            case DAY_TO_DAY:
                // Indicación del curso (prueba de operaciones día a día): "La tabla de envíos
                // debe estar limpia. Aquí no aplica data histórica ni la data proyectada".
                // Los envíos entran SOLO por registro manual (pantalla de ingreso) o por la
                // carga de archivo durante la ejecución — nunca del dataset de simulación.
                return List.of();
                
            case PERIOD_SIMULATION: {
                // Carga por bloques también aquí: el ciclo 1 arranca tras cargar SOLO el
                // primer día de datos; los 4 días restantes se piden durante la simulación
                // (el mismo mecanismo que colapso, sin crecimiento y acotado a 5 días).
                // Fallback: sin chunkLoader o sin fecha de inicio, usa lo precargado.
                if (chunkLoader == null || startDate == null) {
                    return historical;
                }
                ZonedDateTime chunkStart = startDate;
                ZonedDateTime chunkEnd = chunkStart.plusDays(PERIOD_CHUNK_DAYS);
                chunkLoadEndBound = chunkStart.plusDays(5);
                List<ShipmentBatch> firstChunk = chunkLoader.apply(chunkStart, chunkEnd);
                chunkLoadedHorizon = chunkEnd;
                return firstChunk;
            }

            case COLLAPSE_SIMULATION: {
                // Carga incremental por bloques de fecha real (COLLAPSE_CHUNK_DAYS días a la
                // vez) en vez de precargar TODO el volumen de golpe: evita la ráfaga enorme en
                // el ciclo 1 y el riesgo de memoria de materializar cientos de miles de lotes
                // de una sola vez. El primer bloque se carga aquí; los siguientes se piden
                // durante la simulación (ver runSimulation) a medida que el reloj avanza.
                ZonedDateTime chunkStart = startDate != null ? startDate : ZonedDateTime.now();
                ZonedDateTime chunkEnd = chunkStart.plusDays(COLLAPSE_CHUNK_DAYS);
                List<ShipmentBatch> firstChunk = chunkLoader != null
                    ? chunkLoader.apply(chunkStart, chunkEnd)
                    : historical;
                chunkLoadedHorizon = chunkEnd;
                chunkLoadEndBound = null; // colapso no tiene tope: corre hasta colapsar
                if (!firstChunk.isEmpty()) {
                    collapseLastNonEmptyChunk = firstChunk;
                }
                return buildCollapseBlock(firstChunk);
            }

            default:
                throw new IllegalArgumentException("Escenario desconocido: " + scenario);
        }
    }

    /**
     * Aplica el factor de crecimiento (Requisito 29.2) sobre un bloque real de envíos y
     * devuelve el bloque real + lo generado. Si el bloque real viene vacío (fecha fuera del
     * dataset), usa el último bloque real no vacío como base para el patrón/crecimiento, de
     * modo que el colapso siga escalando presión aunque los datos reales ya se hayan agotado.
     */
    private List<ShipmentBatch> buildCollapseBlock(List<ShipmentBatch> realChunk) {
        List<ShipmentBatch> base = !realChunk.isEmpty() ? realChunk : collapseLastNonEmptyChunk;
        List<ShipmentBatch> block = new ArrayList<>(realChunk);
        if (base != null && !base.isEmpty()) {
            ShipmentGenerator collapseGenerator = new ShipmentGenerator();
            List<ShipmentBatch> growthBatches = collapseGenerator.generateFutureShipments(
                base, COLLAPSE_CHUNK_DAYS, COLLAPSE_GROWTH_FACTOR
            );
            block.addAll(growthBatches);
        }
        return block;
    }

    /**
     * Pide el siguiente bloque de fecha real (colapso: + crecimiento; 5 días: tal cual,
     * acotado al fin de la ventana) y lo inyecta en la cola del scheduler EN MARCHA.
     * Se llama desde el bucle principal cuando el reloj simulado se acerca al borde
     * de lo ya cargado.
     */
    private void refillChunk(ScenarioType scenario) {
        ZonedDateTime nextStart = chunkLoadedHorizon;
        int chunkDays = scenario == ScenarioType.COLLAPSE_SIMULATION ? COLLAPSE_CHUNK_DAYS : PERIOD_CHUNK_DAYS;
        ZonedDateTime nextEnd = nextStart.plusDays(chunkDays);
        if (chunkLoadEndBound != null && nextEnd.isAfter(chunkLoadEndBound)) {
            nextEnd = chunkLoadEndBound;
        }
        if (!nextStart.isBefore(nextEnd)) {
            return; // 5 días: ya se cargó toda la ventana
        }

        List<ShipmentBatch> nextChunk = chunkLoader.apply(nextStart, nextEnd);
        List<ShipmentBatch> block;
        if (scenario == ScenarioType.COLLAPSE_SIMULATION) {
            if (!nextChunk.isEmpty()) {
                collapseLastNonEmptyChunk = nextChunk;
            }
            block = buildCollapseBlock(nextChunk);
        } else {
            block = nextChunk;
        }
        for (ShipmentBatch batch : block) {
            scheduler.addShipment(batch);
        }
        currentBatches.addAll(block);
        chunkLoadedHorizon = nextEnd;
        System.out.printf("➕ %s: bloque [%s → %s) cargado — %,d lotes reales%s (%,d total)%n",
            scenario == ScenarioType.COLLAPSE_SIMULATION ? "Colapso" : "5 días",
            nextStart.toLocalDate(), nextEnd.toLocalDate(), nextChunk.size(),
            scenario == ScenarioType.COLLAPSE_SIMULATION ? " + crecimiento" : "",
            block.size());
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
                // VM típica del curso: 1 CPU / 2 GB. Priorizar terminar dentro de Ta
                // y no saturar el único núcleo (sin paralelismo, búsqueda de rutas corta).
                gaConfig.setInt("populationSize", 10);
                gaConfig.setInt("generations", 5);
                gaConfig.setDouble("mutationRate", 0.15);
                gaConfig.setInt("stagnationLimit", 3);
                gaConfig.setBoolean("parallelEnabled", false);
                gaConfig.setInt("routeSearchAttempts", 4);
                gaConfig.setInt("routeCachedVariants", 2);
                // Medido (2028-11-01, ciclos de 900-1600 lotes): el GA poblacional NO cabe
                // en Ta=30s — se corta en la generación 0 y la asignación cae a 62%/9%/1%.
                // La heurística greedy capacity-aware asigna el 100% en 1.5-2s; el balanceo
                // de almacenes en pico queda a cargo del Tabú acotado (deadline 25% de Ta).
                gaConfig.setInt("largeVolumeBatchThreshold", 800);
                gaConfig.setDouble("firstCycleBudgetRatio", 0.30);
                tabuConfig.setInt("maxIterations", 16);
                tabuConfig.setInt("tabuTenure", 8);
                tabuConfig.setInt("neighborhoodSize", 4);
                tabuConfig.setInt("routeSearchAttempts", 4);
                tabuConfig.setInt("routeCachedVariants", 2);
                break;

            case COLLAPSE_SIMULATION:
                // Misma calibración liviana que PERIOD (1 CPU / 2 GB).
                gaConfig.setInt("populationSize", 10);
                gaConfig.setInt("generations", 5);
                gaConfig.setDouble("mutationRate", 0.15);
                gaConfig.setInt("stagnationLimit", 3);
                gaConfig.setBoolean("parallelEnabled", false);
                gaConfig.setInt("routeSearchAttempts", 4);
                gaConfig.setInt("routeCachedVariants", 2);
                gaConfig.setInt("largeVolumeBatchThreshold", 800);
                gaConfig.setDouble("firstCycleBudgetRatio", 0.30);
                tabuConfig.setInt("maxIterations", 16);
                tabuConfig.setInt("tabuTenure", 8);
                tabuConfig.setInt("neighborhoodSize", 4);
                tabuConfig.setInt("routeSearchAttempts", 4);
                tabuConfig.setInt("routeCachedVariants", 2);
                break;
        }

        // Presupuesto de tiempo duro por ciclo (deadline): el algoritmo nunca excede Ta.
        // GA ~70% y Tabú ~25% de Ta (5% de margen para evaluación/acumulación).
        long taMs = scenario.getTaSeconds() * 1000L;
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
        
        CollapseStatus collapseStatus = collapseDetector.evaluateCollapse(
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

                    boolean planning = planningInProgress.get();
                    updateSimulatedClock(simStartRealMs, simStartSimMs, scenario);
                    // Durante el GA: no recalcular inventario ni saturar el WS (1 CPU / buffer DROP).
                    // El reloj sí avanza vía frames livianos en onStorageUpdated.
                    notifyStorageUpdated(planning);
                    Thread.sleep(planning
                        ? STORAGE_UPDATE_INTERVAL_PLANNING_MS
                        : STORAGE_UPDATE_INTERVAL_MS);
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
        notifyStorageUpdated(false);
    }

    private void notifyStorageUpdated(boolean lightweight) {
        if (listener == null) return;
        try {
            if (lightweight && listener instanceof LightweightStorageAware aware) {
                aware.onStorageUpdatedLightweight(buildLightweightStatus());
            } else {
                listener.onStorageUpdated(buildLightweightStatus(), currentSolution);
            }
        } catch (Exception e) {
            System.err.println("⚠️ Error notificando inventario: " + e.getMessage());
        }
    }

    /**
     * Extensión opcional del listener: tick de reloj sin inventario O(rutas).
     * SimulationService la implementa para no matar el núcleo único durante el GA.
     */
    public interface LightweightStorageAware {
        void onStorageUpdatedLightweight(SimulationStatus status);
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
            // Milisegundos (no toMinutes(), que trunca a minutos enteros): con K=1 (día a día,
            // 1:1 con el reloj real) esa truncación congelaba los segundos del contador de
            // "tiempo transcurrido" hasta el siguiente minuto completo. Con K=180 (5 días/
            // colapso) era imperceptible (la pérdida de precisión es menor a 1/3 de segundo
            // real), pero para día a día se notaba de sobra.
            long millisElapsed = java.time.Duration.between(startDate, simulatedTime).toMillis();
            double rawDaysElapsed = millisElapsed / (24.0 * 60.0 * 60.0 * 1000.0);
            // El tope de 5 días (120h) solo aplica a PERIOD_SIMULATION, que tiene ventana fija.
            // COLLAPSE_SIMULATION corre hasta que el backend detecte colapso (puede superar los
            // 5 días); antes este Math.min(5.0, ...) universal congelaba el reloj del frontend
            // en 120h para TODOS los escenarios, dando la falsa impresión de que colapso se
            // detenía como la simulación de 5 días.
            daysElapsed = scenario == ScenarioType.PERIOD_SIMULATION
                ? Math.min(5.0, rawDaysElapsed)
                : rawDaysElapsed;
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
