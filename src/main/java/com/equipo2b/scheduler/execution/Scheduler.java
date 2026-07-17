package com.equipo2b.scheduler.execution;

import com.equipo2b.scheduler.algorithm.*;
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
        
        // Acumular cada ruta nueva sobre una copia estable de la solución actual
        for (AssignedRoute route : finalSolution.getRoutes().values()) {
            accumulatedSolution.addRoute(route);  // Agrega o reemplaza por batchId
        }
        
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
        }

        // 6. Re-evaluar fitness de la solución completa
        evaluator.evaluate(accumulatedSolution);
        System.out.println("Fitness de solución acumulada: " + String.format("%.2f", accumulatedSolution.getFitness()));
        
        // 7. Validar solución acumulada usando RouteValidator
        ValidationReport validationReport = validator.validate(accumulatedSolution);
        
        if (validationReport.isValid()) {
            System.out.println("✓ Solución acumulada válida");
        } else {
            System.out.println("⚠ Solución acumulada con violaciones:");
            System.out.println(validationReport.getSummary());
        }

        logQualityMetrics(batches, finalSolution, accumulatedSolution, validationReport);

        // 7b. Clasificar lotes sin ruta: reintento (aún dentro de SLA y con camino factible),
        //     vencidos (su SLA expira antes del próximo ciclo) o estructuralmente imposibles.
        registerUnroutedForRetry(batches, accumulatedSolution, windowEnd);

        // 8. Registrar tiempo de ejecución y verificar que sea <= Ta
        long totalTime = primaryTime + refinementTime;
        long taMillis = taSeconds * 1000L;
        
        System.out.println("\nTiempo total: " + totalTime + " ms (límite: " + taMillis + " ms)");
        
        if (totalTime > taMillis) {
            System.out.println("⚠ ADVERTENCIA: Tiempo excedido");
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
        Set<String> routedBaseIds = new HashSet<>();
        for (String key : accumulated.getRoutes().keySet()) {
            routedBaseIds.add(baseBatchId(key));
        }

        int expired = 0;
        int structural = 0;
        for (ShipmentBatch batch : batches) {
            if (routedBaseIds.contains(batch.batchId())) {
                continue;
            }
            ZonedDateTime slaDeadline = batch.ingressTime().plus(batch.calculateSLA());
            if (!slaDeadline.isAfter(windowEnd)) {
                expired++;
                continue;
            }
            if (!tabuSearch.hasFeasiblePathIgnoringCapacity(batch)) {
                structural++;
                continue;
            }
            carryoverBatches.add(batch);
        }

        if (!carryoverBatches.isEmpty() || expired > 0 || structural > 0) {
            System.out.printf(
                "🔁 Sin ruta este ciclo: %d pasan a reintento, %d con SLA vencido, %d sin camino factible por horario (no se reintentan)%n",
                carryoverBatches.size(), expired, structural
            );
        }
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
        Map<String, Integer> usedByFlight = new HashMap<>();
        Map<String, Flight> flightById = new HashMap<>();
        Map<String, List<String>> batchesByFlight = new HashMap<>();
        for (AssignedRoute route : solution.getRoutes().values()) {
            int qty = route.getBatch().quantity();
            String bId = route.getBatch().batchId();
            for (Flight f : route.getFlights()) {
                usedByFlight.merge(f.flightId(), qty, Integer::sum);
                flightById.putIfAbsent(f.flightId(), f);
                batchesByFlight.computeIfAbsent(f.flightId(), k -> new ArrayList<>()).add(bId);
            }
        }

        // Lotes que YA tenían ruta antes de este paso (para no recontarlos como "sin ruta").
        Set<String> hadRoute = new HashSet<>(solution.getRoutes().keySet());

        // Contador de sufijos -S por lote base, sembrado con los sub-lotes ya existentes para
        // garantizar IDs únicos (addRoute reemplaza por batchId → un choque perdería maletas).
        Map<String, Integer> splitCounter = new HashMap<>();
        for (String id : solution.getRoutes().keySet()) {
            String base = baseBatchId(id);
            int idx = lastSplitIndex(id);
            splitCounter.merge(base, idx, Math::max);
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
                if (newQty >= MIN_FILL_BAGS) {
                    try {
                        ShipmentBatch reduced = new ShipmentBatch(
                            b.batchId(), b.airportBatchId(), b.clientId(),
                            b.origin(), b.destination(), newQty, b.ingressTime());
                        solution.addRoute(new AssignedRoute(reduced, r.getFlights())); // reemplaza por batchId
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
            splitsGenerated += placeBagsInLeftover(solution, usedByFlight, splitCounter, rem, windowStart, windowEnd);
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
                                    Map<String, Integer> splitCounter, RemainderLot rem,
                                    ZonedDateTime windowStart, ZonedDateTime windowEnd) {
        int remaining = rem.quantity();
        if (remaining < MIN_FILL_BAGS) return 0;
        ShipmentBatch t = rem.template();
        int placed = 0;

        // Vuelos directos origen→destino con espacio libre.
        List<Flight> directFlights = flightPlan.getFlightsFromAirport(t.origin(), windowStart, windowEnd)
            .stream()
            .filter(f -> f.destination().equals(t.destination()))
            .filter(f -> !f.departureTime().isBefore(t.ingressTime()))
            .sorted(Comparator.comparing(Flight::departureTime))
            .toList();

        for (Flight f : directFlights) {
            if (remaining < MIN_FILL_BAGS) break;
            int leftover = f.capacity() - usedByFlight.getOrDefault(f.flightId(), 0);
            if (leftover < MIN_FILL_BAGS) continue;
            int take = Math.min(leftover, remaining);
            int idx = splitCounter.merge(rem.baseId(), 1, Integer::sum);
            String subId = rem.baseId() + "-S" + idx;
            try {
                ShipmentBatch subLot = new ShipmentBatch(
                    subId, t.airportBatchId() + "-S" + idx, t.clientId(),
                    t.origin(), t.destination(), take, t.ingressTime());
                solution.addRoute(new AssignedRoute(subLot, List.of(f)));
                usedByFlight.merge(f.flightId(), take, Integer::sum);
                remaining -= take;
                placed++;
            } catch (Exception e) {
                splitCounter.merge(rem.baseId(), -1, Integer::sum); // revertir índice no usado
            }
        }

        // Multi-hop para el remanente: ruta con escalas verificando capacidad en TODOS los tramos.
        if (remaining >= MIN_FILL_BAGS && fillRouteGenerator != null) {
            try {
                int idx = splitCounter.merge(rem.baseId(), 1, Integer::sum);
                String subId = rem.baseId() + "-S" + idx;
                ShipmentBatch probe = new ShipmentBatch(
                    subId, t.airportBatchId() + "-S" + idx, t.clientId(),
                    t.origin(), t.destination(), remaining, t.ingressTime());
                AssignedRoute probeRoute = fillRouteGenerator.generateFeasibleRoute(probe);
                if (probeRoute != null && probeRoute.getFlights().size() > 1) {
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

    /** Índice numérico del último sufijo "-S&lt;n&gt;" de un id, o 0 si no tiene. */
    private static int lastSplitIndex(String id) {
        int idx = id.lastIndexOf("-S");
        if (idx < 0 || idx + 2 >= id.length()) return 0;
        String suffix = id.substring(idx + 2);
        if (suffix.isEmpty() || !suffix.chars().allMatch(Character::isDigit)) return 0;
        try {
            return Integer.parseInt(suffix);
        } catch (NumberFormatException e) {
            return 0;
        }
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
        System.out.println("✓ Solución actualizada en Scheduler");
    }
}
