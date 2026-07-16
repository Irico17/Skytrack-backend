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
 * - Ta: Tiempo máximo de ejecución del algoritmo (minutos)
 * - Sa: Salto entre ejecuciones del algoritmo (minutos)
 * - K: Constante de proporcionalidad para consumo de datos
 * - Sc = Sa × K: Salto de consumo de datos (minutos)
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
    
    // Parámetros de configuración
    private final int Ta;  // Tiempo algoritmo (minutos)
    private final int Sa;  // Salto algoritmo (minutos)
    private final int K;   // Constante proporcionalidad
    private final int Sc;  // Salto consumo = Sa × K (minutos)
    
    // Solución actual del sistema
    private Solution currentSolution;
    
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
     * @param Ta Tiempo máximo de algoritmo (minutos)
     * @param Sa Salto entre ejecuciones (minutos)
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
                    int Ta, int Sa, int K) {
        this(primaryAlgorithm, tabuSearch, algorithmType, useRefinement,
             shipmentQueue, evaluator, validator, Ta, Sa, K, null, false, null);
    }

    /** Constructor con relleno de capacidad por sub-lotes opcional (directo + multi-hop). */
    public Scheduler(OptimizationAlgorithm primaryAlgorithm,
                    TabuSearch tabuSearch,
                    AlgorithmType algorithmType,
                    boolean useRefinement,
                    ShipmentQueue shipmentQueue,
                    SolutionEvaluator evaluator,
                    RouteValidator validator,
                    int Ta, int Sa, int K,
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
        
        this.Ta = Ta;
        this.Sa = Sa;
        this.K = K;
        this.Sc = Sa * K;
        
        // Validar Sa >= Ta. Se permite Sa == Ta porque el algoritmo respeta un
        // presupuesto de tiempo duro (deadline = Ta) dentro de GA/Tabu: nunca excede Ta,
        // y la cadencia (Sa) puede igualar Ta para usar todo el CPU sin tiempo muerto.
        if (Sa < Ta) {
            throw new IllegalArgumentException(
                String.format("Sa (%d) must be >= Ta (%d)", Sa, Ta)
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
        
        // 2. Consumir pedidos de ShipmentQueue en ventana Sc
        List<ShipmentBatch> batches = shipmentQueue.consumeShipments(windowStart, windowEnd);
        System.out.println("Lotes consumidos: " + batches.size());
        
        if (batches.isEmpty()) {
            System.out.println("No hay lotes para planificar");
            return currentSolution;
        }
        
        // 3. Ejecutar algoritmo primario con pedidos consumidos
        long startTime = System.currentTimeMillis();
        String algorithmName = algorithmType == AlgorithmType.GATS ? "Algoritmo Genético" : "Búsqueda Tabú";
        System.out.println("\nEjecutando " + algorithmName + "...");
        Solution primarySolution = primaryAlgorithm.optimize(batches);
        long primaryTime = System.currentTimeMillis() - startTime;
        
        System.out.println("✓ " + algorithmName + " completado en " + primaryTime + " ms");
        System.out.println("  Fitness: " + String.format("%.2f", primarySolution.getFitness()));
        
        Solution finalSolution = primarySolution;
        long refinementTime = 0;
        
        // 4. Refinar con Búsqueda Tabú (solo para GATS)
        if (useRefinement) {
            startTime = System.currentTimeMillis();
            System.out.println("\nRefinando con Búsqueda Tabú...");
            finalSolution = tabuSearch.refine(primarySolution);
            refinementTime = System.currentTimeMillis() - startTime;
            
            System.out.println("✓ Refinamiento completado en " + refinementTime + " ms");
            System.out.println("  Fitness mejorado: " + String.format("%.2f", finalSolution.getFitness()));
        }
        
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
        
        // 8. Registrar tiempo de ejecución y verificar que sea <= Ta
        long totalTime = primaryTime + refinementTime;
        long taMillis = Ta * 60 * 1000L;
        
        System.out.println("\nTiempo total: " + totalTime + " ms (límite: " + taMillis + " ms)");
        
        if (totalTime > taMillis) {
            System.out.println("⚠ ADVERTENCIA: Tiempo excedido");
        }
        
        currentSolution = accumulatedSolution;
        return currentSolution;
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
        System.out.println("Parámetros: Ta=" + Ta + " min, Sa=" + Sa + " min, K=" + K + ", Sc=" + Sc + " min");
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
