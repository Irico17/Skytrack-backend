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
    /**
     * Intervalo normal de STORAGE_UPDATE (reloj + inventario).
     * 500 ms ≈ casi tiempo real en UI; con K=120 son ~1 min simulado por tick.
     * El recálculo es barato (cache de eventos por identidad de solución).
     */
    private static final long STORAGE_UPDATE_INTERVAL_MS = 500L;
    /**
     * Durante executePlanningCycle el GA/Tabú usa el otro core: espaciamos un poco más
     * (1 s) para no competir innecesariamente, sin volver al congelamiento visual.
     */
    private static final long STORAGE_UPDATE_INTERVAL_PLANNING_MS = 1_000L;
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
    /**
     * Factor de crecimiento (Requisito 29.2) aplicado SOLO cuando el dataset real se agota:
     * mientras haya envíos reales, colapso los consume tal cual (idéntico a 5 días, sin tope);
     * al agotarse, cada bloque sintético se proyecta sobre el anterior, así el crecimiento
     * compone 1.23ⁿ hasta que algún gatillo de colapso dispare (Requisito 29.4).
     */
    private static final double COLLAPSE_GROWTH_FACTOR = 1.23;
    /**
     * Carga por bloques también para la simulación de 5 DÍAS: bloques de 1 día con margen
     * de medio día. El primer ciclo arranca tras cargar SOLO el día 1 (~17-21K lotes en
     * época pico vs ~85K de los 5 días completos) y el resto se pide durante la simulación
     * — mismo mecanismo que colapso, pero sin factor de crecimiento y acotado a 5 días.
     */
    private static final int PERIOD_CHUNK_DAYS = 1;
    private static final int PERIOD_REFILL_MARGIN_HOURS = 12;
    // Componentes del sistema
    private final FlightPlan flightPlan;
    private final AirportManager airportManager;
    private final ClientRegistry clientRegistry;
    
    // Estado de la simulación
    private SimulationState state;
    private Thread simulationThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    /** True mientras corre GA/Tabú/split del ciclo actual (storage usa intervalo más largo). */
    private final AtomicBoolean planningInProgress = new AtomicBoolean(false);
    /**
     * Cierre atómico "primero en llegar, gana" entre las tres vías de detección de colapso:
     * el chequeo de fitness/ocupación del loop principal (una vez por ciclo) y los chequeos
     * de sobrecarga severa/saturación de almacén del hilo de storage (en vivo, cada 0.5–1 s,
     * ver {@link #checkLiveCollapseTriggers}). Sin esto, dos hilos podrían detectar colapso
     * casi al mismo tiempo y pisarse el {@link #collapseInfo} o notificar el fin dos veces.
     */
    private final AtomicBoolean collapseTriggered = new AtomicBoolean(false);
    
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
    // volatile: leído/escrito tanto por el hilo principal de planificación como por
    // storageUpdateThread (updateSimulatedClock corre desde ambos). Antes era un campo
    // plano pese a la carrera de datos preexistente; con A.2 el hilo de storage también
    // lo lee para evaluar colapso en vivo, así que se endurece aquí.
    private volatile ZonedDateTime simulatedTime;
    private int batchesProcessed = 0;
    private int batchesFailed = 0;

    // Almacena los lotes procesados para persistencia final.
    // volatile: refillChunk() lo muta desde el hilo principal; storageUpdateThread y
    // SimulationService.getCurrentBatchesSnapshot() lo leen concurrentemente para
    // inventario/colapso en vivo. El synchronized(activeController) del lado del
    // servicio no protege contra este escritor, así que la visibilidad la da volatile.
    private volatile List<ShipmentBatch> currentBatches;

    // Fecha de inicio para calcular días transcurridos
    private ZonedDateTime startDate;
    private volatile double daysElapsed = 0.0;

    // Carga incremental por bloques de fecha real para COLAPSO y 5 DÍAS (evita precargar
    // todo el volumen de una sola vez). null para día a día (no usa dataset histórico).
    private java.util.function.BiFunction<ZonedDateTime, ZonedDateTime, List<ShipmentBatch>> chunkLoader;
    private ZonedDateTime chunkLoadedHorizon;
    /** Última base de proyección para colapso: el último bloque no vacío (real o sintético). */
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

                // 0c. Aviso de warm-up al frontend: confirma que los datos de envíos ya
                //     están cargados y que el ciclo 1 está calculándose. El handler WS lo
                //     cachea, así que clientes que conecten tarde también lo reciben.
                if (cycleNumber == 1 && listener != null) {
                    try {
                        String prepMsg = currentBatches.isEmpty()
                            ? "✓ Datos listos (sin envíos precargados) — esperando registros para el ciclo 1"
                            : String.format(
                                "✓ Datos de envíos cargados (%,d lotes) — calculando el primer plan de rutas (hasta ~%d s)…",
                                currentBatches.size(), scenario.getTaSeconds());
                        listener.onPreparationProgress(getStatus(), prepMsg);
                    } catch (Exception e) {
                        System.err.println("⚠️ Error notificando preparación: " + e.getMessage());
                    }
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

                // Requisito del curso: colapso también se declara apenas UN lote incumple su
                // SLA (su deadline pasó sin haber sido entregado — "SLA vencido" en el log de
                // registerUnroutedForRetry). A diferencia del almacén (chequeado en vivo cada
                // 0.5-1s en checkLiveCollapseTriggers), el SLA solo se re-clasifica una vez por
                // ciclo dentro de executePlanningCycle, así que este chequeo va aquí mismo, justo
                // después de que el ciclo corrió — no hace falta muestreo más fino que eso.
                if (scenario == ScenarioType.COLLAPSE_SIMULATION
                        && scheduler.getLastCycleSlaExpired() > 0
                        && collapseTriggered.compareAndSet(false, true)) {
                    int expiredBatches = scheduler.getLastCycleSlaExpired();
                    System.out.println("\n⚠️  COLAPSO POR SLA INCUMPLIDO - " + expiredBatches
                        + " lote(s) vencieron sin ser entregados en el ciclo " + cycleNumber);
                    CollapseStatus slaCollapseStatus = collapseDetector.evaluateCollapse(
                        currentSolution, batchesProcessed, batchesFailed);
                    this.collapseInfo = new CollapseInfo(
                        "SLA_VIOLATION",
                        "SLA incumplido",
                        String.format("Se consideró colapso porque %d lote(s) del ciclo %d vencieron su plazo de "
                            + "entrega (SLA) sin haber sido despachados — la red ya no puede cumplir sus "
                            + "compromisos de entrega.",
                            expiredBatches, cycleNumber),
                        ZonedDateTime.now(),
                        cyclePlanningTime,
                        slaCollapseStatus.occupancyPercentage(),
                        slaCollapseStatus.unserviceablePercentage(),
                        0,
                        airportManager.getAllAirports().size(),
                        currentCycle,
                        scheduler.getLastCycleBatchesTotal(), scheduler.getLastCycleBagsTotal(),
                        scheduler.getLastCycleBatchesUnrouted(), scheduler.getLastCycleBagsUnrouted(),
                        expiredBatches
                    );
                    completedNaturally = true;
                    break;
                }

                // La sobrecarga severa y la saturación de almacén (evaluateSevereOverload /
                // evaluateWarehouseSaturation) dependen de simulatedTime en un instante — antes
                // se chequeaban aquí, una vez por ciclo (cada Sa=45s reales / Sc=90min simulados).
                // Ahora corren en checkLiveCollapseTriggers(), en el hilo de storage, cada 0.5–1 s
                // reales — muestreo mucho más fino, sin depender de la cadencia de ciclos. Ver A.2.

                if (collapseStatus.isCollapsed()) {
                    // Guard solo en la rama que detiene la simulación: la rama informativa de
                    // PERIOD_SIMULATION debe poder seguir avisando en cada ciclo sin "consumir"
                    // el cierre atómico que protege contra doble-detección de colapso real.
                    if (scenario == ScenarioType.COLLAPSE_SIMULATION && collapseTriggered.compareAndSet(false, true)) {
                        System.out.println("\n⚠️  COLAPSO DETECTADO - Deteniendo simulación");
                        this.collapseInfo = buildCollapseInfoFromStatus(collapseStatus);
                        completedNaturally = true;
                        break;
                    } else if (scenario != ScenarioType.COLLAPSE_SIMULATION) {
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
                && entry.getValue() >= entry.getKey().storageCapacity() * 0.80)
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
            sat[0], sat[1], currentCycle,
            lastCycleBatches(), lastCycleBags(), lastCycleBatchesUnrouted(),
            lastCycleBagsUnrouted(), lastCycleSlaExpired()
        );
    }

    /**
     * Snapshot del último ciclo ejecutado (lotes/maletas consumidos y sin ruta), para
     * publicar en el reporte de colapso. {@code scheduler} puede ser null si el colapso se
     * detecta antes del primer ciclo (no debería pasar en la práctica, pero se cubre).
     */
    private int lastCycleBatches() { return scheduler != null ? scheduler.getLastCycleBatchesTotal() : 0; }
    private int lastCycleBags() { return scheduler != null ? scheduler.getLastCycleBagsTotal() : 0; }
    private int lastCycleBatchesUnrouted() { return scheduler != null ? scheduler.getLastCycleBatchesUnrouted() : 0; }
    private int lastCycleBagsUnrouted() { return scheduler != null ? scheduler.getLastCycleBagsUnrouted() : 0; }
    private int lastCycleSlaExpired() { return scheduler != null ? scheduler.getLastCycleSlaExpired() : 0; }

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
                // SOLO datos reales: antes se sumaba aquí un 123% sintético que luego se
                // solapaba con el siguiente bloque real (~2.2× de demanda desde el día 2 y
                // colapso casi inmediato). El crecimiento sintético entra únicamente cuando
                // el dataset real se agota (ver refillChunk).
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
                return firstChunk;
            }

            default:
                throw new IllegalArgumentException("Escenario desconocido: " + scenario);
        }
    }

    /**
     * Genera el siguiente bloque SINTÉTICO de envíos (Requisitos 29.2/29.4). Se usa SOLO
     * cuando el dataset real se agotó: proyecta COLLAPSE_CHUNK_DAYS días con el factor de
     * crecimiento sobre la última base (el último bloque real, o el último sintético ya
     * generado). Al re-basar sobre lo generado, el crecimiento compone 1.23ⁿ y las fechas
     * de ingreso avanzan naturalmente a continuación del bloque anterior.
     */
    private List<ShipmentBatch> nextSyntheticCollapseBlock() {
        if (collapseLastNonEmptyChunk == null || collapseLastNonEmptyChunk.isEmpty()) {
            return List.of(); // nunca hubo datos reales: no hay patrón del cual proyectar
        }
        ShipmentGenerator collapseGenerator = new ShipmentGenerator();
        List<ShipmentBatch> block = collapseGenerator.generateFutureShipments(
            collapseLastNonEmptyChunk, COLLAPSE_CHUNK_DAYS, COLLAPSE_GROWTH_FACTOR
        );
        if (!block.isEmpty()) {
            collapseLastNonEmptyChunk = block;
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
        boolean synthetic = false;
        if (scenario == ScenarioType.COLLAPSE_SIMULATION && nextChunk.isEmpty()) {
            // Dataset real agotado: recién aquí entra la proyección con crecimiento.
            block = nextSyntheticCollapseBlock();
            synthetic = true;
        } else {
            if (scenario == ScenarioType.COLLAPSE_SIMULATION) {
                collapseLastNonEmptyChunk = nextChunk;
            }
            block = nextChunk;
        }
        for (ShipmentBatch batch : block) {
            scheduler.addShipment(batch);
        }
        currentBatches.addAll(block);
        chunkLoadedHorizon = nextEnd;
        System.out.printf("➕ %s: bloque [%s → %s) cargado — %,d lotes %s%n",
            scenario == ScenarioType.COLLAPSE_SIMULATION ? "Colapso" : "5 días",
            nextStart.toLocalDate(), nextEnd.toLocalDate(), block.size(),
            synthetic
                ? String.format("SINTÉTICOS (dataset real agotado, crecimiento ×%.2f compuesto)", COLLAPSE_GROWTH_FACTOR)
                : "reales");
    }

    /**
     * Configura los algoritmos según el escenario.
     */
    private void configureAlgorithms(GeneticAlgorithm ga, TabuSearch tabu, ScenarioType scenario) {
        AlgorithmConfig gaConfig = new AlgorithmConfig();
        AlgorithmConfig tabuConfig = new AlgorithmConfig();
        
        switch (scenario) {
            case DAY_TO_DAY:
                // Ta=90s/Sa=120s (mucho más holgado que PERIOD/COLLAPSE), pero cada ciclo
                // solo consume Sc=2min de datos reales — normalmente pocos lotes. Mismo
                // diseño anytime: topes generosos, el deadline dinámico gobierna cuánto se
                // usa. firstCycleBudgetRatio protege el ciclo 1 si el usuario precargó
                // muchos envíos por la web antes de arrancar.
                gaConfig.setInt("populationSize", 20);
                gaConfig.setInt("generations", 40);
                gaConfig.setDouble("mutationRate", 0.1);
                gaConfig.setInt("stagnationLimit", 6);
                gaConfig.setBoolean("parallelEnabled", false);
                gaConfig.setDouble("firstCycleBudgetRatio", 0.30);
                tabuConfig.setInt("maxIterations", 2_000);
                tabuConfig.setInt("tabuTenure", 10);
                tabuConfig.setInt("neighborhoodSize", 15);
                break;
                
            case PERIOD_SIMULATION:
                // VM del curso: 2 CPU / 2 GB (frontend + backend). Diseño ANYTIME: el GA
                // mide el costo real de la semilla y decide con eso cuántos individuos
                // caben; el Tabú recibe todo el Ta restante como presupuesto dinámico.
                // populationSize/maxIterations son TOPES — quien gobierna es el deadline.
                gaConfig.setInt("populationSize", 16);
                gaConfig.setInt("generations", 40);
                gaConfig.setDouble("mutationRate", 0.15);
                gaConfig.setInt("stagnationLimit", 6);
                gaConfig.setBoolean("parallelEnabled", false);
                gaConfig.setInt("routeSearchAttempts", 4);
                gaConfig.setInt("routeCachedVariants", 2);
                gaConfig.setDouble("firstCycleBudgetRatio", 0.30);
                // Tope holgado: medido que 400 iteraciones terminan ANTES del presupuesto
                // dinámico; quien debe cortar es el deadline, no el contador.
                tabuConfig.setInt("maxIterations", 2_000);
                tabuConfig.setInt("tabuTenure", 8);
                tabuConfig.setInt("neighborhoodSize", 4);
                tabuConfig.setInt("routeSearchAttempts", 4);
                tabuConfig.setInt("routeCachedVariants", 2);
                break;

            case COLLAPSE_SIMULATION:
                // Misma calibración anytime que PERIOD (2 CPU / 2 GB).
                gaConfig.setInt("populationSize", 16);
                gaConfig.setInt("generations", 40);
                gaConfig.setDouble("mutationRate", 0.15);
                gaConfig.setInt("stagnationLimit", 6);
                gaConfig.setBoolean("parallelEnabled", false);
                gaConfig.setInt("routeSearchAttempts", 4);
                gaConfig.setInt("routeCachedVariants", 2);
                gaConfig.setDouble("firstCycleBudgetRatio", 0.30);
                tabuConfig.setInt("maxIterations", 2_000);
                tabuConfig.setInt("tabuTenure", 8);
                tabuConfig.setInt("neighborhoodSize", 4);
                tabuConfig.setInt("routeSearchAttempts", 4);
                tabuConfig.setInt("routeCachedVariants", 2);
                break;
        }

        // Presupuesto de tiempo duro por ciclo (deadline): el algoritmo nunca excede Ta.
        // GA ~40% de Ta como TOPE; el Tabú recibe en runtime todo lo que el GA no use
        // (presupuesto dinámico en Scheduler). Medido: la evolución poblacional aporta
        // poco fitness por segundo frente a los movimientos de descongestión del Tabú,
        // así que el reparto favorece al refinamiento. El maxTimeMillis del Tabú es solo
        // fallback para usos sin presupuesto explícito (p. ej. TABU_PURE).
        long taMs = scenario.getTaSeconds() * 1000L;
        gaConfig.setInt("maxTimeMillis", (int) Math.round(taMs * 0.40));
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

        /**
         * Llamado durante el warm-up (antes de que termine el ciclo 1) con mensajes de
         * progreso. Sin esto el frontend no recibe NADA entre "Simulación iniciada" y el
         * primer CYCLE_UPDATE (medido: hasta ~27 s en la VM de 2 CPU) y la pantalla
         * "Preparando simulación…" parece colgada. Default no-op para no romper
         * implementaciones existentes (tests, listeners mínimos).
         */
        default void onPreparationProgress(SimulationStatus status, String message) {}
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

    /**
     * Hilo de fondo que mantiene vivos, en tiempo real, tanto el inventario de almacenes
     * como (para COLAPSO) la detección de saturación — independiente de la cadencia de
     * ciclos del GA/Tabú.
     *
     * <p>Antes, mientras {@code planningInProgress} era true (hasta ~25s reales por ciclo),
     * este hilo solo avanzaba el reloj y reenviaba el último inventario conocido sin
     * recalcular ("modo liviano" — ahorro de CPU de la época en que la VM tenía 1 core).
     * Con 2 CPUs reales ya no hace falta: {@code currentSolution} no cambia durante el
     * planning (la acumulación crea una nueva instancia recién al final del ciclo), y
     * {@link StorageInventoryService#calculateCurrentBags} cachea los eventos ordenados
     * por identidad de solución — recalcular en cada tick durante el planning es, en la
     * práctica, un cache-hit barato, no un recálculo O(rutas) completo.</p>
     */
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
                    notifyStorageUpdated();
                    if (scenario == ScenarioType.COLLAPSE_SIMULATION) {
                        checkLiveCollapseTriggers();
                    }
                    // Intervalo más largo durante planning: el GA usa el otro core; el
                    // recálculo es barato (cache-hit) pero no hace falta spamear el WS.
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
        if (listener == null) return;
        try {
            listener.onStorageUpdated(buildLightweightStatus(), currentSolution);
        } catch (Exception e) {
            System.err.println("⚠️ Error notificando inventario: " + e.getMessage());
        }
    }

    /** Desde cuándo (epoch ms, reloj REAL) cada aeropuerto viene por encima del 100% de forma
     *  CONTINUA — se reinicia apenas un tick lo ve de vuelta bajo 100%. Solo el hilo de storage
     *  toca este mapa (mismo hilo que llama a {@link #checkLiveCollapseTriggers}), sin
     *  necesidad de sincronización. */
    private final Map<String, Long> overCapacitySinceMs = new HashMap<>();

    /**
     * Ventana mínima (ms reales) que un almacén debe permanecer CONTINUAMENTE sobre su
     * capacidad antes de declarar colapso, según qué tan severo sea el exceso ahora mismo.
     *
     * <p>Un pico transitorio (maletas esperando su vuelo de conexión, que se libera apenas ese
     * vuelo despega) es un fenómeno normal y frecuente de la red — verificado en vivo: un
     * aeropuerto pasó por 114%→88%→116%→63% en 90 s reales en una corrida completamente sana,
     * sin ningún problema real de fondo. Disparar colapso en el primer tick que cruza 100%
     * confunde ese ruido esperado con saturación genuina: es cuestión de suerte del muestreo
     * en qué segundo exacto un pico normal cae del lado equivocado de la línea, no una señal
     * real de que la red colapsó — por eso corridas con demanda similar podían "colapsar" en
     * momentos completamente distintos sin ninguna razón de fondo.</p>
     *
     * <p>La escala es inversa a la severidad: mientras más lejos de 100% esté, menos tiempo
     * hace falta esperar — un 110%+ ya es una sobrecarga difícil de explicar como simple
     * ruido de buffering, así que se declara casi de inmediato.</p>
     */
    private static long requiredPersistenceMs(double ratio) {
        if (ratio >= 1.10) return 2_000L;
        if (ratio >= 1.05) return 5_000L;
        return 10_000L; // 100%–105%
    }

    /**
     * Chequeo de colapso EN VIVO para COLAPSO: antes, evaluateSevereOverload/
     * evaluateWarehouseSaturation solo corrían una vez por ciclo en el loop principal
     * (cada Sa=45s reales / Sc=90min simulados). Aquí el muestreo es cada tick de storage
     * (0.5–1 s reales). Un solo {@code calculateCurrentBags} alimenta la comprobación.
     *
     * <p>Requisito del curso: el colapso se declara cuando un almacén supera el 100% de su
     * capacidad de forma SOSTENIDA (ver {@link #requiredPersistenceMs}) — no hace falta que
     * sean varios aeropuertos ni un solo tick alcanza. Esto reemplaza tanto los umbrales
     * originales (120% en ≥3 aeropuertos, 50% de la red) como el disparo instantáneo que los
     * reemplazó: ambos existían/fallaban por la misma razón, distinguir ruido de saturación
     * real. Con la capacidad de almacén ya como restricción dura del planificador, un pico NO
     * es ruido de bug — es buffering real — así que la señal correcta es "cuánto tiempo se
     * sostiene", no "si tocó la línea una vez".</p>
     *
     * <p>Señaliza el colapso al loop principal escribiendo {@link #collapseInfo}/
     * {@link #completedNaturally} (volatile) y recién después {@code running.set(false)}.
     * El loop principal revisa {@code running} al tope de cada iteración y en
     * {@link #sleepInterruptibly}.</p>
     */
    private void checkLiveCollapseTriggers() {
        if (collapseTriggered.get()) {
            return;
        }
        if (currentSolution == null || currentSolution.getRoutes().isEmpty() || simulatedTime == null) {
            return;
        }

        StorageInventoryService inventory = inventoryService != null
            ? inventoryService
            : new StorageInventoryService(airportManager);
        Map<Airport, Integer> currentBags = inventory.calculateCurrentBags(
            currentSolution, simulatedTime, currentBatches);
        if (currentBags.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        Set<String> stillOver = new HashSet<>();
        Airport triggeredAirport = null;
        int triggeredBags = 0;
        double triggeredRatio = 0.0;

        for (Map.Entry<Airport, Integer> entry : currentBags.entrySet()) {
            Airport airport = entry.getKey();
            int bags = entry.getValue();
            if (airport.storageCapacity() <= 0 || bags <= airport.storageCapacity()) {
                continue;
            }
            double ratio = (double) bags / airport.storageCapacity();
            stillOver.add(airport.id());
            long since = overCapacitySinceMs.computeIfAbsent(airport.id(), id -> now);
            long persisted = now - since;
            if (persisted >= requiredPersistenceMs(ratio)) {
                triggeredAirport = airport;
                triggeredBags = bags;
                triggeredRatio = ratio;
                break;
            }
        }
        // Reiniciar la racha de cualquier aeropuerto que este tick ya no está sobre capacidad.
        overCapacitySinceMs.keySet().removeIf(id -> !stillOver.contains(id));

        if (triggeredAirport != null && collapseTriggered.compareAndSet(false, true)) {
            Airport airport = triggeredAirport;
            int bags = triggeredBags;
            double pct = 100.0 * triggeredRatio;
            System.out.printf("%n⚠️  COLAPSO POR ALMACÉN SOBRE CAPACIDAD - %s (%s) al %.0f%% (%d/%d maletas), sostenido%n",
                airport.id(), airport.city(), pct, bags, airport.storageCapacity());
            CollapseStatus collapseStatus = collapseDetector.evaluateCollapse(
                currentSolution, batchesProcessed, batchesFailed);
            this.collapseInfo = new CollapseInfo(
                "WAREHOUSE_OVER_CAPACITY",
                "Almacén sobre capacidad",
                String.format("Se consideró colapso porque el almacén %s (%s) superó el 100%% de su capacidad "
                    + "de forma sostenida (%.0f%%, %d/%d maletas) — la red ya no puede recibir más carga en ese punto.",
                    airport.id(), airport.city(), pct, bags, airport.storageCapacity()),
                ZonedDateTime.now(),
                simulatedTime,
                collapseStatus.occupancyPercentage(),
                collapseStatus.unserviceablePercentage(),
                1,
                airportManager.getAllAirports().size(),
                currentCycle,
                lastCycleBatches(), lastCycleBags(), lastCycleBatchesUnrouted(),
                lastCycleBagsUnrouted(), lastCycleSlaExpired()
            );
            completedNaturally = true;
            running.set(false);
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
