package com.equipo2b.scheduler.algorithm;

import com.equipo2b.scheduler.logic.CapacityContext;
import com.equipo2b.scheduler.logic.RouteGenerator;
import com.equipo2b.scheduler.logic.SolutionEvaluator;
import com.equipo2b.scheduler.model.*;

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
    /** Línea base de almacén del ciclo (para CapacityContext en construcción de rutas). */
    private volatile Map<Airport, Integer> storageBaseline = Map.of();
    
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
     * @param batches Lista de lotes para los cuales generar rutas
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
                CapacityContext capacity = CapacityContext.fromBaseline(storageBaseline);

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

        // INVARIANTE DE TRANSPORTE: ningún individuo compite con MENOS rutas que la
        // semilla. Bajo saturación real, el fitness puede preferir una solución parcial
        // (50k/lote sin asignar es más barato que el exceso de almacén acumulado) y un
        // individuo truncado por deadline le GANABA a la semilla completa — el ciclo
        // terminaba con 12-64% de asignación. Los truncados/vacíos se reemplazan por
        // copias de la semilla; como crossover y mutación nunca reducen el número de
        // rutas, el invariante se conserva en toda la evolución.
        final int seedRouteCount = seed.getRoutes().size();
        for (int j = 0; j < population.size(); j++) {
            if (population.get(j).getRoutes().size() < seedRouteCount) {
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

    private Solution buildHeuristicSolution(List<ShipmentBatch> batches, long deadline) {
        Solution solution = new Solution();
        List<ShipmentBatch> orderedBatches = new ArrayList<>(batches);
        orderedBatches.sort(Comparator.comparing(ShipmentBatch::ingressTime));
        CapacityContext capacity = CapacityContext.fromBaseline(storageBaseline);

        int unroutable = 0;
        int routed = 0;
        for (ShipmentBatch batch : orderedBatches) {
            if (System.currentTimeMillis() >= deadline) {
                System.out.printf(
                    "⏱ Semilla greedy truncada por Ta tras %d/%d lotes%n",
                    routed, orderedBatches.size());
                break;
            }
            AssignedRoute route = routeGenerator.generateEarliestFeasibleRoute(batch, capacity);
            if (route != null) {
                solution.addRoute(route);
                capacity.applyRoute(route);
                routed++;
            } else {
                unroutable++;
            }
        }

        if (unroutable > 0) {
            System.err.printf("Warning: %d batches could not be routed in seed construction%n", unroutable);
        }
        return solution;
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
        
        return child;
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
     * @param batches Lista de lotes disponibles
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
        CapacityContext capacity = CapacityContext.fromSolution(
            solution.getRoutes().values(), storageBaseline);

        for (int m = 0; m < mutationCount; m++) {
            ShipmentBatch batch = batches.get(random.nextInt(batches.size()));
            AssignedRoute existing = solution.getRoute(batch.batchId());
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

        // Configurar evaluador con cantidad esperada de lotes
        evaluator.setExpectedBatchCount(batches.size());
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
        Solution seed = buildHeuristicSolution(batches, deadline);
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
                batches, effectivePopulationSize, effectiveGenerations, effectiveStagnationLimit, deadline, seed);
            runs++;
            totalGenerations += result.generationsExecuted;
            if (runs == 1) {
                firstRunFitness = result.best.getFitness();
            }

            // Aceptar solo si mejora el fitness SIN transportar menos que el mejor actual
            // (refuerzo del invariante de transporte de initializePopulation).
            if (result.best.getFitness() < globalBest.getFitness() - 0.01
                    && result.best.getRoutes().size() >= globalBest.getRoutes().size()) {
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
     */
    private EvolutionResult runEvolution(
            List<ShipmentBatch> batches,
            int effectivePopulationSize,
            int effectiveGenerations,
            int effectiveStagnationLimit,
            long deadline,
            Solution seed) {

        // 1. Inicializar población con rutas factibles (aleatoriedad nueva en cada corrida)
        List<Solution> population = initializePopulation(batches, effectivePopulationSize, deadline, seed);

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

            // Elitismo: preservar mejores soluciones
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
                    mutate(child, batches);
                }

                nextGeneration.add(child);
            }

            population = nextGeneration;
        }

        // Evaluar población final y retornar mejor
        evaluatePopulation(population, effectivePopulationSize);
        population.sort(Comparator.comparingDouble(Solution::getFitness));
        return new EvolutionResult(population.get(0), gensExecuted);
    }
    
    /**
     * Propaga la ocupación de almacén preexistente (rutas de ciclos previos) al evaluador
     * y a la construcción capacity-aware de rutas de este ciclo.
     */
    public void setStorageBaseline(Map<Airport, Integer> baseline) {
        this.storageBaseline = baseline != null ? baseline : Map.of();
        this.evaluator.setStorageBaseline(this.storageBaseline);
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
