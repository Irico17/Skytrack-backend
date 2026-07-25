package com.equipo2b.scheduler.algorithm;

import com.equipo2b.scheduler.logic.CapacityContext;
import com.equipo2b.scheduler.logic.RouteGenerator;
import com.equipo2b.scheduler.logic.SolutionEvaluator;
import com.equipo2b.scheduler.model.*;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Implementación del Algoritmo Genético para planificación masiva.
 * 
 * <p>Características:
 * <ul>
 *   <li>Población de soluciones candidatas</li>
 *   <li>Selección por torneo</li>
 *   <li>Crossover uniforme (intercambio de rutas individuales)</li>
 *   <li>Mutación (regeneración de rutas aleatorias)</li>
 *   <li>Elitismo (preservar mejores soluciones)</li>
 * </ul>
 * 
 * <p>El algoritmo evoluciona una población de soluciones durante múltiples
 * generaciones, aplicando operadores genéticos para explorar el espacio
 * de soluciones y encontrar configuraciones óptimas de rutas.
 * 
 * <p><strong>Validates: Requirements 10.1, 15.1, 15.2, 15.3, 18.3</strong>
 */
public class GeneticAlgorithm implements OptimizationAlgorithm {
    private final RouteGenerator routeGenerator;
    private final SolutionEvaluator evaluator;
    private final FlightPlan flightPlan;
    private final AirportManager airportManager;
    
    // Parámetros configurables con valores por defecto
    private int populationSize = 50;
    private int generations = 100;
    private double mutationRate = 0.15;
    private int tournamentSize = 4;
    private int eliteCount = 2;
    private int stagnationLimit = 15;  // Early stopping: max generations sin mejora
    private boolean parallelEnabled = true;
    private int parallelMinProcessors = 3;
    private int parallelPopulationThreshold = 32;
    private int routeSearchAttempts = 12;
    private int routeCachedVariants = 5;
    private long maxTimeMillis = 0;  // 0 = sin límite; >0 = deadline duro por ciclo (presupuesto Ta)
    /**
     * Fracción del presupuesto usada SOLO en el primer optimize() (primer ciclo de la
     * simulación). 1.0 = sin recorte. Ver comentario "PRIMER CICLO RÁPIDO" en optimize().
     */
    private double firstCycleBudgetRatio = 1.0;
    private boolean firstOptimizeDone = false;
    /** Línea base escalar (opcional; preferir committedRoutes en timeline). */
    private volatile Map<Airport, Integer> storageBaseline = Map.of();
    /** Rutas de ciclos previos: ATP time-phased real (llegadas futuras visibles). */
    private volatile List<AssignedRoute> committedRoutes = List.of();
    /**
     * Timeline ATP de rutas comprometidas, reconstruida solo al cambiar baseline/rutas.
     * Evita {@code fromSolution(committed)} en cada individuo/vecino (pico de RSS en VM 2 GB).
     */
    private volatile CapacityContext committedCapacityCache = CapacityContext.empty();
    
    /**
     * Constructor que inicializa el algoritmo genético con dependencias.
     * 
     * @param flightPlan Plan de vuelos disponibles
     * @param airportManager Gestor de aeropuertos
     * @throws NullPointerException si algún parámetro es null
     */
    public GeneticAlgorithm(FlightPlan flightPlan, AirportManager airportManager) {
        if (flightPlan == null) {
            throw new NullPointerException("FlightPlan cannot be null");
        }
        if (airportManager == null) {
            throw new NullPointerException("AirportManager cannot be null");
        }
        
        this.flightPlan = flightPlan;
        this.airportManager = airportManager;
        this.routeGenerator = new RouteGenerator(flightPlan, airportManager);
        this.evaluator = new SolutionEvaluator(flightPlan, airportManager);
    }

    /** Capacidad de construcción: copia del cache ATP comprometido (+ baseline). */
    private CapacityContext planningCapacity() {
        return committedCapacityCache.copy();
    }

    /** Igual que {@link #planningCapacity()} más las rutas del individuo/ciclo actual. */
    private CapacityContext planningCapacityWith(Collection<AssignedRoute> cycleRoutes) {
        CapacityContext ctx = planningCapacity();
        if (cycleRoutes != null) {
            for (AssignedRoute route : cycleRoutes) {
                ctx.applyRoute(route);
            }
        }
        return ctx;
    }

    private void rebuildCommittedCapacityCache() {
        committedCapacityCache = CapacityContext.fromSolution(committedRoutes, storageBaseline);
    }
    
    /**
    * Inicializa la población con soluciones candidatas.
     * 
     * <p>Para cada solución en la población, genera rutas factibles para todos
     * los lotes usando RouteGenerator. Para introducir diversidad, procesa los
     * lotes en orden aleatorio para cada individuo.
     * 
    * <p>La paralelización se habilita solo cuando la configuración y los CPUs
    * disponibles lo justifican. En VMs pequeñas, el costo de ForkJoinPool suele
    * superar la ganancia.
     * 
     * <p><strong>Validates: Requirement 10.1</strong>
     *
     * @param batches Lista EFECTIVA post-split de la semilla (Tarea A) — sus ids deben
     *     coincidir con los de las rutas de {@code seed}, nunca la lista original del ciclo
     * @return Población inicial de soluciones
     */
    private List<Solution> initializePopulation(List<ShipmentBatch> batches, int effectivePopulationSize,
                                                long deadline, Solution seed) {
        // PRESUPUESTO DE CONSTRUCCIÓN: como máximo la MITAD del tiempo restante. Construir
        // un individuo con búsqueda aleatorizada cuesta ~2× la semilla, y sin este tope la
        // construcción se comía todo el deadline y la evolución corría 0 generaciones.
        final long now = System.currentTimeMillis();
        final long constructionDeadline = deadline == Long.MAX_VALUE
            ? deadline
            : now + Math.max(1, (deadline - now) / 2);

        Stream<Integer> populationIndexes = IntStream.range(0, effectivePopulationSize).boxed();
        if (shouldUseParallelism(effectivePopulationSize)) {
            populationIndexes = populationIndexes.parallel();
        }

        List<Solution> population = populationIndexes.map(i -> {
                // El individuo 0 reutiliza la semilla greedy ya construida y evaluada:
                // reconstruirla costaría tanto como construir un individuo entero.
                if (i == 0) {
                    return new Solution(seed);
                }
                Solution solution = new Solution();
                if (System.currentTimeMillis() >= constructionDeadline) {
                    return solution;
                }
                CapacityContext capacity = planningCapacity();

                // Shufflear lotes por individuo para generar diversidad genética
                List<ShipmentBatch> shuffledBatches = new ArrayList<>(batches);
                Collections.shuffle(shuffledBatches, ThreadLocalRandom.current());

                for (ShipmentBatch batch : shuffledBatches) {
                    if (System.currentTimeMillis() >= constructionDeadline) {
                        break;
                    }
                    // Individuos impares: sesgo multi-hop para explorar hubs alternativos
                    boolean preferMultiHop = (i % 2 == 1);
                    AssignedRoute route = preferMultiHop
                        ? routeGenerator.generateFeasibleRoutePreferMultiHop(batch, capacity)
                        : routeGenerator.generateFeasibleRoute(batch, capacity);
                    if (route != null) {
                        solution.addRoute(route);
                        capacity.applyRoute(route);
                    }
                }
                return solution;
            })
            .collect(Collectors.toList());

        // INVARIANTE DE TRANSPORTE: ningún individuo compite con MENOS MALETAS transportadas
        // que la semilla (Tarea B — antes comparaba número de RUTAS, que con lotes partidos
        // por la semilla —splits "-S<n>"— ya no es comparable 1:1 contra un individuo fresco
        // que enrutó el mismo lote atómicamente en una sola ruta: menos rutas no significa
        // menos maletas). Bajo saturación real, el fitness puede preferir una solución parcial
        // (50k/lote sin asignar es más barato que el exceso de almacén acumulado) y un
        // individuo truncado por deadline le GANABA a la semilla completa — el ciclo
        // terminaba con 12-64% de asignación. Los truncados/vacíos se reemplazan por
        // copias de la semilla; como crossover y mutación nunca reducen las maletas
        // transportadas (Tarea E evita que la mutación descarte lotes sin forzarlos), el
        // invariante se conserva en toda la evolución.
        final int seedTotalBags = seed.getTotalBags();
        for (int j = 0; j < population.size(); j++) {
            if (population.get(j).getTotalBags() < seedTotalBags) {
                population.set(j, new Solution(seed));
            }
        }

        return population;
    }
    
    /**
    * Evalúa el fitness de cada solución no evaluada en la población.
     * 
     * <p>Usa SolutionEvaluator para calcular el fitness y marca las soluciones
     * como evaluadas para evitar recálculos innecesarios.
     * 
    * <p>La evaluación paralela se usa solo cuando hay suficientes CPUs y población.
    * SolutionEvaluator es thread-safe (stateless).
     * 
     * <p><strong>Validates: Requirement 10.2</strong>
     * 
     * @param population Población de soluciones a evaluar
     */
    private void evaluatePopulation(List<Solution> population, int effectivePopulationSize) {
        Stream<Solution> stream = shouldUseParallelism(effectivePopulationSize)
            ? population.parallelStream()
            : population.stream();

        stream
            .filter(solution -> !solution.isEvaluated())
            .forEach(solution -> {
                double fitness = evaluator.evaluate(solution);
                solution.setFitness(fitness);
            });
    }

    private boolean shouldUseParallelism(int effectivePopulationSize) {
        return parallelEnabled
            && Runtime.getRuntime().availableProcessors() >= parallelMinProcessors
            && effectivePopulationSize >= parallelPopulationThreshold;
    }

    /**
     * Resultado de construir la semilla greedy: la solución y la lista EFECTIVA de lotes
     * realmente procesados (sub-lotes de splitPortion + lotes no partidos + lotes que
     * quedaron sin ruta, todos en su forma FINAL — mismos ids que aparecen en las rutas de
     * {@code seed}). Población, crossover (implícitamente, por ids) y mutación deben operar
     * sobre esta lista, nunca sobre la lista original de lotes del ciclo — ver Tarea A.
     */
    private record SeedResult(Solution seed, List<ShipmentBatch> effectiveBatches) {}

    /**
     * Ventana de agrupación temporal para el orden de la semilla greedy (Tarea C).
     */
    private static final Duration REGRET_BLOCK_WINDOW = Duration.ofMinutes(30);

    private SeedResult buildHeuristicSolution(List<ShipmentBatch> batches, long deadline) {
        Solution solution = new Solution();
        List<ShipmentBatch> orderedBatches = new ArrayList<>(batches);
        // SEMILLA REGRET-LITE: prioridad temporal por ventana de ingreso (bloques de 30 min,
        // igual que antes) preservada como criterio PRIMARIO — no queremos que un lote grande
        // le robe el turno a uno con ventana de ingreso muy anterior. Dentro del MISMO bloque,
        // sin embargo, se ordenan primero los lotes "difíciles": cantidad grande primero (más
        // fácil de fragmentar más tarde si no cabe, y son los que más presionan la capacidad
        // residual) y, como desempate, el SLA más apretado primero. Razonamiento del desempate:
        // aunque "intercontinental" suena más restrictivo, el SLA real es 24h mismo continente
        // vs 48h distinto continente — el intracontinental tiene la MITAD de margen, así que es
        // el que en verdad hay que colocar primero; el intercontinental (48h) tiene holgura de
        // sobra para esperar su turno. Los lotes chicos, con SLA laxo, quedan al final del
        // bloque y rellenan los huecos de capacidad que dejan los grandes.
        orderedBatches.sort(
            Comparator.<ShipmentBatch>comparingLong(
                    b -> b.ingressTime().toEpochSecond() / REGRET_BLOCK_WINDOW.toSeconds())
                .thenComparing(Comparator.comparingInt(ShipmentBatch::quantity).reversed())
                .thenComparing(ShipmentBatch::calculateSLA));
        CapacityContext capacity = planningCapacity();

        ArrayDeque<ShipmentBatch> queue = new ArrayDeque<>(orderedBatches);
        Map<String, Integer> nextSplitSuffix = new HashMap<>();
        List<ShipmentBatch> effectiveBatches = new ArrayList<>();
        int unroutable = 0;
        int routed = 0;
        int partialSplits = 0;

        while (!queue.isEmpty()) {
            if (System.currentTimeMillis() >= deadline) {
                System.out.printf(
                    "⏱ Semilla greedy truncada por Ta tras %d/%d lotes (cola restante=%d)%n",
                    routed, orderedBatches.size(), queue.size());
                break;
            }

            ShipmentBatch batch = queue.poll();
            // Residual del ORIGEN acotado a la ventana en que el lote realmente estará ahí
            // ([ingreso, ingreso+SLA): más tarde ya habría incumplido). Con el residual
            // GLOBAL, un origen que se satura en cualquier instante del horizonte
            // comprometido daba 0 y marcaba unroutable todo lote nuevo — pero rechazarlo no
            // libera nada: las maletas se quedan igual en ese almacén, solo que sin ruta.
            int originResidual = capacity.storageResidual(
                batch.origin(), batch.ingressTime(), batch.ingressTime().plus(batch.calculateSLA()));
            if (originResidual <= 0) {
                unroutable++;
                effectiveBatches.add(batch);
                continue;
            }

            // Admisión de origen: si el lote entero no cabe, partir antes de buscar ruta.
            if (batch.quantity() > originResidual) {
                ShipmentBatch head = splitPortion(batch, originResidual, nextSplitSuffix);
                ShipmentBatch tail = splitPortion(batch, batch.quantity() - originResidual, nextSplitSuffix);
                queue.addFirst(tail);
                batch = head;
                partialSplits++;
            }

            AssignedRoute route = routeGenerator.generateEarliestFeasibleRoute(batch, capacity);
            if (route == null && batch.quantity() > 1) {
                // Sin sobrebookeo de vuelo: probar la mayor cantidad que sí quepa.
                int fitted = findMaxRoutableQuantity(batch, capacity);
                if (fitted >= 1 && fitted < batch.quantity()) {
                    ShipmentBatch head = splitPortion(batch, fitted, nextSplitSuffix);
                    ShipmentBatch tail = splitPortion(batch, batch.quantity() - fitted, nextSplitSuffix);
                    route = routeGenerator.generateEarliestFeasibleRoute(head, capacity);
                    if (route != null) {
                        queue.addFirst(tail);
                        partialSplits++;
                    }
                }
            }

            // Lista EFECTIVA (Tarea A): registrar la forma FINAL de esta porción — el batch
            // del route (puede ser un sub-lote -S<n> o el lote sin partir) si se enrutó, o
            // `batch` tal cual quedó (ya con el split de admisión de origen aplicado, si hubo)
            // si no se pudo enrutar. Nunca la lista original.
            if (route != null) {
                solution.addRoute(route);
                capacity.applyRoute(route);
                routed++;
                effectiveBatches.add(route.getBatch());
            } else {
                unroutable++;
                effectiveBatches.add(batch);
            }
        }

        if (unroutable > 0 || partialSplits > 0) {
            System.err.printf(
                "Warning: seed construction — %d unroutable, %d partial splits, %d routed%n",
                unroutable, partialSplits, routed);
        }
        return new SeedResult(solution, effectiveBatches);
    }

    /**
     * Mayor cantidad en [1, batch.quantity() - 1] para la que existe ruta earliest con
     * capacidad de vuelo/almacén. Solo se llama cuando el batch COMPLETO ya falló, así que
     * el tope nunca es la cantidad total (igual que la búsqueda binaria anterior).
     *
     * <p>ANTES: búsqueda binaria con un Dijkstra completo por probe (~6-8 corridas por lote
     * fallido). AHORA (Tarea D): UNA sola corrida earliest con quantity=1 fija la TOPOLOGÍA
     * de la ruta (un solo asiento casi siempre cabe); el cuello de botella real —cuánta
     * cantidad soporta esa topología concreta— se calcula en O(tramos) tomando el mínimo de
     * los residuales de vuelo y almacén a lo largo del camino, sin más búsquedas de ruta.</p>
     */
    private int findMaxRoutableQuantity(ShipmentBatch batch, CapacityContext capacity) {
        ShipmentBatch probe = new ShipmentBatch(
            batch.batchId() + "#probe",
            batch.airportBatchId(),
            batch.clientId(),
            batch.origin(),
            batch.destination(),
            1,
            batch.ingressTime());
        AssignedRoute probeRoute = routeGenerator.generateEarliestFeasibleRoute(probe, capacity);
        if (probeRoute == null) {
            return -1;
        }

        // Cuello de botella: mínimo residual entre almacén de origen, cada tramo de vuelo y
        // cada hub tocado (escalas intermedias y destino) a lo largo del camino del probe.
        // Los residuales de almacén se miden en la ventana EXACTA de estancia de este camino
        // (ya conocemos los horarios del probe), no sobre el pico global del horizonte: si no,
        // un hub que se satura en otro momento del día reducía el cuello a 0 y el lote se
        // partía en trozos mínimos o se descartaba sin motivo real.
        List<Flight> probeFlights = probeRoute.getFlights();
        int bottleneck = capacity.storageResidual(
            batch.origin(), batch.ingressTime(), probeFlights.get(0).departureTime());
        for (int i = 0; i < probeFlights.size(); i++) {
            Flight flight = probeFlights.get(i);
            bottleneck = Math.min(bottleneck, capacity.flightResidual(flight));
            ZonedDateTime until = (i < probeFlights.size() - 1)
                ? probeFlights.get(i + 1).departureTime()
                : flight.arrivalTime().plus(CapacityContext.FINAL_PICKUP_WINDOW);
            bottleneck = Math.min(bottleneck,
                capacity.storageResidual(flight.destination(), flight.arrivalTime(), until));
        }
        int candidate = Math.min(bottleneck, batch.quantity() - 1);

        // Verificación DURA tramo por tramo antes de confiar el candidato: el probe se
        // resolvió con quantity=1, así que su camino pudo pasar por un hub que a mayor
        // cantidad ya no está 100% garantizado (p.ej. justo en el borde del soft-limit).
        // Si no pasa, degradar al máximo que sí pase (nunca sobrebookear vuelo ni almacén).
        while (candidate >= 1 && !fitsAlongRoute(probeRoute, candidate, capacity)) {
            candidate--;
        }
        return candidate >= 1 ? candidate : -1;
    }

    /** Capacidad DURA time-phased (vuelo + almacén) para colocar {@code quantity} en la ruta. */
    private boolean fitsAlongRoute(AssignedRoute route, int quantity, CapacityContext capacity) {
        return capacity.pathFitsHard(route.getBatch(), route.getFlights(), quantity);
    }

    /**
     * Crea una porción del lote {@code source} ANIDANDO el sufijo bajo el id del propio
     * fuente ("B1-S2" → "B1-S2-S1"), nunca aplanando hasta la raíz de la familia.
     *
     * <p><b>Por qué es obligatorio anidar:</b> un lote de reintento puede llegar aquí ya con
     * sufijo (p. ej. "B1-S2", acuñado por el carryover del Scheduler). Aplanar a la raíz
     * ("B1") re-acuñaría "B1-S1" — un id que puede EXISTIR en la solución acumulada de un
     * ciclo anterior; al acumularse, {@code addRoute} (que reemplaza por batchId)
     * sobrescribiría en silencio esa ruta vieja, perdiendo maletas ya colocadas (incluso ya
     * voladas). Anidando, los ids nuevos viven en el subárbol EXCLUSIVO del lote fuente
     * (garantizado libre por freshCarryoverId al acuñar el carryover), así que no pueden
     * colisionar con nada preexistente — y la contabilidad por maletas del Scheduler
     * ({@code routedQuantity}, que suma el subárbol del id del lote) los ve todos.</p>
     */
    private static ShipmentBatch splitPortion(
            ShipmentBatch source, int quantity, Map<String, Integer> nextSplitSuffix) {
        String parent = source.batchId();
        int n = nextSplitSuffix.merge(parent, 1, Integer::sum);
        String id = parent + "-S" + n;
        return new ShipmentBatch(
            id,
            source.airportBatchId() + "-S" + n,
            source.clientId(),
            source.origin(),
            source.destination(),
            quantity,
            source.ingressTime());
    }
    
    /**
     * Selecciona un individuo mediante selección por torneo.
     * 
     * <p>Selecciona tournamentSize candidatos aleatorios de la población
     * y retorna el de mejor fitness (menor valor).
     * 
     * <p>Usa ThreadLocalRandom para thread-safety sin sincronización.
     * 
     * <p><strong>Validates: Requirement 10.3</strong>
     * 
     * @param population Población de soluciones
     * @return Solución ganadora del torneo
     */
    private Solution tournamentSelection(List<Solution> population) {
        Solution best = null;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        
        for (int i = 0; i < tournamentSize; i++) {
            int index = random.nextInt(population.size());
            Solution candidate = population.get(index);
            
            if (best == null || candidate.getFitness() < best.getFitness()) {
                best = candidate;
            }
        }
        
        return best;
    }
    
    /**
     * Realiza crossover uniforme entre dos soluciones padre.
     * 
     * <p>Crea una solución hijo vacía. Para cada batchId presente en los padres,
     * elige la ruta del padre1 o padre2 con probabilidad 50/50 y la copia al hijo.
     * 
     * <p>Este operador mantiene el principio "Equipaje con Dueño": no intercambia
     * maletas entre clientes, solo rutas completas.
     * 
     * <p>Usa ThreadLocalRandom para thread-safety sin sincronización.
     * 
     * <p><strong>Validates: Requirements 8.3, 10.4</strong>
     * 
     * @param parent1 Primer padre
     * @param parent2 Segundo padre
     * @return Solución hijo resultado del crossover
     */
    private Solution crossover(Solution parent1, Solution parent2) {
        Solution child = new Solution();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        
        // Obtener todos los batch IDs de ambos padres
        Set<String> allBatchIds = new HashSet<>();
        allBatchIds.addAll(parent1.getRoutes().keySet());
        allBatchIds.addAll(parent2.getRoutes().keySet());
        
        for (String batchId : allBatchIds) {
            AssignedRoute route1 = parent1.getRoute(batchId);
            AssignedRoute route2 = parent2.getRoute(batchId);
            
            // Elegir ruta de uno de los padres (50/50)
            if (route1 != null && route2 != null) {
                AssignedRoute selectedRoute = random.nextBoolean() ? route1 : route2;
                child.addRoute(new AssignedRoute(selectedRoute));
            } else if (route1 != null) {
                child.addRoute(new AssignedRoute(route1));
            } else if (route2 != null) {
                child.addRoute(new AssignedRoute(route2));
            }
        }

        // NO se repara aquí a propósito. Dos padres ≤100% pueden mezclarse en un hijo que
        // apila el mismo hub, pero repararlo en cada cruce era caro y contraproducente:
        //  - Coste: la reparación reconstruye un contexto time-phased por hijo (población ×
        //    generaciones = cientos de copias del timeline comprometido por corrida).
        //  - Invariante: descartar rutas dentro del cruce rompe la premisa de que crossover y
        //    mutación nunca REDUCEN el transporte, en la que se apoya el invariante de
        //    initializePopulation/optimize (comparación por maletas contra la semilla).
        // El desborde ya está penalizado con fuerza en el fitness (15,000/maleta), y la
        // garantía dura la dan la reparación del mejor final (runEvolution) y la puerta de
        // Scheduler.stripHardStorageOverflow antes de publicar. Buscar con penalización y
        // reparar al final es más barato y no degrada la exploración.
        return child;
    }

    /**
     * Rechaza rutas del hijo que violarían capacidad dura de almacén/vuelo dado el baseline.
     * Orden: primero rutas que tocan hubs relativamente más libres (empaqueta más sin overflow).
     */
    private Solution repairHardStorageCapacity(Solution solution) {
        if (solution == null || solution.getRoutes().isEmpty()) {
            return solution != null ? solution : new Solution();
        }
        CapacityContext capacity = planningCapacity();
        List<AssignedRoute> routes = new ArrayList<>(solution.getRoutes().values());
        routes.sort(Comparator.comparingDouble(route -> routeHubPressure(route, capacity)));

        Solution repaired = new Solution();
        for (AssignedRoute route : routes) {
            int qty = route.getBatch().quantity();
            if (!fitsAlongRoute(route, qty, capacity)) {
                continue;
            }
            repaired.addRoute(route);
            capacity.applyRoute(route);
        }
        return repaired;
    }

    /** Presión relativa: mayor ocupación en hubs tocados → se intenta más tarde (o se descarta). */
    private static double routeHubPressure(AssignedRoute route, CapacityContext capacity) {
        double worst = 0.0;
        List<Airport> hubs = new ArrayList<>();
        hubs.add(route.getBatch().origin());
        for (Flight flight : route.getFlights()) {
            hubs.add(flight.destination());
        }
        for (Airport hub : hubs) {
            int cap = hub.storageCapacity();
            if (cap <= 0) {
                continue;
            }
            worst = Math.max(worst, capacity.storageOccupancy(hub) / (double) cap);
        }
        return worst;
    }
    
    /**
     * Aplica mutación a una solución.
     * 
     * <p>Selecciona 1-3 lotes aleatorios y regenera sus rutas usando RouteGenerator.
     * Esto introduce diversidad genética y ayuda a escapar de óptimos locales.
     * 
     * <p>Usa ThreadLocalRandom para thread-safety sin sincronización.
     * 
     * <p><strong>Validates: Requirements 8.4, 10.5</strong>
     * 
     * @param solution Solución a mutar (se modifica in-place)
     * @param batches Lista EFECTIVA post-split de la semilla (Tarea A) — NUNCA la lista
     *     original: sus ids deben coincidir 1:1 con las claves de {@code solution.getRoutes()},
     *     o {@code solution.getRoute(batch.batchId())} da falso null y se duplica la cantidad
     *     completa del lote además de sus sub-lotes ya existentes (Defecto verificado #2)
     */
    /**
     * Probabilidad de generar la ruta mutada SIN cache (diversidad genuina + multi-hop).
     * Subida desde 0.30: en la banda Sc=90min el cache homogeneizaba la población.
     */
    private static final double FRESH_ROUTE_PROBABILITY = 0.45;

    /** Probabilidad de sesgar la mutación hacia rutas multi-hop (balanceo de hubs). */
    private static final double MULTI_HOP_MUTATION_PROBABILITY = 0.40;

    private void mutate(Solution solution, List<ShipmentBatch> batches) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int mutationCount = random.nextInt(1, Math.min(4, batches.size() + 1));
        CapacityContext capacity = planningCapacityWith(solution.getRoutes().values());

        for (int m = 0; m < mutationCount; m++) {
            ShipmentBatch batch = batches.get(random.nextInt(batches.size()));
            AssignedRoute existing = solution.getRoute(batch.batchId());

            // Tarea E — admisión de origen: un lote SIN ruta previa (típicamente uno que quedó
            // sin asignar en la semilla) no debe forzarse si ni siquiera cabe en el almacén de
            // origen; intentarlo solo produce búsquedas fallidas repetidas. Si YA tenía ruta,
            // removerla libera su propio espacio de origen, así que no aplica este chequeo.
            if (existing == null && capacity.storageResidual(
                    batch.origin(),
                    batch.ingressTime(),
                    batch.ingressTime().plus(batch.calculateSLA())) < batch.quantity()) {
                continue;
            }

            if (existing != null) {
                capacity.removeRoute(existing);
            }

            boolean preferMultiHop = random.nextDouble() < MULTI_HOP_MUTATION_PROBABILITY;
            AssignedRoute newRoute;
            if (random.nextDouble() < FRESH_ROUTE_PROBABILITY) {
                newRoute = routeGenerator.generateFeasibleRouteNoCache(batch, capacity, preferMultiHop);
            } else if (preferMultiHop) {
                newRoute = routeGenerator.generateFeasibleRoutePreferMultiHop(batch, capacity);
            } else {
                newRoute = routeGenerator.generateFeasibleRoute(batch, capacity);
            }
            if (newRoute != null) {
                solution.addRoute(newRoute);
                capacity.applyRoute(newRoute);
            } else if (existing != null) {
                // Restaurar ocupación si no hubo reemplazo
                capacity.applyRoute(existing);
            }
        }
    }
    
    /**
     * Ejecuta el algoritmo genético para optimizar la planificación de lotes.
     * 
     * <p>Proceso:
     * <ol>
     *   <li>Inicializa población con rutas factibles</li>
     *   <li>Itera durante generations generaciones</li>
     *   <li>En cada generación:
     *     <ul>
     *       <li>Evalúa fitness de toda la población</li>
     *       <li>Ordena por fitness (menor es mejor)</li>
     *       <li>Aplica elitismo: preserva mejores soluciones</li>
     *       <li>Genera nueva población con selección/crossover/mutación</li>
     *     </ul>
     *   </li>
     *   <li>Imprime progreso: generación actual y mejor fitness</li>
     *   <li>Retorna mejor solución encontrada</li>
     * </ol>
     * 
     * <p><strong>Validates: Requirements 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 16.3</strong>
     * 
     * @param batches Lista de lotes a planificar
     * @return Mejor solución encontrada
     */
    @Override
    public Solution optimize(List<ShipmentBatch> batches) {
        if (batches == null) {
            throw new NullPointerException("Batches cannot be null");
        }

        // Configurar evaluador con cantidad esperada de lotes y de MALETAS (Tarea F). El
        // conteo de maletas se fija con la lista ORIGINAL del ciclo — los splits de la
        // semilla greedy solo fragmentan lotes existentes, nunca cambian el total transportable.
        evaluator.setExpectedBatchCount(batches.size());
        evaluator.setExpectedBagCount(batches.stream().mapToInt(ShipmentBatch::quantity).sum());
        if (batches.isEmpty()) {
            Solution empty = new Solution();
            empty.setFitness(evaluator.evaluate(empty));
            return empty;
        }

        // PRIMER CICLO RÁPIDO: en el ciclo 1 la red está vacía — la semilla greedy ya es
        // casi óptima y la búsqueda profunda no aporta (medido: 28 generaciones y 2
        // corridas SIN ninguna mejora de fitness). Presupuesto recortado y sin reinicios.
        final boolean firstCall = !firstOptimizeDone;
        firstOptimizeDone = true;
        final long effectiveBudgetMs = maxTimeMillis > 0 && firstCall && firstCycleBudgetRatio < 1.0
            ? Math.max(2_000L, Math.round(maxTimeMillis * firstCycleBudgetRatio))
            : maxTimeMillis;

        // Deadline duro global: nunca exceder el presupuesto de tiempo (Ta).
        final long startMs = System.currentTimeMillis();
        final long deadline = effectiveBudgetMs > 0 ? startMs + effectiveBudgetMs : Long.MAX_VALUE;

        // ===== DISEÑO ANYTIME (sin umbrales por cantidad de lotes) =====
        // FASE 1 — Semilla greedy capacity-aware, CRONOMETRADA. Su costo real es el mejor
        // estimador del costo de construir cada individuo adicional de la población: la
        // decisión de correr o no la evolución poblacional se toma con tiempo MEDIDO en
        // esta máquina y este ciclo, no con un umbral fijo de lotes que hay que recalibrar
        // en cada VM.
        SeedResult seedResult = buildHeuristicSolution(batches, deadline);
        Solution seed = seedResult.seed();
        // Lista EFECTIVA post-split (Tarea A): población, crossover (por ids) y mutación
        // operan sobre ESTA lista, nunca sobre `batches` — sus ids coinciden exactamente con
        // las claves de las rutas de la semilla, así que la mutación nunca genera una ruta
        // "fantasma" para un lote que en realidad ya está partido en sub-lotes.
        List<ShipmentBatch> effectiveBatches = seedResult.effectiveBatches();
        seed.setFitness(evaluator.evaluate(seed));
        final long seedMs = Math.max(1, System.currentTimeMillis() - startMs);
        final long remainingAfterSeed = deadline - System.currentTimeMillis();

        // FASE 2 — ¿Cabe una población? Cada individuo extra cuesta ~seedMs; reservamos
        // la mitad del presupuesto restante para las GENERACIONES (evaluación, crossover,
        // mutación), que es donde la evolución realmente paga.
        final int affordablePopulation = effectiveBudgetMs <= 0
            ? populationSize
            : (int) Math.min(populationSize, 1 + remainingAfterSeed / (2 * seedMs));

        if (affordablePopulation < 3) {
            System.out.printf(
                "🌱 GA anytime: semilla greedy (%d rutas) en %d ms; población no cabe en el "
                + "presupuesto restante (%d ms) — el refinamiento Tabú usará el resto del Ta%n",
                seed.getRoutes().size(), seedMs, remainingAfterSeed);
            return seed;
        }

        // Generaciones/paciencia gobernadas por el deadline: el tope de generaciones es
        // generoso y quien corta es el presupuesto o el estancamiento, nunca un umbral.
        final int effectivePopulationSize = affordablePopulation;
        final int effectiveGenerations = effectiveBudgetMs > 0
            ? Math.max(generations, 40)
            : generations;
        final int effectiveStagnationLimit = Math.max(stagnationLimit, 6);

        // REINICIOS ITERADOS: si la evolución converge y sobra presupuesto (≥30% del
        // total), relanzar con población fresca. Sin reinicios en el primer ciclo.
        final boolean restartsEnabled = effectiveBudgetMs > 0
            && !(firstCall && firstCycleBudgetRatio < 1.0);
        final int maxRestarts = restartsEnabled ? 6 : 1;
        final int maxRestartsWithoutImprovement = 2;

        // La semilla es el piso de calidad: el resultado NUNCA es peor que ella.
        Solution globalBest = seed;
        int runs = 0;
        int improvements = 0;
        int runsWithoutImprovement = 0;
        int totalGenerations = 0;
        double firstRunFitness = Double.NaN;

        while (runs < maxRestarts) {
            long remaining = deadline - System.currentTimeMillis();
            // Reiniciar solo si queda al menos ~30% del presupuesto (evita corridas truncadas).
            if (runs > 0 && (remaining < effectiveBudgetMs * 0.30 || runsWithoutImprovement >= maxRestartsWithoutImprovement)) {
                break;
            }

            EvolutionResult result = runEvolution(
                effectiveBatches, effectivePopulationSize, effectiveGenerations, effectiveStagnationLimit,
                deadline, seed);
            runs++;
            totalGenerations += result.generationsExecuted;
            if (runs == 1) {
                firstRunFitness = result.best.getFitness();
            }

            // Aceptar solo si mejora el fitness SIN transportar menos MALETAS que el mejor
            // actual (Tarea B — refuerzo del invariante de transporte de initializePopulation,
            // ahora medido por maletas en vez de número de rutas por la misma razón: los
            // splits de la semilla inflan el conteo de rutas sin inflar las maletas).
            if (result.best.getFitness() < globalBest.getFitness() - 0.01
                    && result.best.getTotalBags() >= globalBest.getTotalBags()) {
                if (runs > 1) improvements++;
                globalBest = result.best;
                runsWithoutImprovement = 0;
            } else {
                runsWithoutImprovement++;
            }

            if (System.currentTimeMillis() >= deadline) break;
        }

        // Log liviano de UNA línea: semilla, población que cupo, corridas, generaciones,
        // fitness semilla → final, y tiempo usado del presupuesto.
        System.out.printf(
            "🧬 GA anytime: semilla %dms, población=%d, corridas=%d, gens=%d, fitness %.2f→%.2f en %dms%n",
            seedMs, effectivePopulationSize, runs, totalGenerations,
            Double.isNaN(firstRunFitness) ? seed.getFitness() : firstRunFitness,
            globalBest.getFitness(),
            System.currentTimeMillis() - startMs
        );

        return globalBest;
    }

    /** Resultado de una corrida evolutiva: mejor solución y generaciones ejecutadas. */
    private record EvolutionResult(Solution best, int generationsExecuted) {}

    /**
     * Una corrida evolutiva completa (población fresca → early-stop/deadline/límite de
     * generaciones). Extraída de optimize() para poder reiniciarla con semillas nuevas.
     *
     * @param effectiveBatches Lista EFECTIVA post-split de la semilla (Tarea A) — NUNCA la
     *     lista original de lotes del ciclo. Gobierna initializePopulation y mutate().
     */
    private EvolutionResult runEvolution(
            List<ShipmentBatch> effectiveBatches,
            int effectivePopulationSize,
            int effectiveGenerations,
            int effectiveStagnationLimit,
            long deadline,
            Solution seed) {

        // 1. Inicializar población con rutas factibles (aleatoriedad nueva en cada corrida)
        List<Solution> population = initializePopulation(effectiveBatches, effectivePopulationSize, deadline, seed);

        double bestFitnessSoFar = Double.MAX_VALUE;
        int stagnationCounter = 0;
        int gensExecuted = 0;

        // 2. Evolucionar durante N generaciones (con early stopping)
        for (int gen = 0; gen < effectiveGenerations; gen++) {
            if (System.currentTimeMillis() >= deadline) {
                System.out.printf("⏱️ GA detenido por presupuesto de tiempo en generación %d%n", gen);
                break;
            }
            gensExecuted = gen + 1;
            // Evaluar fitness de toda la población
            evaluatePopulation(population, effectivePopulationSize);

            // Ordenar por fitness (menor es mejor)
            population.sort(Comparator.comparingDouble(Solution::getFitness));

            // Early stopping: detectar convergencia prematura
            double currentBest = population.get(0).getFitness();
            if (currentBest < bestFitnessSoFar - 0.01) {
                bestFitnessSoFar = currentBest;
                stagnationCounter = 0;
            } else {
                stagnationCounter++;
            }

            if (stagnationCounter >= effectiveStagnationLimit) {
                break;
            }

            // Crear nueva generación
            List<Solution> nextGeneration = new ArrayList<>();

            // Elitismo: preservar los mejores TAL CUAL. Repararlos aquí los degradaba
            // generación a generación (cada pasada puede descartar alguna ruta más), que es
            // justo lo contrario de lo que el elitismo debe garantizar. La reparación se
            // aplica una sola vez, al mejor final de la corrida.
            int effectiveEliteCount = Math.min(eliteCount, Math.max(1, effectivePopulationSize / 8));
            for (int i = 0; i < effectiveEliteCount && i < population.size(); i++) {
                nextGeneration.add(new Solution(population.get(i)));
            }

            // Generar resto de la población
            while (nextGeneration.size() < effectivePopulationSize) {
                Solution parent1 = tournamentSelection(population);
                Solution parent2 = tournamentSelection(population);
                Solution child = crossover(parent1, parent2);

                if (ThreadLocalRandom.current().nextDouble() < mutationRate) {
                    mutate(child, effectiveBatches);
                }

                nextGeneration.add(child);
            }

            population = nextGeneration;
        }

        // Evaluar población final y retornar mejor (reparada: nunca publicar desborde duro)
        evaluatePopulation(population, effectivePopulationSize);
        population.sort(Comparator.comparingDouble(Solution::getFitness));
        return new EvolutionResult(repairHardStorageCapacity(population.get(0)), gensExecuted);
    }
    
    /**
     * Propaga la ocupación de almacén preexistente (rutas de ciclos previos) al evaluador
     * y a la construcción capacity-aware de rutas de este ciclo.
     */
    /** Solo baseline de construcción en CapacityContext. El fitness usa {@link #setEvaluatorStorageBaseline}. */
    public void setStorageBaseline(Map<Airport, Integer> baseline) {
        this.storageBaseline = baseline != null ? baseline : Map.of();
        rebuildCommittedCapacityCache();
    }

    /**
     * Rutas ya publicadas de ciclos previos: se cargan en la timeline time-phased para que
     * ARRIVALs futuros (maletas en vuelo) reserven hub/capacidad real.
     */
    public void setCommittedRoutes(Collection<AssignedRoute> routes) {
        this.committedRoutes = (routes == null || routes.isEmpty())
            ? List.of()
            : List.copyOf(new ArrayList<>(routes));
        rebuildCommittedCapacityCache();
    }

    /**
     * Solo el evaluador (fitness): pico ATP escalar para penalizar desbalance, sin usarlo
     * como piso duro en {@link CapacityContext}.
     */
    public void setEvaluatorStorageBaseline(Map<Airport, Integer> baseline) {
        this.evaluator.setStorageBaseline(baseline != null ? baseline : Map.of());
    }

    /**
     * Configura parámetros del algoritmo genético.
     * 
     * <p>Lee parámetros de AlgorithmConfig y actualiza los campos correspondientes.
     * Parámetros soportados:
     * <ul>
     *   <li>populationSize: Tamaño de la población (default: 50)</li>
     *   <li>generations: Número de generaciones (default: 100)</li>
     *   <li>mutationRate: Tasa de mutación (default: 0.1)</li>
     *   <li>tournamentSize: Tamaño del torneo (default: 4)</li>
     *   <li>eliteCount: Número de élites a preservar (default: 2)</li>
    *   <li>parallelEnabled: Habilita paralelismo si la VM tiene CPUs suficientes</li>
    *   <li>parallelMinProcessors: CPUs mínimos para paralelizar (default: 3)</li>
    *   <li>parallelPopulationThreshold: población mínima para paralelizar (default: 32)</li>
     * </ul>
     * 
     * <p>NOTA: randomSeed ya no es configurable. Se usa ThreadLocalRandom
     * para paralelización thread-safe.
     * 
     * <p><strong>Validates: Requirements 15.1, 15.2, 15.3</strong>
     * 
     * @param config Configuración con parámetros
     * @throws NullPointerException si config es null
     */
    @Override
    public void configure(AlgorithmConfig config) {
        if (config == null) {
            throw new NullPointerException("Config cannot be null");
        }
        
        this.populationSize = config.getInt("populationSize", 50);
        this.generations = config.getInt("generations", 100);
        this.mutationRate = config.getDouble("mutationRate", 0.15);
        this.tournamentSize = config.getInt("tournamentSize", 4);
        this.eliteCount = config.getInt("eliteCount", 2);
        this.stagnationLimit = config.getInt("stagnationLimit", 15);
        this.parallelEnabled = config.getBoolean("parallelEnabled", true);
        this.parallelMinProcessors = config.getInt("parallelMinProcessors", 3);
        this.parallelPopulationThreshold = config.getInt("parallelPopulationThreshold", 32);
        this.routeSearchAttempts = config.getInt("routeSearchAttempts", 12);
        this.routeCachedVariants = config.getInt("routeCachedVariants", 5);
        this.maxTimeMillis = config.getInt("maxTimeMillis", 0);
        this.firstCycleBudgetRatio = config.getDouble("firstCycleBudgetRatio", 1.0);
        this.firstOptimizeDone = false;
        this.routeGenerator.configureSearchEffort(routeSearchAttempts, routeCachedVariants);
    }
}
