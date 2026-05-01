package com.equipo2b.scheduler.execution;

import com.equipo2b.scheduler.algorithm.*;
import com.equipo2b.scheduler.logic.SolutionEvaluator;
import com.equipo2b.scheduler.model.*;
import com.equipo2b.scheduler.validation.RouteValidator;

/**
 * Factory para crear instancias de Scheduler configuradas con diferentes algoritmos.
 * 
 * <p>Soporta dos configuraciones:
 * <ul>
 *   <li>GATS: Algoritmo Genético + Búsqueda Tabú (híbrido)</li>
 *   <li>Tabu Puro: Búsqueda Tabú standalone</li>
 * </ul>
 * 
 * <p><strong>Validates: Caso de estudio punto a, b - Dos algoritmos metaheurísticos</strong>
 */
public class SchedulerFactory {
    
    /**
     * Crea Scheduler configurado con GATS (GA + Tabu refinamiento).
     * 
     * <p>Configuración:
     * <ul>
     *   <li>Algoritmo primario: Genetic Algorithm</li>
     *   <li>Refinamiento: Tabu Search</li>
     *   <li>Parámetros GA: población=50, generaciones=100, mutación=0.1</li>
     *   <li>Parámetros Tabu: iteraciones=200, tenure=15</li>
     * </ul>
     * 
     * @param flightPlan Plan de vuelos
     * @param airportManager Gestor de aeropuertos
     * @param shipmentQueue Cola de pedidos
     * @param evaluator Evaluador de fitness
     * @param validator Validador de soluciones
     * @param Ta Tiempo máximo de algoritmo (minutos)
     * @param Sa Salto entre ejecuciones (minutos)
     * @param K Constante de proporcionalidad
     * @return Scheduler configurado con GATS
     */
    public static Scheduler createGATSScheduler(
            FlightPlan flightPlan,
            AirportManager airportManager,
            ShipmentQueue shipmentQueue,
            SolutionEvaluator evaluator,
            RouteValidator validator,
            int Ta, int Sa, int K) {
        
        // Crear algoritmos
        GeneticAlgorithm ga = new GeneticAlgorithm(flightPlan, airportManager);
        TabuSearch tabu = new TabuSearch(flightPlan, airportManager);
        
        // Configurar parámetros balanceados con paralelización
        // Con paralelización, podemos usar más evaluaciones sin penalización de tiempo
        AlgorithmConfig gaConfig = new AlgorithmConfig();
        gaConfig.setInt("populationSize", 40);      // Balanceado: más que optimizado (30), menos que original (50)
        gaConfig.setInt("generations", 80);         // Balanceado: más que optimizado (50), menos que original (100)
        gaConfig.setDouble("mutationRate", 0.1);
        gaConfig.setInt("tournamentSize", 4);
        gaConfig.setInt("eliteCount", 2);
        ga.configure(gaConfig);
        
        AlgorithmConfig tabuConfig = new AlgorithmConfig();
        tabuConfig.setInt("maxIterations", 150);    // Balanceado: más que optimizado (100), menos que original (200)
        tabuConfig.setInt("tabuTenure", 15);
        tabuConfig.setInt("neighborhoodSize", 20);
        tabu.configure(tabuConfig);
        
        return new Scheduler(
            ga,                      // primaryAlgorithm = GA
            tabu,                    // tabuSearch para refine
            AlgorithmType.GATS,      // tipo
            true,                    // useRefinement = true
            shipmentQueue,
            evaluator,
            validator,
            Ta, Sa, K
        );
    }
    
    /**
     * Crea Scheduler configurado con Tabu Search puro (sin GA).
     * 
     * <p>Configuración:
     * <ul>
     *   <li>Algoritmo primario: Tabu Search</li>
     *   <li>Sin refinamiento adicional</li>
     *   <li>Parámetros Tabu: iteraciones=300 (más que en modo refine), tenure=20</li>
     * </ul>
     * 
     * @param flightPlan Plan de vuelos
     * @param airportManager Gestor de aeropuertos
     * @param shipmentQueue Cola de pedidos
     * @param evaluator Evaluador de fitness
     * @param validator Validador de soluciones
     * @param Ta Tiempo máximo de algoritmo (minutos)
     * @param Sa Salto entre ejecuciones (minutos)
     * @param K Constante de proporcionalidad
     * @return Scheduler configurado con Tabu puro
     */
    public static Scheduler createTabuScheduler(
            FlightPlan flightPlan,
            AirportManager airportManager,
            ShipmentQueue shipmentQueue,
            SolutionEvaluator evaluator,
            RouteValidator validator,
            int Ta, int Sa, int K) {
        
        // Crear solo Tabu
        TabuSearch tabu = new TabuSearch(flightPlan, airportManager);
        
        // Configurar parámetros (más iteraciones que en modo refine)
        AlgorithmConfig tabuConfig = new AlgorithmConfig();
        tabuConfig.setInt("maxIterations", 300);  // Más iteraciones para compensar falta de GA
        tabuConfig.setInt("tabuTenure", 20);
        tabuConfig.setInt("neighborhoodSize", 30);
        tabu.configure(tabuConfig);
        
        return new Scheduler(
            tabu,                    // primaryAlgorithm = Tabu
            tabu,                    // mismo objeto (no se usa para refine)
            AlgorithmType.TABU_PURE, // tipo
            false,                   // useRefinement = false (evita doble Tabu)
            shipmentQueue,
            evaluator,
            validator,
            Ta, Sa, K
        );
    }
}
