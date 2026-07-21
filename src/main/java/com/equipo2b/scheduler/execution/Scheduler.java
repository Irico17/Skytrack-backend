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
    // Tamaño mínimo de un sub-lote NUEVO (evita fragmentar en sub-lotes de 1-2 maletas, cuyo
    // overhead de tracking/gestión es desproporcionado). Antes en 1: fragmentaba libremente.
    // EXCEPCIÓN de último recurso (ver placeBagsInLeftover): si el remanente COMPLETO a
    // reubicar ya es menor que este mínimo, se coloca igual relajando el mínimo a 1 — mejor
    // ubicar 2 maletas que perderlas (la alternativa es que queden sin ruta este ciclo y
    // dependan de que el próximo ciclo, vía carryoverBatches, les encuentre hueco por la vía
    // normal de GA/Tabú, sin ninguna garantía de que lo consiga tampoco).
    private static final int MIN_FILL_BAGS = 3;

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
    
    // Solución actual del sistema. volatile: el hilo de storage de SimulationController
    // (startStorageUpdateLoop) la lee en cada tick sin ninguna sincronización — sin volatile,
    // ese hilo podría ver una referencia obsoleta (o, en el peor caso, un objeto a medio
    // publicar) tras la reasignación al final de executePlanningCycle/updateSolution, que
    // corre en el hilo del loop de ciclos.
    private volatile Solution currentSolution;

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
        // Rutas creadas/reemplazadas por el splitting (peel + sub-lotes nuevos, directo y
        // multi-hop) — se llena dentro de applyCapacityAwareSplitting/placeBagsInLeftover.
        // Ver validación en el paso 6: antes quedaban sin validar porque el splitting corre
        // DESPUÉS de validator.validate(finalSolution).
        List<AssignedRoute> splitTouchedRoutes = new ArrayList<>();
        if (partialFillEnabled && flightPlan != null) {
            int splits = applyCapacityAwareSplitting(accumulatedSolution, batches, windowStart, windowEnd, splitTouchedRoutes);
            if (splits > 0) {
                System.out.println("🧩 División por capacidad: " + splits + " sub-lotes ubicados (envíos divididos en vuelos distintos)");
            }
            refreshFrenteCalienteAfterSplitting(accumulatedSolution, splitTouchedRoutes);
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

        // 6c. Validar también las rutas TOCADAS por el splitting (5b): applyCapacityAwareSplitting
        //     muta accumulatedSolution DESPUÉS de que finalSolution ya fue validada arriba, así
        //     que sus rutas nuevas/reemplazadas (peel + sub-lotes "-S<n>") quedaban sin pasar
        //     nunca por el validador. Alcance acotado a las rutas efectivamente tocadas este
        //     ciclo (no toda accumulatedSolution — mismo criterio de costo que el punto 6):
        //     detecta violaciones de SLA/escala de forma exacta (son intrínsecas a cada ruta) y
        //     de capacidad cuando DOS sub-lotes tocados este ciclo comparten vuelo/almacén y se
        //     pasan entre sí — un bug real en el bookkeeping de usedByFlight/hubCapacity se vería
        //     aquí. No detecta un exceso que solo aparece al combinarse con una ruta de
        //     frenteCaliente que NO fue tocada este ciclo (ya se contabilizó correctamente vía
        //     usedByFlight/hubCapacity al construirse, así que no se re-verifica por completo).
        if (!splitTouchedRoutes.isEmpty()) {
            Solution splitCheckSolution = new Solution();
            for (AssignedRoute route : splitTouchedRoutes) {
                splitCheckSolution.addRoute(route);
            }
            ValidationReport splitValidationReport = validator.validate(splitCheckSolution);
            if (!splitValidationReport.isValid()) {
                System.out.println("⚠ Sub-rutas del splitting con violaciones:");
                System.out.println(splitValidationReport.getSummary());
            }
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
     *
     * <p><b>Contabilidad POR MALETAS (no por lote completo):</b> un lote puede haber colocado
     * SOLO UNA PARTE vía sub-lotes ("-S&lt;n&gt;") — p. ej. B16-S1 con 30 de 100 maletas. Antes
     * se usaba {@code isRouted} (todo/nada): bastaba con que existiera CUALQUIER "-S&lt;n&gt;"
     * para dar el lote entero por enrutado, y las 70 maletas restantes jamás se
     * replanificaban (evaporación silenciosa). Ahora se compara la cantidad REALMENTE enrutada
     * (ver {@link #routedQuantity}) contra {@code batch.quantity()}, y solo el FALTANTE entra a
     * SLA/factibilidad/carryover.</p>
     *
     * <p><b>Id del lote reducido</b> (ver {@link #freshCarryoverId}): cuelga de la misma base
     * ORIGINAL (sin más que un {@code baseBatchId} de distancia, sin importar cuántos niveles de
     * carryover lleve encadenados), pero NUNCA reutiliza literalmente un id que ya tenga ruta en
     * {@code accumulated} — si la base ya tiene una ruta propia (p. ej. quedó una porción en el
     * vuelo original tras un peel), usa el siguiente sufijo "-S&lt;n&gt;" libre bajo esa base en
     * vez de la base "a secas". Esto es OBLIGATORIO: si se reutilizara el id de una ruta que YA
     * existe en {@code accumulated} (p. ej. la base "B16" con 30 maletas ya colocadas), el
     * algoritmo del próximo ciclo generaría una ruta NUEVA con ese mismo id para las maletas
     * FALTANTES, y {@code accumulatedSolution.addRoute} (que reemplaza por batchId) la
     * SOBRESCRIBIRÍA — perdiendo silenciosamente las 30 maletas ya colocadas. Con la base libre
     * (caso común: lote nunca antes rebajado ni dividido) sí se conserva el id base tal cual —
     * así {@code routedQuantity}/applyCapacityAwareSplitting siguen sumando correctamente y el
     * comportamiento coincide con el de un reintento simple (ver SchedulerRetryTest).</p>
     *
     * <p><b>Unicidad de ids entre ciclos (por construcción):</b> TODOS los splits anidan bajo
     * el id del lote fuente — la semilla del GA (splitPortion) y los remanentes del splitting
     * (placeBagsInLeftover) acuñan {@code idFuente + "-S<n>"}, nunca aplanan a la base — y
     * {@link #freshCarryoverId} solo entrega ids cuyo subárbol completo está libre en la
     * solución acumulada. Como cada lote (original o de carryover) se procesa en UN solo ciclo
     * y su subárbol nace vacío, los ids nuevos no pueden colisionar con rutas de ciclos
     * anteriores, y {@link #routedQuantity} (suma recursiva del subárbol) los ve todos.</p>
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
        int routedBags = 0;
        for (ShipmentBatch batch : batches) {
            totalBags += batch.quantity();
            int routed = routedQuantity(accumulated, batch.batchId());
            routedBags += routed;  // sin recortar a quantity(): si excede, el cuadre de abajo lo delata
            int missing = batch.quantity() - routed;
            if (missing <= 0) {
                continue;
            }
            ZonedDateTime slaDeadline = batch.ingressTime().plus(batch.calculateSLA());
            if (!slaDeadline.isAfter(windowEnd)) {
                expired++;
                expiredBags += missing;
                continue;
            }
            if (!hasStructuralPathCached(batch)) {
                structural++;
                structuralBags += missing;
                continue;
            }
            // Lote reducido con el FALTANTE; id calculado por freshCarryoverId (ver javadoc del
            // método: base "a secas" si está libre, si no el siguiente sufijo "-S<n>" libre bajo
            // esa base — NUNCA reutiliza un id con ruta ya existente en accumulated).
            String carryoverId = freshCarryoverId(accumulated, baseBatchId(batch.batchId()));
            ShipmentBatch reduced = new ShipmentBatch(
                carryoverId, batch.airportBatchId(), batch.clientId(),
                batch.origin(), batch.destination(), missing, batch.ingressTime());
            carryoverBatches.add(reduced);
            newCarryover++;
            newCarryoverBags += missing;
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

        // Cuadre por ciclo (red de seguridad): las maletas consumidas este ciclo deben repartirse
        // EXACTAMENTE entre enrutadas, a reintento, con SLA vencido y estructurales — ninguna
        // puede desaparecer ni duplicarse. routedBags se deja SIN recortar a quantity() por lote
        // (ver arriba) precisamente para que un bug de doble conteo en routedQuantity/splitting
        // se delate aquí como descuadre, en vez de quedar enmascarado.
        int accountedBags = routedBags + newCarryoverBags + expiredBags + structuralBags;
        System.out.printf(
            "📒 Cuadre ciclo: %d maletas consumidas = %d enrutadas + %d a reintento + %d SLA vencido + %d estructurales%s%n",
            totalBags, routedBags, newCarryoverBags, expiredBags, structuralBags,
            accountedBags == totalBags ? "" : " ⚠ DESCUADRE (sumó " + accountedBags + ", esperado " + totalBags + ")"
        );
    }

    /**
     * Cache de {@link TabuSearch#hasFeasiblePathIgnoringCapacity} por (origen, destino,
     * ingreso). El resultado SOLO depende del plan de vuelos (estático entre ciclos) y de esos
     * tres datos — un lote de reintento conserva su ingressTime original, así que bajo
     * sobrecarga (backlog de cientos de lotes re-clasificados CADA ciclo) este chequeo repetía
     * el mismo Dijkstra completo una y otra vez para claves idénticas: era uno de los
     * responsables de que el ciclo excediera Ta por minutos en fechas densas. Se invalida en
     * {@link #updateSolution} (replanificación de emergencia = hubo cancelación de vuelo, lo
     * único que puede cambiar la respuesta) y se acota en tamaño por si acaso.
     */
    private final Map<String, Boolean> structuralFeasibilityCache = new HashMap<>();
    private static final int MAX_STRUCTURAL_CACHE_ENTRIES = 20_000;

    private boolean hasStructuralPathCached(ShipmentBatch batch) {
        String key = batch.origin().id() + ">" + batch.destination().id()
            + "@" + batch.ingressTime().toEpochSecond();
        Boolean cached = structuralFeasibilityCache.get(key);
        if (cached != null) {
            return cached;
        }
        if (structuralFeasibilityCache.size() >= MAX_STRUCTURAL_CACHE_ENTRIES) {
            structuralFeasibilityCache.clear();
        }
        boolean feasible = tabuSearch.hasFeasiblePathIgnoringCapacity(batch);
        structuralFeasibilityCache.put(key, feasible);
        return feasible;
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
     * Maletas REALMENTE enrutadas de un lote en la solución acumulada: la cantidad de la ruta
     * con el id del lote (si existe) más las de TODO su subárbol de sub-lotes anidados
     * ("id-S1", "id-S1-S1", "id-S2", ...). Reemplaza al antiguo {@code isRouted} (todo/nada):
     * un split parcial (p. ej. B16-S1 con 30 de 100 maletas) antes se consideraba "enrutado"
     * completo — ver javadoc de {@link #registerUnroutedForRetry}.
     *
     * <p><b>Por qué recursivo y tolerante a huecos:</b> los splits ANIDAN bajo el id fuente
     * (semilla GA y remanentes del splitting — ver splitPortion/placeBagsInLeftover), así que
     * las porciones de un lote pueden vivir a más de un nivel de profundidad ("B1-S2" partido
     * en "B1-S2-S1"+"B1-S2-S2", y su cola otra vez en "B1-S2-S2-S1"). Además un sub-lote
     * puede DESAPARECER dejando un hueco en la numeración (peel que cae bajo MIN_FILL_BAGS →
     * removeRoute) — cortar el escaneo en el primer hueco perdería de vista a sus hermanos
     * mayores. Se tolera una racha corta de índices completamente vacíos (sin ruta propia ni
     * hijos) antes de cortar; los sufijos se acuñan secuencialmente, así que huecos más largos
     * no ocurren en la práctica — y si ocurrieran, el cuadre del ciclo los delataría como
     * descuadre. El escaneo sigue acotado al subárbol del lote (nunca recorre la solución
     * acumulada) y la profundidad real es la cantidad de generaciones de split (pequeña).</p>
     */
    private static final int SUBTREE_SCAN_GAP_TOLERANCE = 3;
    private static final int SUBTREE_SCAN_MAX_DEPTH = 5;

    private static int routedQuantity(Solution accumulated, String batchId) {
        return subtreeRoutedQuantity(accumulated, batchId, 0);
    }

    private static int subtreeRoutedQuantity(Solution accumulated, String id, int depth) {
        int total = 0;
        AssignedRoute own = accumulated.getRoute(id);
        if (own != null) {
            total += own.getBatch().quantity();
        }
        if (depth >= SUBTREE_SCAN_MAX_DEPTH) {
            return total;
        }
        int emptyStreak = 0;
        for (int i = 1; emptyStreak < SUBTREE_SCAN_GAP_TOLERANCE; i++) {
            String childId = id + "-S" + i;
            // Pre-chequeo barato antes de recursar: un índice cuenta como "vacío" solo si no
            // tiene ruta propia NI un primer hijo (un nodo absorbido puede conservar hijos).
            if (accumulated.getRoute(childId) == null && accumulated.getRoute(childId + "-S1") == null) {
                emptyStreak++;
                continue;
            }
            emptyStreak = 0;
            total += subtreeRoutedQuantity(accumulated, childId, depth + 1);
        }
        return total;
    }

    /**
     * Id seguro para el lote de carryover reducido de {@code baseId} (ya sin sufijos, ver
     * {@link #baseBatchId}): la base "a secas" SOLO si la familia completa está vacía (ni la
     * base ni "-S1" tienen ruta todavía — lote nunca antes dividido ni rebajado, el caso común:
     * mismo id que usaría un reintento simple, sin sub-lotes de por medio); si no, el siguiente
     * sufijo "-S&lt;n&gt;" libre bajo esa base.
     *
     * <p>Dos peligros distintos si se devolviera un id ya ocupado, ambos reales:</p>
     * <ul>
     *   <li><b>Sobrescritura</b>: el algoritmo del próximo ciclo crea una ruta NUEVA con
     *       exactamente el id del {@link ShipmentBatch} que se le pasa (no sabe nada de "colgar
     *       de una base"), y {@code accumulatedSolution.addRoute} reemplaza por batchId —
     *       reutilizar el id de una ruta YA existente (p. ej. la porción que quedó en el vuelo
     *       original tras un peel) la sobrescribiría en silencio, perdiendo sus maletas.</li>
     *   <li><b>Contaminación cruzada de ciclos en {@link #routedQuantity}</b>: si se reutilizara
     *       la base "a secas" mientras YA existe un sub-lote hermano ("B16-S1") de un ciclo
     *       ANTERIOR, {@code routedQuantity(accumulated, "B16")} en el ciclo SIGUIENTE sumaría
     *       ese hermano viejo (ajeno a este lote de carryover) junto con lo que se rutee este
     *       ciclo — pudiendo superar el {@code quantity()} del lote de carryover y hacer que se
     *       lo dé por "totalmente resuelto" ANTES de que sus propias maletas realmente lo estén
     *       (bug simétrico al que corrige la tarea A: en vez de evaporar maletas, deja de
     *       reintentar unas que en realidad siguen sin ruta). Exigir que TODA la familia (base +
     *       "-S1") esté vacía antes de reusar la base "a secas" evita este cruce por
     *       construcción: cualquier id que devuelve esta función es, desde ese momento en
     *       adelante, de uso EXCLUSIVO de este lote de carryover.</li>
     * </ul>
     *
     * <p><b>Slot libre = subárbol COMPLETO vacío</b>: como los splits anidan bajo el id fuente
     * (splitPortion de la semilla GA y placeBagsInLeftover — "B1-S2" partido genera
     * "B1-S2-S1"), un slot "B1-S&lt;n&gt;" cuyo id no tiene ruta propia puede aún tener HIJOS
     * de un ciclo anterior (el nodo fue absorbido pero sus porciones anidadas viven). Reusar
     * ese slot haría que los splits futuros del nuevo carryover re-acuñaran ids de esos hijos
     * viejos (sobrescritura) y que {@code routedQuantity} sumara maletas ajenas. Por eso el
     * escaneo exige que ni el id ni sus primeros hijos existan, con la misma tolerancia a
     * huecos que {@code subtreeRoutedQuantity} — cualquier anomalía más profunda la delataría
     * el cuadre del ciclo.</p>
     */
    private static String freshCarryoverId(Solution accumulated, String baseId) {
        if (isSubtreeSlotFree(accumulated, baseId)) {
            return baseId;
        }
        int idx = 1;
        while (!isSubtreeSlotFree(accumulated, baseId + "-S" + idx)) {
            idx++;
        }
        return baseId + "-S" + idx;
    }

    /** True si el id no tiene ruta propia ni hijos "-S&lt;i&gt;" (tolerante a huecos cortos). */
    private static boolean isSubtreeSlotFree(Solution accumulated, String id) {
        if (accumulated.getRoute(id) != null) {
            return false;
        }
        for (int i = 1; i <= SUBTREE_SCAN_GAP_TOLERANCE; i++) {
            if (accumulated.getRoute(id + "-S" + i) != null
                    || accumulated.getRoute(id + "-S" + i + "-S1") != null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Re-sincroniza {@link #frenteCaliente} después de que applyCapacityAwareSplitting corrió:
     * splitting puede haber reemplazado (peel) o vaciado por completo cualquier entrada
     * existente, y puede haber creado sub-lotes nuevos ("-S&lt;n&gt;"). Los reemplazos/
     * absorciones se detectan comparando por identidad contra {@code accumulated}; los
     * sub-lotes nuevos llegan por la lista EXPLÍCITA {@code splitTouchedRoutes} que el propio
     * splitting reporta — sin re-escanear familias de ids. (El escaneo por familias que había
     * antes aplanaba a la base y podía redescubrir un sub-lote hermano ya promovido y sacado
     * de frenteCaliente en un ciclo anterior — como {@code accumulated.getRoutes()} nunca
     * olvida nada, "ausente de frenteCaliente" no distingue "nunca visto" de "ya promovido",
     * y volver a agregarlo lo contaba dos veces al fitness al re-promoverlo; con la lista
     * explícita ese modo de fallo no existe por construcción.)</p>
     */
    private void refreshFrenteCalienteAfterSplitting(Solution accumulated, List<AssignedRoute> splitTouchedRoutes) {
        Iterator<Map.Entry<String, AssignedRoute>> it = frenteCaliente.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, AssignedRoute> entry = it.next();
            AssignedRoute previous = entry.getValue();
            AssignedRoute current = accumulated.getRoute(entry.getKey());
            if (current == null) {
                // splitting absorbió esta ruta por completo en otros vuelos: sus eventos de
                // almacén pendientes (encolados cuando se creó, vía trackPendingEvents) ya no
                // corresponden a nada — hay que retirarlos, no quedan huérfanos para siempre.
                fitnessTracker.replacePendingEvents(previous, null);
                it.remove();
            } else if (current != previous) {
                // splitting la reemplazó (peel) por una de menor cantidad: sus eventos
                // pendientes tenían la cantidad ORIGINAL — hay que cambiarlos por los de la
                // versión reducida, o el fitness contaría maletas que ya no están ahí.
                fitnessTracker.replacePendingEvents(previous, current);
                entry.setValue(current);
            }
            // else: intacta este ciclo.
        }

        // Sub-lotes NUEVOS: directamente desde las rutas que el splitting reportó haber tocado
        // (peel reducido + sub-lotes directos y multi-hop) — nada de re-escanear familias de
        // ids en accumulated. El escaneo anterior aplanaba a la base y podía redescubrir un
        // sub-lote hermano ya PROMOVIDO en un ciclo anterior (accumulated nunca olvida) y
        // contarlo dos veces al re-promoverlo; con la lista explícita ese caso no existe.
        // Siempre se toma la versión VIGENTE en accumulated (no la instancia reportada, que
        // pudo quedar obsoleta dentro del mismo pase), y un id ya presente con la misma
        // instancia (p. ej. el peel que el bucle de arriba ya sincronizó) se deja como está.
        for (AssignedRoute touched : splitTouchedRoutes) {
            String id = touched.getBatch().batchId();
            AssignedRoute current = accumulated.getRoute(id);
            if (current == null) {
                continue;  // creada y luego absorbida dentro del mismo pase: nunca se trackeó
            }
            AssignedRoute previous = frenteCaliente.get(id);
            if (previous == null) {
                frenteCaliente.put(id, current);
                fitnessTracker.trackPendingEvents(List.of(current));
            } else if (previous != current) {
                fitnessTracker.replacePendingEvents(previous, current);
                frenteCaliente.put(id, current);
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
     * @param touchedRoutes salida: se agregan aquí todas las rutas creadas o reemplazadas por
     *                       este método (peel del vuelo sobre-capacidad + sub-lotes nuevos,
     *                       directo y multi-hop) — usado por el llamador para validarlas (ver
     *                       paso 6c de executePlanningCycle), ya que corren DESPUÉS de que
     *                       finalSolution ya pasó por el validador.
     * @return número de sub-lotes (divisiones) generados
     */
    private int applyCapacityAwareSplitting(Solution solution, List<ShipmentBatch> cycleBatches,
                                            ZonedDateTime windowStart, ZonedDateTime windowEnd,
                                            List<AssignedRoute> touchedRoutes) {
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

        // Contador de sufijos -S por id FUENTE (no por base aplanada): los sub-lotes nuevos
        // ANIDAN bajo el id del lote/ruta del que salen ("B1-S2" pelado genera "B1-S2-S<n>"),
        // igual que splitPortion en la semilla GA — así los ids nuevos viven en el subárbol
        // exclusivo de su fuente (no pueden chocar con sub-lotes de otros ciclos) y
        // routedQuantity, acotado a ese subárbol, los suma todos. Sembrado con el índice
        // máximo YA usado bajo cada fuente (tolerante a huecos cortos, igual que
        // subtreeRoutedQuantity: un peel que cayó bajo MIN_FILL_BAGS deja huecos), para
        // garantizar unicidad (addRoute reemplaza por batchId → un choque perdería maletas).
        // Acotado a las fuentes que podrían necesitar un sub-lote NUEVO este ciclo: las rutas
        // de frenteCaliente (únicas peelables) y los lotes de este ciclo.
        Map<String, Integer> splitCounter = new HashMap<>();
        Set<String> splitSources = new HashSet<>();
        for (AssignedRoute route : frenteCaliente.values()) {
            splitSources.add(route.getBatch().batchId());
        }
        for (ShipmentBatch batch : cycleBatches) {
            splitSources.add(batch.batchId());
        }
        for (String source : splitSources) {
            int maxIdx = 0;
            int emptyStreak = 0;
            for (int i = 1; emptyStreak < SUBTREE_SCAN_GAP_TOLERANCE; i++) {
                String childId = source + "-S" + i;
                if (solution.getRoute(childId) == null && solution.getRoute(childId + "-S1") == null) {
                    emptyStreak++;
                    continue;
                }
                emptyStreak = 0;
                maxIdx = i;
            }
            splitCounter.put(source, maxIdx);
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
                hubCapacity.removeRoute(r);
                // Si la porción que quedaría en el vuelo original (newQty) no alcanza el mínimo
                // de sub-lote, se descarta la ruta ENTERA (no solo el peel) — por eso el
                // remanente que se manda a reubicar debe ser b.quantity() completo en ese caso,
                // no solo `peel`: dejar solo `peel` perdería en silencio las newQty maletas que
                // se suponía quedaban en el vuelo original pero ya no tienen ruta. Con
                // MIN_FILL_BAGS=1 esto nunca ocurría (newQty<1 solo si newQty==0, es decir
                // peel==b.quantity()), pero al subir el mínimo (tarea E) sí puede pasar con
                // newQty=1 o 2.
                boolean keepReducedRoute = newQty >= MIN_FILL_BAGS;
                if (keepReducedRoute) {
                    try {
                        ShipmentBatch reduced = new ShipmentBatch(
                            b.batchId(), b.airportBatchId(), b.clientId(),
                            b.origin(), b.destination(), newQty, b.ingressTime());
                        AssignedRoute reducedRoute = new AssignedRoute(reduced, r.getFlights());
                        solution.addRoute(reducedRoute); // reemplaza por batchId
                        hubCapacity.applyRoute(reducedRoute);
                        touchedRoutes.add(reducedRoute);
                    } catch (Exception e) {
                        solution.removeRoute(b.batchId());
                        keepReducedRoute = false;
                    }
                } else {
                    solution.removeRoute(b.batchId());
                }
                // Liberar de usedByFlight lo que REALMENTE deja de ocupar el vuelo: si se
                // conserva la porción reducida, solo `peel` (newQty sigue viajando ahí); si la
                // ruta se descarta ENTERA (newQty<MIN_FILL_BAGS o falló la construcción), hay
                // que liberar b.quantity() completo — quedarse solo con `peel` subestimaría la
                // ocupación real restante del vuelo, permitiendo sobre-reservarlo con otro
                // remanente más adelante en este mismo ciclo (mismo caso nuevo que el comentario
                // de arriba: con MIN_FILL_BAGS=1 esta rama solo se daba con newQty==0, donde
                // peel ya era b.quantity() completo y ambos coincidían).
                int flightRelease = keepReducedRoute ? peel : b.quantity();
                for (Flight g : r.getFlights()) {
                    usedByFlight.merge(g.flightId(), -flightRelease, Integer::sum);
                }
                // El remanente ANIDA bajo el id de la ruta pelada (no bajo la base aplanada):
                // sus sub-lotes se acuñan como b.batchId()+"-S<n>", dentro del subárbol que
                // routedQuantity/el cuadre del ciclo ya vigilan para este lote.
                remainders.add(new RemainderLot(b, b.batchId(), keepReducedRoute ? peel : b.quantity()));
                overflow -= peel;
            }
        }

        // 3. Añadir los lotes que NUNCA tuvieron ruta como remanentes (división de extremo a
        //    extremo). Igual que en el peel: anidan bajo su PROPIO id (un carryover "B1-S2"
        //    genera "B1-S2-S<n>", nunca "B1-S<n>" — ese nivel pertenece a otras porciones).
        //    TRIAJE bajo sobrecarga: con backlog grande (fechas densas, medido feb-2028:
        //    600+ lotes sin ruta por ciclo), intentar reubicar TODOS explotaba el tiempo del
        //    ciclo muy por encima de Ta (155-168s vs 45s de cadencia) — cada remanente paga un
        //    escaneo de vuelos directos y potencialmente una búsqueda multi-hop completa. Se
        //    procesan los más URGENTES por vencimiento de SLA (los demás no se pierden: pasan a
        //    carryover por la vía normal de registerUnroutedForRetry y reintentan el próximo
        //    ciclo). Los remanentes de peel (paso 2) NO se recortan: son pocos y sus maletas
        //    acaban de perder la ruta que ya tenían — reubicarlos es prioridad absoluta.
        List<RemainderLot> unroutedRemainders = new ArrayList<>();
        for (ShipmentBatch batch : cycleBatches) {
            if (hadRoute.contains(batch.batchId())) continue;
            if (solution.getRoute(batch.batchId()) != null) continue;
            unroutedRemainders.add(new RemainderLot(batch, batch.batchId(), batch.quantity()));
        }
        if (unroutedRemainders.size() > MAX_UNROUTED_REMAINDERS_PER_CYCLE) {
            unroutedRemainders.sort(Comparator.comparing(
                rem -> rem.template().ingressTime().plus(rem.template().calculateSLA())));
            unroutedRemainders = unroutedRemainders.subList(0, MAX_UNROUTED_REMAINDERS_PER_CYCLE);
        }
        remainders.addAll(unroutedRemainders);

        // 4. Reubicar cada remanente en el espacio libre (directo y, si hace falta, con
        //    escalas). Presupuesto de búsquedas multi-hop POR CICLO (no por remanente): la
        //    colocación directa es barata (vuelos indexados de la ventana), pero cada sonda
        //    multi-hop es una búsqueda de camino completa — bajo backlog, cientos de sondas
        //    por ciclo eran el otro gran responsable del exceso sobre Ta.
        int splitsGenerated = 0;
        int[] multiHopProbeBudget = { MAX_MULTIHOP_PROBES_PER_CYCLE };
        for (RemainderLot rem : remainders) {
            splitsGenerated += placeBagsInLeftover(
                solution, usedByFlight, hubCapacity, splitCounter, rem, windowStart, windowEnd,
                touchedRoutes, multiHopProbeBudget);
        }
        return splitsGenerated;
    }

    /** Máximo de lotes sin ruta que el splitting intenta reubicar por ciclo (los más urgentes). */
    private static final int MAX_UNROUTED_REMAINDERS_PER_CYCLE = 150;

    /** Máximo de búsquedas multi-hop del splitting por ciclo (las directas no se limitan). */
    private static final int MAX_MULTIHOP_PROBES_PER_CYCLE = 40;

    /**
     * Coloca {@code rem.quantity()} maletas en el espacio libre de vuelos directos y, para el
     * remanente, en una ruta con escalas. Divide en tantos sub-lotes como vuelos haga falta.
     *
     * <p>Mínimo de sub-lote (ver {@link #MIN_FILL_BAGS}): con EXCEPCIÓN de último recurso — si
     * el remanente COMPLETO ({@code rem.quantity()}, evaluado una sola vez al entrar, no
     * {@code remaining} que va bajando) ya es menor que el mínimo normal, se usa un mínimo
     * efectivo de 1 en todo este remanente. Así un remanente de 2 maletas se coloca igual en
     * vez de perderse, pero un remanente grande (p. ej. 10) que termina con una cola de 2 tras
     * varias colocaciones de >=3 sigue respetando el mínimo normal para esa cola — la cola no
     * se pierde, pasa a carryover el próximo ciclo por la vía normal (ver registerUnroutedForRetry).
     *
     * @return número de sub-lotes creados para este remanente
     */
    private int placeBagsInLeftover(Solution solution, Map<String, Integer> usedByFlight,
                                    CapacityContext hubCapacity, Map<String, Integer> splitCounter, RemainderLot rem,
                                    ZonedDateTime windowStart, ZonedDateTime windowEnd,
                                    List<AssignedRoute> touchedRoutes, int[] multiHopProbeBudget) {
        int remaining = rem.quantity();
        int effectiveMin = rem.quantity() < MIN_FILL_BAGS ? 1 : MIN_FILL_BAGS;
        if (remaining < effectiveMin) return 0;
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
            if (remaining < effectiveMin) break;
            int leftover = f.capacity() - usedByFlight.getOrDefault(f.flightId(), 0);
            if (leftover < effectiveMin) continue;
            // Capacidad DURA de almacén en destino — nunca se relaja (mismo criterio que
            // CapacityContext.hasHubCapacity en RouteGenerator): sin esto, un remanente podía
            // reubicarse en un vuelo directo con espacio de sobra pero cuyo destino ya no
            // tiene almacén libre, empujándolo sobre el 100%.
            int hubResidual = t.destination().storageCapacity() - hubCapacity.storageOccupancy(t.destination());
            if (hubResidual < effectiveMin) continue;
            int originResidual = hubCapacity.storageResidual(t.origin());
            if (originResidual < effectiveMin) continue;
            int take = Math.min(Math.min(leftover, remaining), Math.min(hubResidual, originResidual));
            int idx = splitCounter.merge(rem.sourceId(), 1, Integer::sum);
            String subId = rem.sourceId() + "-S" + idx;
            try {
                ShipmentBatch subLot = new ShipmentBatch(
                    subId, t.airportBatchId() + "-S" + idx, t.clientId(),
                    t.origin(), t.destination(), take, t.ingressTime());
                AssignedRoute subRoute = new AssignedRoute(subLot, List.of(f));
                solution.addRoute(subRoute);
                usedByFlight.merge(f.flightId(), take, Integer::sum);
                hubCapacity.applyRoute(subRoute);
                touchedRoutes.add(subRoute);
                remaining -= take;
                placed++;
            } catch (Exception e) {
                splitCounter.merge(rem.sourceId(), -1, Integer::sum); // revertir índice no usado
            }
        }

        // Multi-hop para el remanente: ruta con escalas verificando capacidad en TODOS los tramos
        // (vuelo Y almacén — hubCapacity va al generador, así que la búsqueda misma ya descarta
        // hubs sin espacio real; ver RouteGenerator.generateFeasibleRoute). Consume del
        // presupuesto POR CICLO de sondas multi-hop (ver applyCapacityAwareSplitting, paso 4).
        if (remaining >= effectiveMin && fillRouteGenerator != null && multiHopProbeBudget[0] > 0) {
            multiHopProbeBudget[0]--;
            try {
                int idx = splitCounter.merge(rem.sourceId(), 1, Integer::sum);
                String subId = rem.sourceId() + "-S" + idx;
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
                    if (take >= effectiveMin) {
                        AssignedRoute route = take == remaining ? probeRoute : new AssignedRoute(
                            new ShipmentBatch(subId, t.airportBatchId() + "-S" + idx, t.clientId(),
                                t.origin(), t.destination(), take, t.ingressTime()),
                            probeRoute.getFlights());
                        solution.addRoute(route);
                        for (Flight f : route.getFlights()) {
                            usedByFlight.merge(f.flightId(), take, Integer::sum);
                        }
                        hubCapacity.applyRoute(route);
                        touchedRoutes.add(route);
                        remaining -= take;
                        placed++;
                    } else {
                        splitCounter.merge(rem.sourceId(), -1, Integer::sum);
                    }
                } else {
                    splitCounter.merge(rem.sourceId(), -1, Integer::sum);
                }
            } catch (Exception ignored) {
                // Sin ruta multi-hop factible → el remanente queda sin ubicar este ciclo.
            }
        }
        return placed;
    }

    /** Remanente de maletas a reubicar; {@code template} aporta origen/destino/cliente/ingreso.
     * {@code sourceId} es el id del lote/ruta del que salió el remanente — sus sub-lotes se
     * acuñan anidados bajo él ({@code sourceId + "-S<n>"}), nunca bajo la base aplanada. */
    private record RemainderLot(ShipmentBatch template, String sourceId, int quantity) {}

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
        // La replanificación de emergencia implica cancelación de vuelo: lo único que puede
        // cambiar la respuesta de "¿existe camino por horario?" — invalidar el cache.
        structuralFeasibilityCache.clear();
        // Sus eventos de almacén también deben entrar a la cola pendiente del tracker —
        // igual que se hace para las rutas nuevas de un ciclo normal — o quedarían
        // invisibles para siempre en currentFitness/advanceStorageWatermark.
        fitnessTracker.trackPendingEvents(newSolution.getRoutes().values());

        System.out.println("✓ Solución actualizada en Scheduler");
    }
}
