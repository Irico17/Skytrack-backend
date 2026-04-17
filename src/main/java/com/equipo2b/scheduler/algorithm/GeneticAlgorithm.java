package com.equipo2b.scheduler.algorithm;

import com.equipo2b.scheduler.logic.RouteGenerator;
import com.equipo2b.scheduler.logic.SolutionEvaluator;
import com.equipo2b.scheduler.model.*;

import java.util.*;

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
    private final Random random;
    
    // Parámetros configurables con valores por defecto
    private int populationSize = 50;
    private int generations = 100;
    private double mutationRate = 0.1;
    private int tournamentSize = 4;
    private int eliteCount = 2;
    
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
        this.random = new Random();
    }
    
    /**
     * Inicializa la población con soluciones candidatas.
     * 
     * <p>Para cada solución en la población, genera rutas factibles para todos
     * los lotes usando RouteGenerator. Si no se puede generar una ruta para
     * algún lote, registra un warning pero continúa con los demás lotes.
     * 
     * <p><strong>Validates: Requirement 10.1</strong>
     * 
     * @param batches Lista de lotes para los cuales generar rutas
     * @return Población inicial de soluciones
     */
    private List<Solution> initializePopulation(List<ShipmentBatch> batches) {
        List<Solution> population = new ArrayList<>();
        
        for (int i = 0; i < populationSize; i++) {
            Solution solution = new Solution();
            
            for (ShipmentBatch batch : batches) {
                AssignedRoute route = routeGenerator.generateFeasibleRoute(batch);
                if (route != null) {
                    solution.addRoute(route);
                } else {
                    // Registrar warning si no se puede generar ruta factible
                    System.err.printf("Warning: Could not generate route for batch %s%n", 
                                    batch.batchId());
                }
            }
            
            population.add(solution);
        }
        
        return population;
    }
    
    /**
     * Evalúa el fitness de cada solución no evaluada en la población.
     * 
     * <p>Usa SolutionEvaluator para calcular el fitness y marca las soluciones
     * como evaluadas para evitar recálculos innecesarios.
     * 
     * <p><strong>Validates: Requirement 10.2</strong>
     * 
     * @param population Población de soluciones a evaluar
     */
    private void evaluatePopulation(List<Solution> population) {
        for (Solution solution : population) {
            if (!solution.isEvaluated()) {
                double fitness = evaluator.evaluate(solution);
                solution.setFitness(fitness);
            }
        }
    }
    
    /**
     * Selecciona un individuo mediante selección por torneo.
     * 
     * <p>Selecciona tournamentSize candidatos aleatorios de la población
     * y retorna el de mejor fitness (menor valor).
     * 
     * <p><strong>Validates: Requirement 10.3</strong>
     * 
     * @param population Población de soluciones
     * @return Solución ganadora del torneo
     */
    private Solution tournamentSelection(List<Solution> population) {
        Solution best = null;
        
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
     * <p><strong>Validates: Requirements 8.3, 10.4</strong>
     * 
     * @param parent1 Primer padre
     * @param parent2 Segundo padre
     * @return Solución hijo resultado del crossover
     */
    private Solution crossover(Solution parent1, Solution parent2) {
        Solution child = new Solution();
        
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
     * <p>Para cada lote, con probabilidad geneMutationRate (2%), regenera
     * la ruta usando RouteGenerator. Esto introduce diversidad genética
     * y ayuda a escapar de óptimos locales.
     * 
     * <p><strong>Validates: Requirements 8.4, 10.5</strong>
     * 
     * @param solution Solución a mutar (se modifica in-place)
     * @param batches Lista de lotes disponibles
     */
    private void mutate(Solution solution, List<ShipmentBatch> batches) {
        double geneMutationRate = 0.02;  // 2% de genes mutados
        
        for (ShipmentBatch batch : batches) {
            if (random.nextDouble() < geneMutationRate) {
                AssignedRoute newRoute = routeGenerator.generateFeasibleRoute(batch);
                if (newRoute != null) {
                    solution.addRoute(newRoute);
                }
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
        
        // 1. Inicializar población con rutas factibles
        List<Solution> population = initializePopulation(batches);
        
        // 2. Evolucionar durante N generaciones
        for (int gen = 0; gen < generations; gen++) {
            // Evaluar fitness de toda la población
            evaluatePopulation(population);
            
            // Ordenar por fitness (menor es mejor)
            population.sort(Comparator.comparingDouble(Solution::getFitness));
            
            // Imprimir progreso
            System.out.printf("Generation %d - Best Fitness: %.2f%n", 
                            gen, population.get(0).getFitness());
            
            // Crear nueva generación
            List<Solution> nextGeneration = new ArrayList<>();
            
            // Elitismo: preservar mejores soluciones
            for (int i = 0; i < eliteCount && i < population.size(); i++) {
                nextGeneration.add(new Solution(population.get(i)));
            }
            
            // Generar resto de la población
            while (nextGeneration.size() < populationSize) {
                Solution parent1 = tournamentSelection(population);
                Solution parent2 = tournamentSelection(population);
                Solution child = crossover(parent1, parent2);
                
                if (random.nextDouble() < mutationRate) {
                    mutate(child, batches);
                }
                
                nextGeneration.add(child);
            }
            
            population = nextGeneration;
        }
        
        // Evaluar población final y retornar mejor
        evaluatePopulation(population);
        population.sort(Comparator.comparingDouble(Solution::getFitness));
        return population.get(0);
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
     * </ul>
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
        this.mutationRate = config.getDouble("mutationRate", 0.1);
        this.tournamentSize = config.getInt("tournamentSize", 4);
        this.eliteCount = config.getInt("eliteCount", 2);
    }
}
