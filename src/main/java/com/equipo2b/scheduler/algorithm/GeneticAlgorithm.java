package com.equipo2b.scheduler.algorithm;

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
    private int largeVolumeBatchThreshold = 2_500;
    private int routeSearchAttempts = 12;
    private int routeCachedVariants = 3;
    private long maxTimeMillis = 0;  // 0 = sin límite; >0 = deadline duro por ciclo (presupuesto Ta)
    
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
    private List<Solution> initializePopulation(List<ShipmentBatch> batches, int effectivePopulationSize) {
        Set<String> unroutableBatches = Collections.synchronizedSet(new HashSet<>());
        
        Stream<Integer> populationIndexes = IntStream.range(0, effectivePopulationSize).boxed();
        if (shouldUseParallelism(effectivePopulationSize)) {
            populationIndexes = populationIndexes.parallel();
        }

        List<Solution> population = populationIndexes.map(i -> {
                Solution solution = new Solution();
                
                List<ShipmentBatch> shuffledBatches = new ArrayList<>(batches);
                if (i == 0) {
                    shuffledBatches.sort(Comparator
                        .comparing(ShipmentBatch::ingressTime)
                        .thenComparing(ShipmentBatch::batchId));
                } else {
                    // IMPORTANTE: Shufflear lotes para cada individuo para generar diversidad
                    // ThreadLocalRandom es thread-safe sin sincronización
                    Collections.shuffle(shuffledBatches, ThreadLocalRandom.current());
                }
                
                for (ShipmentBatch batch : shuffledBatches) {
                    AssignedRoute route = i == 0
                        ? routeGenerator.generateEarliestFeasibleRoute(batch)
                        : routeGenerator.generateFeasibleRoute(batch);
                    if (route != null) {
                        solution.addRoute(route);
                    } else {
                        // Solo registrar warning la primera vez que encontramos un lote no ruteable
                        if (i == 0) {
                            unroutableBatches.add(batch.batchId());
                        }
                    }
                }
                
                return solution;
            })
            .collect(Collectors.toList());
        
        // Mostrar resumen de lotes no ruteables (solo una vez)
        if (!unroutableBatches.isEmpty()) {
            System.err.printf("Warning: %d batches could not be routed (no feasible path found)%n", 
                            unroutableBatches.size());
            if (unroutableBatches.size() <= 10) {
                System.err.println("Unroutable batches: " + unroutableBatches);
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

    private int effectivePopulationSize(int batchCount) {
        if (batchCount >= 4_000) return Math.min(populationSize, 6);
        if (batchCount >= 2_000) return Math.min(populationSize, 8);
        if (batchCount >= 1_000) return Math.min(populationSize, 10);
        if (batchCount >= 500) return Math.min(populationSize, 14);
        return populationSize;
    }

    private int effectiveGenerations(int batchCount) {
        if (batchCount >= 4_000) return Math.min(generations, 3);
        if (batchCount >= 2_000) return Math.min(generations, 4);
        if (batchCount >= 1_000) return Math.min(generations, 5);
        if (batchCount >= 500) return Math.min(generations, 7);
        return generations;
    }

    private int effectiveStagnationLimit(int batchCount, int effectiveGenerations) {
        if (batchCount >= 4_000) return Math.min(stagnationLimit, 3);
        if (batchCount >= 500) return Math.min(stagnationLimit, 4);
        return Math.min(stagnationLimit, effectiveGenerations);
    }

    private Solution buildHeuristicSolution(List<ShipmentBatch> batches) {
        Solution solution = new Solution();
        List<ShipmentBatch> orderedBatches = new ArrayList<>(batches);
        orderedBatches.sort(Comparator.comparing(ShipmentBatch::ingressTime));

        int unroutable = 0;
        for (ShipmentBatch batch : orderedBatches) {
            AssignedRoute route = routeGenerator.generateEarliestFeasibleRoute(batch);
            if (route != null) {
                solution.addRoute(route);
            } else {
                unroutable++;
            }
        }

        if (unroutable > 0) {
            System.err.printf("Warning: %d batches could not be routed in large-volume mode%n", unroutable);
        }
        return solution;
    }

    private Solution optimizeLargeVolume(List<ShipmentBatch> batches) {
        long startMs = System.currentTimeMillis();
        System.out.printf(
            "Carga masiva (%d lotes): usando planificación heurística cacheada para respetar CPU/RAM%n",
            batches.size()
        );

        Solution solution = buildHeuristicSolution(batches);
        evaluator.evaluate(solution);

        System.out.printf(
            "Planificación masiva completada en %.1fs: rutas=%d, fitness=%.2f%n",
            (System.currentTimeMillis() - startMs) / 1000.0,
            solution.getRoutes().size(),
            solution.getFitness()
        );
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
    /** Probabilidad de generar la ruta mutada SIN cache (diversidad genuina en el pool). */
    private static final double FRESH_ROUTE_PROBABILITY = 0.30;

    private void mutate(Solution solution, List<ShipmentBatch> batches) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        // Mutar 1-3 genes aleatorios (en vez de iterar todos con 5%)
        int mutationCount = random.nextInt(1, Math.min(4, batches.size() + 1));

        for (int m = 0; m < mutationCount; m++) {
            ShipmentBatch batch = batches.get(random.nextInt(batches.size()));
            // Bypass probabilístico del cache de rutas: el cache limita cada lote a ≤3
            // variantes y homogeneiza la población en 2-3 generaciones (fitness estancado).
            // Un 30% de mutaciones con BFS fresco inyecta rutas nuevas; el 70% cacheado
            // mantiene el costo de CPU acotado bajo carga.
            AssignedRoute newRoute = random.nextDouble() < FRESH_ROUTE_PROBABILITY
                ? routeGenerator.generateFeasibleRouteNoCache(batch)
                : routeGenerator.generateFeasibleRoute(batch);
            if (newRoute != null) {
                solution.addRoute(newRoute);
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

        if (batches.size() >= largeVolumeBatchThreshold) {
            return optimizeLargeVolume(batches);
        }

        int effectivePopulationSize = effectivePopulationSize(batches.size());
        int effectiveGenerations = effectiveGenerations(batches.size());
        int effectiveStagnationLimit = effectiveStagnationLimit(batches.size(), effectiveGenerations);

        if (effectivePopulationSize != populationSize || effectiveGenerations != generations) {
            System.out.printf(
                "Carga alta (%d lotes): GA adaptativo población=%d, generaciones=%d%n",
                batches.size(), effectivePopulationSize, effectiveGenerations
            );
        }

        // Deadline duro global: nunca exceder el presupuesto de tiempo (Ta).
        final long startMs = System.currentTimeMillis();
        final long deadline = maxTimeMillis > 0 ? startMs + maxTimeMillis : Long.MAX_VALUE;

        // PACIENCIA ADAPTATIVA AL PRESUPUESTO: con carga ligera el presupuesto Ta queda
        // >99% sin usar; cortar tras 4 generaciones sin mejora ahorraba un tiempo que no
        // necesitábamos ahorrar. Con presupuesto configurado y carga baja, exploramos más.
        if (maxTimeMillis > 0 && batches.size() < 500) {
            effectiveStagnationLimit = Math.max(effectiveStagnationLimit, 12);
            effectiveGenerations = Math.max(effectiveGenerations, 40);
        }

        // REINICIOS ITERADOS: cuando la evolución converge (early-stop) y sobra presupuesto,
        // relanzar con población fresca conserva el mejor global y ataca el estancamiento
        // causado por la baja diversidad inicial (cache de rutas). Solo con carga baja
        // (<500 lotes) y siempre bajo el deadline duro — bajo carga alta se corre una vez,
        // exactamente como antes.
        final boolean restartsEnabled = maxTimeMillis > 0 && batches.size() < 500;
        final int maxRestarts = restartsEnabled ? 6 : 1;
        final int maxRestartsWithoutImprovement = 2;

        Solution globalBest = null;
        int runs = 0;
        int improvements = 0;
        int runsWithoutImprovement = 0;
        int totalGenerations = 0;
        double firstRunFitness = Double.NaN;

        while (runs < maxRestarts) {
            long remaining = deadline - System.currentTimeMillis();
            // Reiniciar solo si queda al menos ~30% del presupuesto (evita corridas truncadas).
            if (runs > 0 && (remaining < maxTimeMillis * 0.30 || runsWithoutImprovement >= maxRestartsWithoutImprovement)) {
                break;
            }

            EvolutionResult result = runEvolution(
                batches, effectivePopulationSize, effectiveGenerations, effectiveStagnationLimit, deadline);
            runs++;
            totalGenerations += result.generationsExecuted;
            if (runs == 1) {
                firstRunFitness = result.best.getFitness();
            }

            if (globalBest == null || result.best.getFitness() < globalBest.getFitness() - 0.01) {
                if (globalBest != null) improvements++;
                globalBest = result.best;
                runsWithoutImprovement = 0;
            } else {
                runsWithoutImprovement++;
            }

            if (System.currentTimeMillis() >= deadline) break;
        }

        // Log liviano de UNA línea con lo necesario para analizar la búsqueda:
        // corridas (1=sin reinicio), generaciones totales, fitness 1ª corrida → final
        // (delta = ganancia de los reinicios), y tiempo usado del presupuesto.
        if (runs > 1 || improvements > 0) {
            System.out.printf(
                "🧬 GA: corridas=%d gens=%d fitness %.2f→%.2f (mejoras por reinicio=%d) en %dms%n",
                runs, totalGenerations, firstRunFitness, globalBest.getFitness(), improvements,
                System.currentTimeMillis() - startMs
            );
        }

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
            long deadline) {

        // 1. Inicializar población con rutas factibles (aleatoriedad nueva en cada corrida)
        List<Solution> population = initializePopulation(batches, effectivePopulationSize);

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
        this.largeVolumeBatchThreshold = config.getInt("largeVolumeBatchThreshold", 2_500);
        this.routeSearchAttempts = config.getInt("routeSearchAttempts", 12);
        this.routeCachedVariants = config.getInt("routeCachedVariants", 3);
        this.maxTimeMillis = config.getInt("maxTimeMillis", 0);
        this.routeGenerator.configureSearchEffort(routeSearchAttempts, routeCachedVariants);
    }
}
