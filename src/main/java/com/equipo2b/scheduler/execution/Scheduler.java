package com.equipo2b.scheduler.execution;

import com.equipo2b.scheduler.algorithm.*;
import com.equipo2b.scheduler.logic.AccumulatedFitnessTracker;
import com.equipo2b.scheduler.logic.CapacityContext;
import com.equipo2b.scheduler.logic.RouteGenerator;
import com.equipo2b.scheduler.logic.SolutionEvaluator;
import com.equipo2b.scheduler.model.*;
import com.equipo2b.scheduler.validation.RouteValidator;
import com.equipo2b.scheduler.validation.ValidationReport;
import com.equipo2b.scheduler.validation.ViolationType;

import java.time.ZonedDateTime;
import java.time.Duration;
import java.util.*;

/**
 * Scheduler para planificación programada con ciclos periódicos.
 * 
 * <p>Soporta dos modos de algoritmo:
 * <ul>
 *   <li>GATS: Algoritmo Genético + Búsqueda Tabú (híbrido)</li>
 *   <li>TABU_PURE: Búsqueda Tabú pura (standalone)</li>
 * </ul>
 * 
 * Parámetros:
 * - Ta: Presupuesto máximo de ejecución del algoritmo (segundos; deadline duro)
 * - Sa: Salto entre ejecuciones del algoritmo (segundos)
 * - K: Constante de proporcionalidad para consumo de datos
 * - Sc = Sa × K: Salto de consumo de datos (minutos simulados)
 * 
 * **Validates: Requirements 21.5, 22.4, 23.1, 34.1, 34.2, Caso de estudio punto a, b**
 */
public class Scheduler {
    private final OptimizationAlgorithm primaryAlgorithm;
    private final TabuSearch tabuSearch;
    private final AlgorithmType algorithmType;
    private final boolean useRefinement;
    private final ShipmentQueue shipmentQueue;
    private final SolutionEvaluator evaluator;
    private final RouteValidator validator;
    // División por capacidad (sub-lotes): cuando un vuelo va lleno pero solo caben algunas
    // maletas de un envío, divide el envío — las que caben se quedan y el resto se reubican
    // en otros vuelos con espacio (directo o con escalas). Cubre vuelos sobre-capacidad y
    // lotes sin ruta; ver applyCapacityAwareSplitting.
    private final FlightPlan flightPlan;
    private final boolean partialFillEnabled;
    private final RouteGenerator fillRouteGenerator;  // para sub-lotes multi-hop (puede ser null)
    private static final int MIN_FILL_BAGS = 1;

    /**
     * PRIMER CICLO RÁPIDO (mismo principio que GA.firstCycleBudgetRatio, aplicado al
     * Tabú): con la red vacía el usuario ve "Preparando simulación…" hasta que el ciclo 1
     * entero termina. Medido en contenedor 2 CPU/1.5 GB: con presupuesto dinámico completo
     * el Tabú consumía ~20-25 s del Ta en el ciclo 1 (total ~27 s con datos de 2028),
     * haciendo parecer colgada la UI. Ciclos siguientes (red cargada, donde el balanceo
     * SÍ es crítico) usan el presupuesto dinámico completo.
     */
    private static final double FIRST_CYCLE_REFINE_BUDGET_RATIO = 0.20;
    
    // Parámetros de configuración
    private final int taSeconds;  // Presupuesto del algoritmo (segundos)
    private final int saSeconds;  // Salto entre ejecuciones (segundos)
    private final int K;          // Constante proporcionalidad
    private final int Sc;         // Salto consumo = Sa × K (minutos simulados)
    
    // Solución actual del sistema
    private Solution currentSolution;

    // Cola de reintento: lotes que NO obtuvieron ruta en el ciclo anterior y aún pueden
    // cumplir su SLA. Entran al siguiente ciclo ANTES que los lotes nuevos (prioridad).
    // Los lotes sin camino factible por horario/SLA (infactibilidad estructural: el plan
    // de vuelos no cambia entre ciclos) NO se reintentan — sería presupuesto perdido.
    private final List<ShipmentBatch> carryoverBatches = new ArrayList<>();

    // Fitness incremental de la solución acumulada (ver AccumulatedFitnessTracker): evita
    // recorrer TODA la historia de rutas cada ciclo para recalcular capacidad/SLA/almacén.
    private final AccumulatedFitnessTracker fitnessTracker = new AccumulatedFitnessTracker();

    // "Frente caliente": rutas cuyo primer vuelo aún no sale — las únicas que
    // applyCapacityAwareSplitting puede seguir modificando (peel/reemplazo) en ciclos
    // futuros. Por eso NO se registran en fitnessTracker hasta que su primer vuelo ya
    // salió (ver promoteSettledFrenteCaliente): registrarlas antes arriesgaría sellar una
    // contribución que splitting todavía puede cambiar. Tamaño acotado por cuántos vuelos
    // recientes siguen sin salir, no por cuánto tiempo simulado ha pasado.
    private final Map<String, AssignedRoute> frenteCaliente = new HashMap<>();

    /**
     * Constructor del Scheduler con algoritmo configurable.
     *
     * @param primaryAlgorithm Algoritmo primario (GA o Tabu)
     * @param tabuSearch Búsqueda Tabú para refinamiento (opcional)
     * @param algorithmType Tipo de algoritmo (GATS o TABU_PURE)
     * @param useRefinement Si se debe aplicar refinamiento Tabú adicional
     * @param shipmentQueue Cola de pedidos pendientes
     * @param evaluator Evaluador de fitness
     * @param validator Validador de soluciones
     * @param taSeconds Presupuesto máximo del algoritmo (segundos)
     * @param saSeconds Salto entre ejecuciones (segundos)
     * @param K Constante de proporcionalidad
     * 
     * **Validates: Requirements 21.5, 22.4, 23.1, 34.1, 34.2, Caso de estudio punto a, b**
     */
    public Scheduler(OptimizationAlgorithm primaryAlgorithm,
                    TabuSearch tabuSearch,
                    AlgorithmType algorithmType,
                    boolean useRefinement,
                    ShipmentQueue shipmentQueue,
                    SolutionEvaluator evaluator,
                    RouteValidator validator,
                    int taSeconds, int saSeconds, int K) {
        this(primaryAlgorithm, tabuSearch, algorithmType, useRefinement,
             shipmentQueue, evaluator, validator, taSeconds, saSeconds, K, null, false, null);
    }

    /** Constructor con relleno de capacidad por sub-lotes opcional (directo + multi-hop). */
    public Scheduler(OptimizationAlgorithm primaryAlgorithm,
                    TabuSearch tabuSearch,
                    AlgorithmType algorithmType,
                    boolean useRefinement,
                    ShipmentQueue shipmentQueue,
                    SolutionEvaluator evaluator,
                    RouteValidator validator,
                    int taSeconds, int saSeconds, int K,
                    FlightPlan flightPlan,
                    boolean partialFillEnabled,
                    RouteGenerator fillRouteGenerator) {
        this.flightPlan = flightPlan;
        this.partialFillEnabled = partialFillEnabled;
        this.fillRouteGenerator = fillRouteGenerator;
        this.primaryAlgorithm = Objects.requireNonNull(primaryAlgorithm, "Primary algorithm cannot be null");
        this.tabuSearch = Objects.requireNonNull(tabuSearch, "Tabu search cannot be null");
        this.algorithmType = Objects.requireNonNull(algorithmType, "Algorithm type cannot be null");
        this.useRefinement = useRefinement;
        this.shipmentQueue = Objects.requireNonNull(shipmentQueue, "Shipment queue cannot be null");
        this.evaluator = Objects.requireNonNull(evaluator, "Evaluator cannot be null");
        this.validator = Objects.requireNonNull(validator, "Validator cannot be null");

        this.taSeconds = taSeconds;
        this.saSeconds = saSeconds;
        this.K = K;
        // Sc en minutos simulados; Sa ahora es segundos → Sa×K debe ser múltiplo de 60
        long scSeconds = (long) saSeconds * K;
        if (scSeconds % 60 != 0) {
            throw new IllegalArgumentException(
                String.format("Sa (%ds) × K (%d) = %ds no es un número entero de minutos", saSeconds, K, scSeconds)
            );
        }
        this.Sc = (int) (scSeconds / 60);

        // Validar Sa >= Ta. Se permite Sa == Ta porque el algoritmo respeta un
        // presupuesto de tiempo duro (deadline = Ta) dentro de GA/Tabu: nunca excede Ta,
        // y la cadencia (Sa) puede igualar Ta para usar todo el CPU sin tiempo muerto.
        if (saSeconds < taSeconds) {
            throw new IllegalArgumentException(
                String.format("Sa (%ds) must be >= Ta (%ds)", saSeconds, taSeconds)
            );
        }

        this.currentSolution = new Solution();
    }
    
    /**
     * Ejecuta un ciclo de planificación.
     * 
     * Proceso:
     * 1. Consumir pedidos de ventana Sc
     * 2. Ejecutar algoritmo primario (GA o Tabu)
     * 3. Refinar con Búsqueda Tabú (solo si useRefinement = true)
     * 4. Validar solución
     * 5. Actualizar rutas asignadas
     * 
     * @param currentTime Tiempo actual de la simulación
     * @return Solución refinada y validada
     * 
     * **Validates: Requirements 22.1, 22.2, 22.3, 22.5, 23.2, Caso de estudio punto a, b**
     */
    public Solution executePlanningCycle(ZonedDateTime currentTime) {
        // Reloj de pared del ciclo COMPLETO (no solo GA+Tabú): evaluate/validate/split/
        // registerUnroutedForRetry también cuestan tiempo real y antes quedaban fuera del
        // "Tiempo total" reportado más abajo, escondiendo cuánto se pasaba realmente el
        // presupuesto Ta hasta que ya era demasiado tarde para notarlo en los logs.
        long cycleWallStartMs = System.currentTimeMillis();
        System.out.println("\n=== CICLO DE PLANIFICACIÓN ===");
        System.out.println("Algoritmo: " + algorithmType.getDisplayName());
        System.out.println("Tiempo actual: " + currentTime);
        
        // 1. Calcular ventana de consumo: [currentTime, currentTime + Sc]
        ZonedDateTime windowStart = currentTime;
        ZonedDateTime windowEnd = currentTime.plusMinutes(Sc);
        
        System.out.println("Ventana de consumo: " + Sc + " minutos");
        
        // 2. Consumir pedidos de ShipmentQueue en ventana Sc + reintentos del ciclo anterior.
        //    Los reintentos van PRIMERO: llevan más tiempo esperando y menos margen de SLA.
        List<ShipmentBatch> newBatches = shipmentQueue.consumeShipments(windowStart, windowEnd);
        int retryCount = carryoverBatches.size();
        List<ShipmentBatch> batches = new ArrayList<>(retryCount + newBatches.size());
        batches.addAll(carryoverBatches);
        batches.addAll(newBatches);
        carryoverBatches.clear();
        System.out.println("Lotes consumidos: " + newBatches.size() + " nuevos"
            + (retryCount > 0 ? " + " + retryCount + " en reintento = " + batches.size() : ""));
        
        if (batches.isEmpty()) {
            System.out.println("No hay lotes para planificar");
            return currentSolution;
        }
        
        // 3. Ejecutar algoritmo primario con pedidos consumidos.
        //    La línea base de almacenes (carga de ciclos previos) se aplica SOLO durante
        //    la optimización de la ventana nueva: los candidatos del ciclo se evalúan
        //    contra la ocupación absoluta real de cada almacén.
        applyStorageBaseline(pendingStorageBaseline);
        // Red vacía = primer ciclo real (warm start). Se calcula ANTES de optimizar
        // porque currentSolution se reemplaza más abajo con la acumulada de este ciclo.
        boolean isWarmStart = currentSolution.getRoutes().isEmpty();
        long startTime = System.currentTimeMillis();
        String algorithmName = algorithmType == AlgorithmType.GATS ? "Algoritmo Genético" : "Búsqueda Tabú";
        System.out.println("\nEjecutando " + algorithmName + "...");
        Solution primarySolution = primaryAlgorithm.optimize(batches);
        long primaryTime = System.currentTimeMillis() - startTime;

        System.out.println("✓ " + algorithmName + " completado en " + primaryTime + " ms");
        System.out.println("  Fitness: " + String.format("%.2f", primarySolution.getFitness()));

        Solution finalSolution = primarySolution;
        long refinementTime = 0;

        // 4. Refinar con Búsqueda Tabú (solo para GATS) con PRESUPUESTO DINÁMICO: recibe
        //    todo el Ta que la fase primaria no consumió (menos un margen para split/
        //    evaluación/validación). Así, cuando la semilla es rápida, el balanceo de
        //    almacenes/vuelos por escalas dispone de decenas de segundos en lugar de un
        //    porcentaje fijo, sin jamás exceder Ta.
        if (useRefinement) {
            startTime = System.currentTimeMillis();
            long taMillisBudget = taSeconds > 0 ? taSeconds * 1000L : 0;
            // Margen del 15% de Ta (≥1.5s): en la VM del curso (más lenta que el equipo
            // de desarrollo) la acumulación/evaluación/validación post-algoritmo también
            // se encarece; con Ta=30s el algoritmo termina a ~25.5s y queda holgura real.
            long refineBudget = taMillisBudget > 0
                ? taMillisBudget - primaryTime - Math.max(1_500L, Math.round(taMillisBudget * 0.15))
                : 0;

            if (isWarmStart && taMillisBudget > 0) {
                refineBudget = Math.min(refineBudget,
                    Math.round(taMillisBudget * FIRST_CYCLE_REFINE_BUDGET_RATIO));
            }

            if (taMillisBudget > 0 && refineBudget < 500) {
                System.out.println("⏱ Sin presupuesto restante para refinamiento Tabú (fase primaria usó "
                    + primaryTime + " ms de " + taMillisBudget + " ms)");
            } else {
                System.out.println("\nRefinando con Búsqueda Tabú"
                    + (taMillisBudget > 0 ? " (presupuesto dinámico: " + refineBudget + " ms)..." : "..."));
                finalSolution = taMillisBudget > 0
                    ? tabuSearch.refine(primarySolution, refineBudget)
                    : tabuSearch.refine(primarySolution);
                refinementTime = System.currentTimeMillis() - startTime;

                System.out.println("✓ Refinamiento completado en " + refinementTime + " ms");
                System.out.println("  Fitness mejorado: " + String.format("%.2f", finalSolution.getFitness()));
            }
        }

        // Retirar la línea base ANTES de evaluar la solución ACUMULADA: la acumulada ya
        // contiene las rutas de ciclos previos — mantener la base contaría su carga 2 veces.
        applyStorageBaseline(null);
        
        // 5. ACUMULAR rutas nuevas a la solución existente (PLANIFICACIÓN INCREMENTAL)
        Solution accumulatedSolution = new Solution(currentSolution);
        int routesBeforeAccumulation = accumulatedSolution.getRoutes().size();
        int newRoutesCount = finalSolution.getRoutes().size();
        
        System.out.println("\n=== ACUMULACIÓN DE RUTAS ===");
        System.out.println("Rutas existentes: " + routesBeforeAccumulation);
        System.out.println("Rutas nuevas generadas: " + newRoutesCount);
        
        // Acumular cada ruta nueva sobre una copia estable de la solución actual. Entran al
        // "frente caliente" (no al fitnessTracker todavía): su primer vuelo por construcción
        // aún no salió, así que applyCapacityAwareSplitting puede seguir modificándolas en
        // este mismo ciclo o en ciclos futuros — sellar su contribución ahora arriesgaría
        // contarla mal si luego se parte (peel). Sus eventos de almacén sí entran de una vez
        // a la cola pendiente del tracker (fitnessTracker.trackPendingEvents): deben competir
        // por la marca de agua desde ya, para que un evento sellado más tarde nunca sea más
        // antiguo que uno recién agregado aquí.
        for (AssignedRoute route : finalSolution.getRoutes().values()) {
            accumulatedSolution.addRoute(route);  // Agrega o reemplaza por batchId
            frenteCaliente.put(route.getBatch().batchId(), route);
        }
        fitnessTracker.trackPendingEvents(finalSolution.getRoutes().values());

        int routesAfterAccumulation = accumulatedSolution.getRoutes().size();
        System.out.println("Rutas totales acumuladas: " + routesAfterAccumulation);

        // 5b. División por capacidad (sub-lotes): cuando un vuelo va lleno pero solo caben
        //     ALGUNAS maletas de un envío, el envío se divide — las que caben se quedan y el
        //     resto se reubican en otros vuelos con espacio. Cubre TODOS los casos (no solo
        //     lotes sin ruta). Determinista, aditivo y solo actúa si hay exceso real.
        if (partialFillEnabled && flightPlan != null) {
            int splits = applyCapacityAwareSplitting(accumulatedSolution, batches, windowStart, windowEnd);
            if (splits > 0) {
                System.out.println("🧩 División por capacidad: " + splits + " sub-lotes ubicados (envíos divididos en vuelos distintos)");
            }
            refreshFrenteCalienteAfterSplitting(accumulatedSolution, batches);
        }

        // 6. Validar SOLO las rutas nuevas de este ciclo (finalSolution), no la acumulada
        //    completa: RouteValidator repite exactamente lo que el evaluador ya calcula
        //    (mismo recorrido + mismo sort de eventos de almacén), y las rutas históricas ya
        //    fueron validadas cuando se crearon — no vuelven a cambiar (AssignedRoute es
        //    inmutable). Con miles de rutas acumuladas este segundo recorrido completo era
        //    puro trabajo duplicado solo para loguear.
        ValidationReport validationReport = validator.validate(finalSolution);

        if (validationReport.isValid()) {
            System.out.println("✓ Rutas de este ciclo válidas");
        } else {
            System.out.println("⚠ Rutas de este ciclo con violaciones:");
            System.out.println(validationReport.getSummary());
        }

        logQualityMetrics(batches, finalSolution, accumulatedSolution, validationReport);

        // 6b. Clasificar lotes sin ruta: reintento (aún dentro de SLA y con camino factible),
        //     vencidos (su SLA expira antes del próximo ciclo) o estructuralmente imposibles.
        //     Debe correr ANTES del fitness incremental: la marca de agua de eventos de
        //     almacén (paso 7) necesita conocer los reintentos que quedan en carryoverBatches
        //     para no sellar eventos con fecha anterior a un reintento aún pendiente.
        registerUnroutedForRetry(batches, accumulatedSolution, windowEnd);

        // 7. Fitness incremental de la solución acumulada (ver AccumulatedFitnessTracker):
        //    sella vuelos ya cerrados, promueve del frente caliente las rutas cuyo primer
        //    vuelo ya salió (splitting ya no puede tocarlas), avanza la marca de agua de
        //    eventos de almacén hasta donde es seguro, y recalcula el fitness SOLO sobre lo
        //    que aún puede cambiar — nunca recorriendo la solución acumulada completa, sin
        //    importar cuántas rutas lleve la simulación.
        fitnessTracker.settleExpiredFlights(windowStart, evaluator);
        promoteSettledFrenteCaliente(windowStart, evaluator);
        fitnessTracker.advanceStorageWatermark(storageEventWatermark(windowStart), evaluator);
        accumulatedSolution.setFitness(fitnessTracker.currentFitness(frenteCaliente.values(), evaluator));
        System.out.println("Fitness de solución acumulada: " + String.format("%.2f", accumulatedSolution.getFitness()));

        // 8. Registrar tiempo de ejecución y verificar que sea <= Ta. El presupuesto Ta
        //    gobierna GA+Tabú (algorithmTime), pero la ADVERTENCIA de exceso debe mirar el
        //    ciclo COMPLETO (cycleWallMs): evaluate/validate/split/registerUnroutedForRetry
        //    corren después y antes quedaban fuera de esta cuenta — un ciclo podía tardar el
        //    doble o triple del real sin que este log lo mostrara jamás.
        long algorithmTime = primaryTime + refinementTime;
        long cycleWallMs = System.currentTimeMillis() - cycleWallStartMs;
        long taMillis = taSeconds * 1000L;

        System.out.println("\nTiempo algoritmo (GA+Tabú): " + algorithmTime + " ms (límite: " + taMillis + " ms)");
        System.out.println("Tiempo total del ciclo (incluye evaluar/validar/dividir): " + cycleWallMs + " ms");

        if (cycleWallMs > taMillis) {
            System.out.println("⚠ ADVERTENCIA: Tiempo total del ciclo excedió Ta"
                + (algorithmTime <= taMillis
                    ? " (el algoritmo estuvo dentro de presupuesto — el exceso viene de evaluar/validar/dividir la solución acumulada)"
                    : ""));
        }

        currentSolution = accumulatedSolution;
        return currentSolution;
    }

    /**
     * Encola para el próximo ciclo los lotes que quedaron SIN RUTA en este, filtrando los
     * casos donde reintentar no tiene sentido:
     * <ul>
     *   <li><b>SLA vencido</b>: su deadline cae antes del fin de esta ventana — ya es
     *       irrecuperable, reintentarlo solo infla la carga del algoritmo.</li>
     *   <li><b>Infactibilidad estructural</b>: no existe NINGUNA combinación de vuelos que
     *       cumpla el SLA aunque la capacidad fuera infinita. Como el plan de vuelos no
     *       cambia entre ciclos, el resultado nunca cambiaría.</li>
     * </ul>
     * Los que sí se encolan fallaron por congestión momentánea (capacidad ocupada por otras
     * rutas de esta ventana), que sí puede resolverse en el ciclo siguiente.
     *
     * <p>Limitación conocida: la búsqueda de rutas parte del ingreso del lote, así que un
     * reintento podría elegir un vuelo que despega dentro de la ventana anterior (hasta Sc
     * minutos "en el pasado" del reloj de planificación). Con Sc=90min el efecto es menor.</p>
     */
    private void registerUnroutedForRetry(List<ShipmentBatch> batches,
                                          Solution accumulated,
                                          ZonedDateTime windowEnd) {
        int expired = 0;
        int structural = 0;
        int expiredBags = 0;
        int structuralBags = 0;
        int newCarryover = 0;
        int newCarryoverBags = 0;
        int totalBags = 0;
        for (ShipmentBatch batch : batches) {
            totalBags += batch.quantity();
            if (isRouted(accumulated, batch.batchId())) {
                continue;
            }
            ZonedDateTime slaDeadline = batch.ingressTime().plus(batch.calculateSLA());
            if (!slaDeadline.isAfter(windowEnd)) {
                expired++;
                expiredBags += batch.quantity();
                continue;
            }
            if (!tabuSearch.hasFeasiblePathIgnoringCapacity(batch)) {
                structural++;
                structuralBags += batch.quantity();
                continue;
            }
            carryoverBatches.add(batch);
            newCarryover++;
            newCarryoverBags += batch.quantity();
        }

        // Snapshot del ciclo para reportar el "último ciclo" si esto dispara un colapso (ver
        // SimulationController.checkLiveCollapseTriggers / getLastCycle* getters más abajo).
        lastCycleBatchesTotal = batches.size();
        lastCycleBagsTotal = totalBags;
        lastCycleSlaExpired = expired;
        lastCycleBatchesUnrouted = expired + structural + newCarryover;
        lastCycleBagsUnrouted = expiredBags + structuralBags + newCarryoverBags;

        if (!carryoverBatches.isEmpty() || expired > 0 || structural > 0) {
            System.out.printf(
                "🔁 Sin ruta este ciclo: %d pasan a reintento, %d con SLA vencido, %d sin camino factible por horario (no se reintentan)%n",
                carryoverBatches.size(), expired, structural
            );
        }
    }

    private int lastCycleBatchesTotal;
    private int lastCycleBagsTotal;
    private int lastCycleBatchesUnrouted;
    private int lastCycleBagsUnrouted;
    private int lastCycleSlaExpired;

    /** Lotes consumidos en el último ciclo ejecutado (nuevos + reintentos). */
    public int getLastCycleBatchesTotal() { return lastCycleBatchesTotal; }
    /** Maletas consumidas en el último ciclo ejecutado. */
    public int getLastCycleBagsTotal() { return lastCycleBagsTotal; }
    /** Lotes del último ciclo que quedaron SIN ruta (reintento + SLA vencido + inviable). */
    public int getLastCycleBatchesUnrouted() { return lastCycleBatchesUnrouted; }
    /** Maletas del último ciclo que quedaron SIN ruta. */
    public int getLastCycleBagsUnrouted() { return lastCycleBagsUnrouted; }
    /** Lotes del último ciclo cuyo SLA venció sin haber sido entregados (irrecuperable). */
    public int getLastCycleSlaExpired() { return lastCycleSlaExpired; }

    /**
     * True si el lote (completo o dividido en sub-lotes "-S&lt;n&gt;") tiene ruta en la
     * solución acumulada. Antes esto se resolvía reconstruyendo un {@code Set} con TODAS las
     * claves de la solución acumulada (O(rutas totales), miles tras varios días simulados)
     * solo para chequear membresía de los ~100-150 lotes de ESTE ciclo. Cada lote de este
     * ciclo solo puede tener rutas creadas en este mismo ciclo (un lote se procesa una sola
     * vez, en el ciclo en que se consume), así que un puñado de accesos O(1) al mapa basta —
     * mismo resultado, sin recorrer el historial acumulado.
     */
    private static boolean isRouted(Solution accumulated, String batchId) {
        if (accumulated.getRoute(batchId) != null) {
            return true;
        }
        for (int i = 1; accumulated.getRoute(batchId + "-S" + i) != null; i++) {
            return true;
        }
        return false;
    }

    /**
     * Re-sincroniza {@link #frenteCaliente} después de que applyCapacityAwareSplitting corrió:
     * splitting puede haber reemplazado (peel) o vaciado por completo cualquier entrada
     * existente, y puede haber creado sub-lotes nuevos ("-S&lt;n&gt;"). Ambos casos se
     * resuelven con accesos O(1) a {@code accumulated} — nunca recorriendo la solución
     * completa — porque el universo de ids afectados es acotado.
     *
     * <p><b>Ojo con qué bases se re-escanean</b>: solo las de entradas efectivamente TOCADAS
     * este ciclo (peel o absorción) más los lotes de este ciclo — NUNCA las de entradas que
     * siguen intactas en frenteCaliente. Escanear una base intacta redescubriría un sub-lote
     * hermano que ya fue promovido y sacado de frenteCaliente en un ciclo anterior — como
     * {@code accumulated.getRoutes()} nunca olvida nada, "ausente de frenteCaliente" no
     * distingue "nunca visto" de "ya promovido", y volver a agregarlo lo contaría dos veces
     * cuando se promueva otra vez (bug real, encontrado con el test de invariante: un sub-lote
     * se sumaba dos veces al fitness).</p>
     */
    private void refreshFrenteCalienteAfterSplitting(Solution accumulated, List<ShipmentBatch> cycleBatches) {
        Set<String> baseIdsToCheck = new HashSet<>();

        Iterator<Map.Entry<String, AssignedRoute>> it = frenteCaliente.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, AssignedRoute> entry = it.next();
            AssignedRoute previous = entry.getValue();
            AssignedRoute current = accumulated.getRoute(entry.getKey());
            if (current == null) {
                // splitting absorbió esta ruta por completo en otros vuelos: sus eventos de
                // almacén pendientes (encolados cuando se creó, vía trackPendingEvents) ya no
                // corresponden a nada — hay que retirarlos, no quedan huérfanos para siempre.
                baseIdsToCheck.add(baseBatchId(entry.getKey()));
                fitnessTracker.replacePendingEvents(previous, null);
                it.remove();
            } else if (current != previous) {
                // splitting la reemplazó (peel) por una de menor cantidad: sus eventos
                // pendientes tenían la cantidad ORIGINAL — hay que cambiarlos por los de la
                // versión reducida, o el fitness contaría maletas que ya no están ahí.
                baseIdsToCheck.add(baseBatchId(entry.getKey()));
                fitnessTracker.replacePendingEvents(previous, current);
                entry.setValue(current);
            }
            // else: intacta este ciclo — NO se agrega su base (ver javadoc).
        }
        for (ShipmentBatch batch : cycleBatches) {
            baseIdsToCheck.add(batch.batchId());
        }

        for (String baseId : baseIdsToCheck) {
            for (int i = 1; ; i++) {
                String subId = baseId + "-S" + i;
                AssignedRoute sub = accumulated.getRoute(subId);
                if (sub == null) {
                    break;
                }
                if (frenteCaliente.putIfAbsent(subId, sub) == null) {
                    // Recién descubierto: sus eventos de almacén todavía no estaban en la
                    // cola pendiente del tracker (a diferencia de las rutas de finalSolution,
                    // que ya se encolaron en la acumulación) — hay que agregarlos ahora.
                    fitnessTracker.trackPendingEvents(List.of(sub));
                }
            }
        }
    }

    /**
     * Mueve del frente caliente al fitnessTracker las rutas cuyo primer vuelo YA salió
     * respecto a {@code windowStart} — splitting nunca vuelve a tocarlas (mismo criterio que
     * usa applyCapacityAwareSplitting para saltarlas), así que su SLA/escala/capacidad de
     * vuelo ya son seguros de sellar para siempre (sus eventos de almacén NO se sellan aquí
     * — ver {@link AccumulatedFitnessTracker#advanceStorageWatermark}, que exige además
     * orden cronológico). Acotado por el tamaño de frenteCaliente, no por la historia
     * acumulada.
     */
    private void promoteSettledFrenteCaliente(ZonedDateTime windowStart, SolutionEvaluator evaluator) {
        List<AssignedRoute> toPromote = new ArrayList<>();
        Iterator<Map.Entry<String, AssignedRoute>> it = frenteCaliente.entrySet().iterator();
        while (it.hasNext()) {
            AssignedRoute route = it.next().getValue();
            if (route.getFlights().get(0).departureTime().isBefore(windowStart)) {
                toPromote.add(route);
                it.remove();
            }
        }
        if (!toPromote.isEmpty()) {
            fitnessTracker.recordSettledRoutes(toPromote, evaluator);
        }
    }

    /**
     * Cota segura para sellar eventos de almacén (ver
     * {@link AccumulatedFitnessTracker#advanceStorageWatermark}): ningún evento aún no visto
     * puede tener fecha anterior a esto, Y ninguna ruta que todavía pueda MODIFICARSE
     * (splitting) puede tener un evento ya sellado — si no, un peel posterior no podría
     * deshacer un evento que ya quedó fijo para siempre con la cantidad vieja. Tres fuentes:
     * <ul>
     *   <li>{@code windowStart}: ningún lote nuevo de ciclos futuros puede ingresar antes.</li>
     *   <li>Los reintentos en {@link #carryoverBatches}: conservan su ingressTime ORIGINAL
     *       (de un ciclo anterior) y podrían generar un evento con esa fecha en un ciclo
     *       futuro cuando por fin obtengan ruta.</li>
     *   <li><b>El evento más antiguo de cualquier ruta que SIGUE en {@link #frenteCaliente}</b>
     *       (tras las promociones de este ciclo): mientras applyCapacityAwareSplitting pueda
     *       todavía partirla, NINGUNO de sus eventos es seguro de sellar — ni siquiera el más
     *       temprano (p. ej. la llegada al origen), aunque su fecha ya haya pasado, porque un
     *       peel más tarde cambiaría también esa cantidad.</li>
     * </ul>
     */
    private ZonedDateTime storageEventWatermark(ZonedDateTime windowStart) {
        ZonedDateTime watermark = windowStart;
        for (ShipmentBatch batch : carryoverBatches) {
            if (batch.ingressTime().isBefore(watermark)) {
                watermark = batch.ingressTime();
            }
        }
        for (AssignedRoute route : frenteCaliente.values()) {
            for (StorageEvent event : route.getStorageEvents()) {
                if (event.timestamp().isBefore(watermark)) {
                    watermark = event.timestamp();
                }
            }
        }
        return watermark;
    }

    /**
     * División de envíos por capacidad (sub-lotes), aplicada en CADA ciclo y a TODOS los casos.
     *
     * <p>El algoritmo asigna cada lote de forma atómica y la capacidad de vuelo es una
     * restricción BLANDA (solo penalizada), por lo que un envío puede quedar asignado a un vuelo
     * que excede su capacidad. Este paso lo corrige de forma general:</p>
     * <ol>
     *   <li><b>Despegue de exceso:</b> en los vuelos sobre-capacidad, se "despega" el exceso de
     *       maletas de los envíos que los usan (los más grandes primero) — las maletas que SÍ
     *       caben se quedan en el vuelo y el resto se vuelve un remanente a reubicar.</li>
     *   <li><b>Reubicación:</b> cada remanente (y los lotes que quedaron sin ruta) se divide en el
     *       espacio libre de otros vuelos directos y, si hace falta, en una ruta con escalas. Así
     *       las maletas de un mismo envío viajan en vuelos distintos cuando uno solo no alcanza.</li>
     * </ol>
     * <p>Es determinista, aditivo y solo actúa si hay exceso real, por lo que no degrada la
     * calidad (de hecho elimina penalizaciones por capacidad) ni el rendimiento.</p>
     *
     * @return número de sub-lotes (divisiones) generados
     */
    private int applyCapacityAwareSplitting(Solution solution, List<ShipmentBatch> cycleBatches,
                                            ZonedDateTime windowStart, ZonedDateTime windowEnd) {
        // 1. Capacidad usada por vuelo + índices (vuelo→capacidad, vuelo→lotes que lo usan).
        //    SOLO vuelos "vivos" — sin recorrer la solución acumulada completa: un vuelo que ya
        //    salió jamás puede volver a estar sobre-capacidad (su carga quedó fija cuando
        //    despegó) ni recibir maletas nuevas (RouteGenerator solo asigna a vuelos futuros),
        //    así que su valor en usedByFlight nunca se vuelve a consultar aquí.
        //      - activeFlightLoad (tracker): rutas ya asentadas, NO peelables (su primer vuelo
        //        ya salió) pero su carga sigue sumando al total del vuelo compartido.
        //      - frenteCaliente: las ÚNICAS rutas que este método puede modificar — también
        //        aportan a usedByFlight Y a batchesByFlight (candidatas a peel).
        Map<String, Integer> usedByFlight = new HashMap<>();
        Map<String, Flight> flightById = new HashMap<>();
        Map<String, List<String>> batchesByFlight = new HashMap<>();

        for (Map.Entry<Flight, Integer> entry : fitnessTracker.snapshotActiveFlightLoad().entrySet()) {
            usedByFlight.merge(entry.getKey().flightId(), entry.getValue(), Integer::sum);
            flightById.putIfAbsent(entry.getKey().flightId(), entry.getKey());
        }
        for (AssignedRoute route : frenteCaliente.values()) {
            int qty = route.getBatch().quantity();
            String bId = route.getBatch().batchId();
            for (Flight f : route.getFlights()) {
                usedByFlight.merge(f.flightId(), qty, Integer::sum);
                flightById.putIfAbsent(f.flightId(), f);
                batchesByFlight.computeIfAbsent(f.flightId(), k -> new ArrayList<>()).add(bId);
            }
        }

        // Lotes que YA tenían ruta antes de este paso (para no recontarlos como "sin ruta").
        // Acotado a los lotes de ESTE ciclo — son los únicos que el paso 3 consulta. La misma
        // pasada siembra hubCapacity: línea base real (pendingStorageBaseline, calculada UNA
        // vez por ciclo antes de GA/Tabú — ver applyStorageBaseline) + las rutas que este ciclo
        // ya agregó, acotado a cycleBatches — NUNCA se recorre la solución acumulada completa.
        // placeBagsInLeftover la usa para no reubicar maletas en un almacén sin espacio real:
        // a diferencia de la capacidad de vuelo, la de almacén no tiene corrección posterior.
        Set<String> hadRoute = new HashSet<>();
        CapacityContext hubCapacity = CapacityContext.fromBaseline(pendingStorageBaseline);
        for (ShipmentBatch batch : cycleBatches) {
            AssignedRoute existingRoute = solution.getRoute(batch.batchId());
            if (existingRoute != null) {
                hadRoute.add(batch.batchId());
                hubCapacity.applyRoute(existingRoute);
            }
        }

        // Contador de sufijos -S por lote base, sembrado con los sub-lotes YA existentes para
        // garantizar IDs únicos (addRoute reemplaza por batchId → un choque perdería maletas).
        // Acotado a los base-ids que podrían necesitar un sub-lote NUEVO este ciclo: los de
        // frenteCaliente (únicas rutas peelables) y los de los lotes de este ciclo — el mismo
        // universo acotado que usa refreshFrenteCalienteAfterSplitting para descubrir splits.
        Map<String, Integer> splitCounter = new HashMap<>();
        Set<String> splitCounterBases = new HashSet<>();
        for (AssignedRoute route : frenteCaliente.values()) {
            splitCounterBases.add(baseBatchId(route.getBatch().batchId()));
        }
        for (ShipmentBatch batch : cycleBatches) {
            splitCounterBases.add(batch.batchId());
        }
        for (String base : splitCounterBases) {
            int maxIdx = 0;
            for (int i = 1; solution.getRoute(base + "-S" + i) != null; i++) {
                maxIdx = i;
            }
            splitCounter.put(base, maxIdx);
        }

        // 2. Despegar el exceso de los vuelos sobre-capacidad → remanentes a reubicar.
        List<RemainderLot> remainders = new ArrayList<>();
        List<String> overCapacity = usedByFlight.entrySet().stream()
            .filter(e -> {
                Flight f = flightById.get(e.getKey());
                return f != null && e.getValue() > f.capacity();
            })
            .map(Map.Entry::getKey)
            .sorted()
            .toList();

        for (String flightId : overCapacity) {
            Flight f = flightById.get(flightId);
            int overflow = usedByFlight.getOrDefault(flightId, 0) - f.capacity();
            if (overflow <= 0) continue;

            // Rutas que usan este vuelo, las más grandes primero (desempate determinista por id).
            List<AssignedRoute> routesHere = new ArrayList<>();
            for (String bId : batchesByFlight.getOrDefault(flightId, List.of())) {
                AssignedRoute r = solution.getRoute(bId);
                if (r != null) routesHere.add(r);
            }
            routesHere.sort(Comparator
                .comparingInt((AssignedRoute r) -> r.getBatch().quantity()).reversed()
                .thenComparing(r -> r.getBatch().batchId()));

            for (AssignedRoute r : routesHere) {
                if (overflow <= 0) break;
                // FRENTE CALIENTE: solo se modifican rutas cuyo primer vuelo aún NO salió.
                // Una ruta con salida en el pasado es físicamente inmutable (las maletas ya
                // volaron); tocarla sería retroactivo y además desperdicia CPU en el ciclo.
                if (r.getFlights().get(0).departureTime().isBefore(windowStart)) continue;
                ShipmentBatch b = r.getBatch();
                int peel = Math.min(overflow, b.quantity());
                if (peel <= 0) continue;
                int newQty = b.quantity() - peel;
                // Reducir la ruta en TODOS sus tramos (libera capacidad también en escalas).
                for (Flight g : r.getFlights()) {
                    usedByFlight.merge(g.flightId(), -peel, Integer::sum);
                }
                hubCapacity.removeRoute(r);
                if (newQty >= MIN_FILL_BAGS) {
                    try {
                        ShipmentBatch reduced = new ShipmentBatch(
                            b.batchId(), b.airportBatchId(), b.clientId(),
                            b.origin(), b.destination(), newQty, b.ingressTime());
                        AssignedRoute reducedRoute = new AssignedRoute(reduced, r.getFlights());
                        solution.addRoute(reducedRoute); // reemplaza por batchId
                        hubCapacity.applyRoute(reducedRoute);
                    } catch (Exception e) {
                        solution.removeRoute(b.batchId());
                    }
                } else {
                    solution.removeRoute(b.batchId());
                }
                remainders.add(new RemainderLot(b, baseBatchId(b.batchId()), peel));
                overflow -= peel;
            }
        }

        // 3. Añadir los lotes que NUNCA tuvieron ruta como remanentes (división de extremo a extremo).
        for (ShipmentBatch batch : cycleBatches) {
            if (hadRoute.contains(batch.batchId())) continue;
            if (solution.getRoute(batch.batchId()) != null) continue;
            remainders.add(new RemainderLot(batch, baseBatchId(batch.batchId()), batch.quantity()));
        }

        // 4. Reubicar cada remanente en el espacio libre (directo y, si hace falta, con escalas).
        int splitsGenerated = 0;
        for (RemainderLot rem : remainders) {
            splitsGenerated += placeBagsInLeftover(
                solution, usedByFlight, hubCapacity, splitCounter, rem, windowStart, windowEnd);
        }
        return splitsGenerated;
    }

    /**
     * Coloca {@code rem.quantity()} maletas en el espacio libre de vuelos directos y, para el
     * remanente, en una ruta con escalas. Divide en tantos sub-lotes como vuelos haga falta.
     *
     * @return número de sub-lotes creados para este remanente
     */
    private int placeBagsInLeftover(Solution solution, Map<String, Integer> usedByFlight,
                                    CapacityContext hubCapacity, Map<String, Integer> splitCounter, RemainderLot rem,
                                    ZonedDateTime windowStart, ZonedDateTime windowEnd) {
        int remaining = rem.quantity();
        if (remaining < MIN_FILL_BAGS) return 0;
        ShipmentBatch t = rem.template();
        int placed = 0;

        // Vuelos directos origen→destino con espacio libre. departureTime >= windowStart es
        // OBLIGATORIO (no solo >= t.ingressTime()): con el ingressTime de un remanente que
        // viene arrastrándose de ciclos anteriores (peel repetido), un vuelo ya salido para
        // el reloj ACTUAL todavía podría cumplir ">= ingressTime" si ese ingreso es viejo.
        // usedByFlight ya no incluye vuelos asentados (ver applyCapacityAwareSplitting), así
        // que sin este filtro un vuelo cerrado se vería con toda su capacidad libre.
        // meetsSLA(f.arrivalTime()) es OBLIGATORIO: a diferencia de RouteGenerator (que nunca
        // deja pasar un camino que incumpla el SLA), esta colocación directa no pasaba por
        // ningún chequeo de SLA — un vuelo con espacio libre pero que llega después del
        // deadline del lote se aceptaba igual, generando entregas "retrasadas" reales (no
        // cosmético: AssignedRoute.meetsSLA() ya devolvía false para esa ruta).
        List<Flight> directFlights = flightPlan.getFlightsFromAirport(t.origin(), windowStart, windowEnd)
            .stream()
            .filter(f -> f.destination().equals(t.destination()))
            .filter(f -> !f.departureTime().isBefore(t.ingressTime()))
            .filter(f -> !f.departureTime().isBefore(windowStart))
            .filter(f -> t.meetsSLA(f.arrivalTime()))
            .sorted(Comparator.comparing(Flight::departureTime))
            .toList();

        for (Flight f : directFlights) {
            if (remaining < MIN_FILL_BAGS) break;
            int leftover = f.capacity() - usedByFlight.getOrDefault(f.flightId(), 0);
            if (leftover < MIN_FILL_BAGS) continue;
            // Capacidad DURA de almacén en destino — nunca se relaja (mismo criterio que
            // CapacityContext.hasHubCapacity en RouteGenerator): sin esto, un remanente podía
            // reubicarse en un vuelo directo con espacio de sobra pero cuyo destino ya no
            // tiene almacén libre, empujándolo sobre el 100%.
            int hubResidual = t.destination().storageCapacity() - hubCapacity.storageOccupancy(t.destination());
            if (hubResidual < MIN_FILL_BAGS) continue;
            int originResidual = hubCapacity.storageResidual(t.origin());
            if (originResidual < MIN_FILL_BAGS) continue;
            int take = Math.min(Math.min(leftover, remaining), Math.min(hubResidual, originResidual));
            int idx = splitCounter.merge(rem.baseId(), 1, Integer::sum);
            String subId = rem.baseId() + "-S" + idx;
            try {
                ShipmentBatch subLot = new ShipmentBatch(
                    subId, t.airportBatchId() + "-S" + idx, t.clientId(),
                    t.origin(), t.destination(), take, t.ingressTime());
                AssignedRoute subRoute = new AssignedRoute(subLot, List.of(f));
                solution.addRoute(subRoute);
                usedByFlight.merge(f.flightId(), take, Integer::sum);
                hubCapacity.applyRoute(subRoute);
                remaining -= take;
                placed++;
            } catch (Exception e) {
                splitCounter.merge(rem.baseId(), -1, Integer::sum); // revertir índice no usado
            }
        }

        // Multi-hop para el remanente: ruta con escalas verificando capacidad en TODOS los tramos
        // (vuelo Y almacén — hubCapacity va al generador, así que la búsqueda misma ya descarta
        // hubs sin espacio real; ver RouteGenerator.generateFeasibleRoute).
        if (remaining >= MIN_FILL_BAGS && fillRouteGenerator != null) {
            try {
                int idx = splitCounter.merge(rem.baseId(), 1, Integer::sum);
                String subId = rem.baseId() + "-S" + idx;
                ShipmentBatch probe = new ShipmentBatch(
                    subId, t.airportBatchId() + "-S" + idx, t.clientId(),
                    t.origin(), t.destination(), remaining, t.ingressTime());
                AssignedRoute probeRoute = fillRouteGenerator.generateFeasibleRoute(probe, hubCapacity);
                // Mismo resguardo que en el directo: fillRouteGenerator busca desde
                // t.ingressTime() (puede ser viejo en un remanente arrastrado), así que puede
                // devolver un tramo ya salido para el reloj actual — usedByFlight no lo vería.
                boolean anyLegAlreadyDeparted = probeRoute != null && probeRoute.getFlights().stream()
                    .anyMatch(f -> f.departureTime().isBefore(windowStart));
                if (probeRoute != null && !anyLegAlreadyDeparted && probeRoute.getFlights().size() > 1) {
                    int take = remaining;
                    for (Flight f : probeRoute.getFlights()) {
                        take = Math.min(take, f.capacity() - usedByFlight.getOrDefault(f.flightId(), 0));
                    }
                    if (take >= MIN_FILL_BAGS) {
                        AssignedRoute route = take == remaining ? probeRoute : new AssignedRoute(
                            new ShipmentBatch(subId, t.airportBatchId() + "-S" + idx, t.clientId(),
                                t.origin(), t.destination(), take, t.ingressTime()),
                            probeRoute.getFlights());
                        solution.addRoute(route);
                        for (Flight f : route.getFlights()) {
                            usedByFlight.merge(f.flightId(), take, Integer::sum);
                        }
                        hubCapacity.applyRoute(route);
                        remaining -= take;
                        placed++;
                    } else {
                        splitCounter.merge(rem.baseId(), -1, Integer::sum);
                    }
                } else {
                    splitCounter.merge(rem.baseId(), -1, Integer::sum);
                }
            } catch (Exception ignored) {
                // Sin ruta multi-hop factible → el remanente queda sin ubicar este ciclo.
            }
        }
        return placed;
    }

    /** Remanente de maletas a reubicar; {@code template} aporta origen/destino/cliente/ingreso. */
    private record RemainderLot(ShipmentBatch template, String baseId, int quantity) {}

    /** Quita los sufijos "-S&lt;n&gt;" finales para obtener el id base del lote. */
    private static String baseBatchId(String id) {
        String s = id;
        while (true) {
            int idx = s.lastIndexOf("-S");
            if (idx < 0 || idx + 2 >= s.length()) break;
            String suffix = s.substring(idx + 2);
            if (!suffix.chars().allMatch(Character::isDigit)) break;
            s = s.substring(0, idx);
        }
        return s.isEmpty() ? id : s;
    }

    private void logQualityMetrics(
            List<ShipmentBatch> cycleBatches,
            Solution cycleSolution,
            Solution accumulatedSolution,
            ValidationReport validationReport) {
        int cycleBatchCount = cycleBatches.size();
        int cycleRoutes = cycleSolution.getRoutes().size();
        double assignmentRate = cycleBatchCount > 0 ? cycleRoutes * 100.0 / cycleBatchCount : 100.0;

        long accumulatedRoutes = accumulatedSolution.getRoutes().size();
        long accumulatedSlaOk = accumulatedSolution.getRoutes().values().stream()
            .filter(AssignedRoute::meetsSLA)
            .count();
        double slaRate = accumulatedRoutes > 0 ? accumulatedSlaOk * 100.0 / accumulatedRoutes : 100.0;

        double avgLegs = accumulatedSolution.getRoutes().values().stream()
            .mapToInt(route -> route.getFlights().size())
            .average()
            .orElse(0.0);

        long flightViolations = validationReport.getViolations().stream()
            .filter(v -> v.type() == ViolationType.FLIGHT_CAPACITY)
            .count();
        long storageViolations = validationReport.getViolations().stream()
            .filter(v -> v.type() == ViolationType.STORAGE_CAPACITY)
            .count();
        long slaViolations = validationReport.getViolations().stream()
            .filter(v -> v.type() == ViolationType.SLA_VIOLATION)
            .count();
        long layoverViolations = validationReport.getViolations().stream()
            .filter(v -> v.type() == ViolationType.LAYOVER_VIOLATION)
            .count();

        System.out.printf(
            "📈 Calidad ciclo: asignación=%.1f%% (%d/%d), SLA acumulado=%.1f%%, vuelos/ruta=%.2f, violaciones [vuelo=%d, almacén=%d, SLA=%d, escala=%d]%n",
            assignmentRate,
            cycleRoutes,
            cycleBatchCount,
            slaRate,
            avgLegs,
            flightViolations,
            storageViolations,
            slaViolations,
            layoverViolations
        );
    }
    
    /**
     * Ejecuta simulación completa con ciclos de planificación.
     * 
     * Proceso:
     * - Ejecutar ciclos cada Sa minutos
     * - Continuar hasta que no haya más pedidos o se alcance límite
     * 
     * @param startTime Tiempo de inicio de la simulación
     * @param maxCycles Número máximo de ciclos (0 = sin límite)
     * @return Solución final
     * 
     * **Validates: Requirements 21.1, 21.2, 21.3, 21.4, 22.1, Caso de estudio punto a, b**
     */
    public Solution run(ZonedDateTime startTime, int maxCycles) {
        System.out.println("=".repeat(80));
        System.out.println("INICIANDO SCHEDULER");
        System.out.println("Algoritmo: " + algorithmType.getDisplayName());
        System.out.println("Parámetros: Ta=" + taSeconds + "s, Sa=" + saSeconds + "s, K=" + K + ", Sc=" + Sc + " min");
        System.out.println("=".repeat(80));
        
        ZonedDateTime currentTime = startTime;
        int cycle = 0;
        
        while (shipmentQueue.getPendingCount() > 0) {
            cycle++;
            System.out.println("\n--- CICLO " + cycle + " ---");
            
            // Ejecutar ciclo de planificación
            executePlanningCycle(currentTime);
            
            // Avanzar tiempo simulado en Sc minutos (= Sa × K)
            currentTime = currentTime.plusMinutes(Sc);
            
            // Verificar límite de ciclos
            if (maxCycles > 0 && cycle >= maxCycles) {
                System.out.println("\nLímite de ciclos alcanzado: " + maxCycles);
                break;
            }
        }
        
        System.out.println("\n" + "=".repeat(80));
        System.out.println("SCHEDULER COMPLETADO");
        System.out.println("Algoritmo usado: " + algorithmType.getDisplayName());
        System.out.println("Ciclos ejecutados: " + cycle);
        System.out.println("Fitness final: " + String.format("%.2f", currentSolution.getFitness()));
        System.out.println("=".repeat(80));
        
        return currentSolution;
    }
    
    /**
     * Obtiene el tipo de algoritmo configurado.
     * 
     * @return Tipo de algoritmo
     */
    public AlgorithmType getAlgorithmType() {
        return algorithmType;
    }

    /**
     * Agrega un nuevo lote transaccional a la cola para el próximo ciclo.
     */
    public void addShipment(ShipmentBatch batch) {
        shipmentQueue.addShipment(batch);
    }

    /**
     * Registra la ocupación de almacén preexistente (rutas ya planificadas en ciclos
     * anteriores) para el PRÓXIMO ciclo. Se aplica a los evaluadores de GA/Tabú solo
     * mientras se optimiza la ventana nueva, y se retira antes de re-evaluar la solución
     * ACUMULADA (que ya contiene esas rutas — mantenerla contaría la carga dos veces).
     */
    public void setStorageBaseline(Map<Airport, Integer> baseline) {
        this.pendingStorageBaseline = baseline;
    }

    private Map<Airport, Integer> pendingStorageBaseline;

    /** Aplica (o retira, con null) la línea base en TODOS los evaluadores involucrados. */
    private void applyStorageBaseline(Map<Airport, Integer> baseline) {
        evaluator.setStorageBaseline(baseline);
        if (primaryAlgorithm instanceof GeneticAlgorithm ga) {
            ga.setStorageBaseline(baseline);
        } else if (primaryAlgorithm instanceof TabuSearch tabuPrimary) {
            tabuPrimary.setStorageBaseline(baseline);
        }
        tabuSearch.setStorageBaseline(baseline);
    }

    /**
     * Retorna la cantidad de lotes pendientes en cola.
     */
    public int getPendingCount() {
        return shipmentQueue.getPendingCount();
    }
    
    /**
     * Obtiene la solución actual del sistema.
     * 
     * @return Solución actual
     */
    public Solution getCurrentSolution() {
        return currentSolution;
    }
    
    /**
     * Actualiza la solución actual del sistema.
     * Usado para replanificación de emergencia.
     * 
     * @param newSolution Nueva solución
     * 
     * **Validates: Requirements 12.6, 24.5**
     */
    public void updateSolution(Solution newSolution) {
        this.currentSolution = Objects.requireNonNull(newSolution, "New solution cannot be null");

        // La replanificación de emergencia puede reemplazar CUALQUIER ruta (incluidas las que
        // fitnessTracker ya daba por selladas para siempre) — su estado incremental ya no es
        // confiable. Reset pesimista: TODA ruta de la nueva solución vuelve al frente caliente;
        // el próximo ciclo la clasifica correctamente en su primer settleExpiredFlights/
        // promoteSettledFrenteCaliente (mismo criterio de siempre, sin necesitar la hora
        // actual aquí). Costo O(rutas) UNA VEZ, en un evento raro y administrativo — no en
        // el loop de ciclos.
        fitnessTracker.reset();
        frenteCaliente.clear();
        frenteCaliente.putAll(newSolution.getRoutes());
        // Sus eventos de almacén también deben entrar a la cola pendiente del tracker —
        // igual que se hace para las rutas nuevas de un ciclo normal — o quedarían
        // invisibles para siempre en currentFitness/advanceStorageWatermark.
        fitnessTracker.trackPendingEvents(newSolution.getRoutes().values());

        System.out.println("✓ Solución actualizada en Scheduler");
    }
}
