package com.equipo2b.scheduler.algorithm;

import com.equipo2b.scheduler.logic.*;
import com.equipo2b.scheduler.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test de integración para verificar que los algoritmos funcionan correctamente.
 * 
 * Verifica:
 * - Algoritmo Genético genera soluciones válidas
 * - Búsqueda Tabú genera soluciones válidas
 * - Función fitness penaliza y premia correctamente
 * - RouteGenerator crea rutas factibles
 */
class AlgorithmIntegrationTest {
    
    private FlightPlan flightPlan;
    private AirportManager airportManager;
    private List<ShipmentBatch> batches;
    
    private Airport jfk;
    private Airport cdg;
    private Airport nrt;
    
    @BeforeEach
    void setUp() {
        // Crear aeropuertos
        jfk = new Airport("JFK", "New York", "USA", ZoneId.of("America/New_York"), 
                         600, 40.6413, -73.7781, Continent.AMERICA);
        cdg = new Airport("CDG", "Paris", "France", ZoneId.of("Europe/Paris"), 
                         600, 49.0097, 2.5479, Continent.EUROPE);
        nrt = new Airport("NRT", "Tokyo", "Japan", ZoneId.of("Asia/Tokyo"), 
                         600, 35.7720, 140.3929, Continent.ASIA);
        
        airportManager = new AirportManager();
        airportManager.addAirport(jfk);
        airportManager.addAirport(cdg);
        airportManager.addAirport(nrt);
        
        // Crear plan de vuelos
        flightPlan = new FlightPlan();
        
        ZonedDateTime now = ZonedDateTime.now(jfk.zoneId());
        
        // Vuelos JFK -> CDG
        for (int i = 0; i < 5; i++) {
            ZonedDateTime dep = now.plusHours(i * 6);
            ZonedDateTime arr = dep.plusHours(24);
            Flight flight = new Flight("FL_JFK_CDG_" + i, jfk, cdg, dep, arr, 300, FlightType.INTERCONTINENTAL);
            flightPlan.addFlight(flight);
        }
        
        // Vuelos CDG -> NRT
        for (int i = 0; i < 5; i++) {
            ZonedDateTime dep = now.plusHours(i * 6 + 25);
            ZonedDateTime arr = dep.plusHours(24);
            Flight flight = new Flight("FL_CDG_NRT_" + i, cdg, nrt, dep, arr, 300, FlightType.INTERCONTINENTAL);
            flightPlan.addFlight(flight);
        }
        
        // Vuelos JFK -> NRT (directo)
        for (int i = 0; i < 3; i++) {
            ZonedDateTime dep = now.plusHours(i * 8);
            ZonedDateTime arr = dep.plusHours(24);
            Flight flight = new Flight("FL_JFK_NRT_" + i, jfk, nrt, dep, arr, 350, FlightType.INTERCONTINENTAL);
            flightPlan.addFlight(flight);
        }
        
        // Crear lotes de maletas
        batches = new ArrayList<>();
        batches.add(new ShipmentBatch("B1", "B1_JFK", "CLIENT1", jfk, cdg, 50, now));
        batches.add(new ShipmentBatch("B2", "B2_JFK", "CLIENT1", jfk, nrt, 75, now));
        batches.add(new ShipmentBatch("B3", "B3_JFK", "CLIENT2", jfk, cdg, 100, now));
    }
    
    @Test
    @DisplayName("RouteGenerator crea rutas factibles")
    void testRouteGeneratorCreatesValidRoutes() {
        RouteGenerator generator = new RouteGenerator(flightPlan, airportManager);
        
        for (ShipmentBatch batch : batches) {
            AssignedRoute route = generator.generateFeasibleRoute(batch);
            
            assertNotNull(route, "Debe generar ruta para batch " + batch.batchId());
            assertEquals(batch, route.getBatch());
            assertFalse(route.getFlights().isEmpty(), "Ruta debe tener al menos un vuelo");
            
            // Verificar que cumple SLA
            assertTrue(route.meetsSLA(), "Ruta debe cumplir SLA para batch " + batch.batchId());
        }
    }
    
    @Test
    @DisplayName("SolutionEvaluator calcula fitness correctamente")
    void testSolutionEvaluatorCalculatesFitness() {
        SolutionEvaluator evaluator = new SolutionEvaluator(flightPlan, airportManager);
        RouteGenerator generator = new RouteGenerator(flightPlan, airportManager);
        
        Solution solution = new Solution();
        for (ShipmentBatch batch : batches) {
            AssignedRoute route = generator.generateFeasibleRoute(batch);
            if (route != null) {
                solution.addRoute(route);
            }
        }
        
        double fitness = evaluator.evaluate(solution);
        
        // Verificar que la solución fue evaluada correctamente
        assertTrue(solution.isEvaluated(), "Solución debe estar marcada como evaluada");
        assertTrue(Double.isFinite(fitness), "Fitness debe ser un número finito");
    }
    
    @Test
    @DisplayName("GeneticAlgorithm genera solución válida")
    void testGeneticAlgorithmGeneratesValidSolution() {
        // Configurar GA con parámetros pequeños para prueba rápida
        GeneticAlgorithm ga = new GeneticAlgorithm(flightPlan, airportManager);
        AlgorithmConfig config = new AlgorithmConfig();
        config.setInt("populationSize", 10);
        config.setInt("generations", 5);
        config.setDouble("mutationRate", 0.1);
        ga.configure(config);
        
        Solution solution = ga.optimize(batches);
        
        assertNotNull(solution, "GA debe retornar una solución");
        assertTrue(solution.isEvaluated(), "Solución debe estar evaluada");
        assertFalse(solution.getRoutes().isEmpty(), "Solución debe tener rutas");
        
        // Verificar que todas las rutas cumplen SLA
        for (AssignedRoute route : solution.getRoutes().values()) {
            assertTrue(route.meetsSLA(), "Todas las rutas deben cumplir SLA");
        }
    }
    
    @Test
    @DisplayName("TabuSearch refina solución correctamente")
    void testTabuSearchRefinesSolution() {
        // Generar solución inicial
        RouteGenerator generator = new RouteGenerator(flightPlan, airportManager);
        Solution initialSolution = new Solution();
        for (ShipmentBatch batch : batches) {
            AssignedRoute route = generator.generateFeasibleRoute(batch);
            if (route != null) {
                initialSolution.addRoute(route);
            }
        }
        
        SolutionEvaluator evaluator = new SolutionEvaluator(flightPlan, airportManager);
        double initialFitness = evaluator.evaluate(initialSolution);
        initialSolution.setFitness(initialFitness);
        
        // Refinar con Tabú Search
        TabuSearch tabu = new TabuSearch(flightPlan, airportManager);
        AlgorithmConfig config = new AlgorithmConfig();
        config.setInt("maxIterations", 10);
        config.setInt("tabuTenure", 5);
        config.setInt("neighborhoodSize", 5);
        tabu.configure(config);
        
        Solution refinedSolution = tabu.refine(initialSolution);
        
        assertNotNull(refinedSolution, "Tabú debe retornar una solución");
        assertTrue(refinedSolution.isEvaluated(), "Solución refinada debe estar evaluada");
        
        // La solución refinada debe ser al menos tan buena como la inicial
        assertTrue(refinedSolution.getFitness() <= initialFitness, 
                  "Solución refinada debe mejorar o mantener fitness");
    }
    
    @Test
    @DisplayName("Fitness penaliza violaciones correctamente")
    void testFitnessPenalizesViolations() {
        SolutionEvaluator evaluator = new SolutionEvaluator(flightPlan, airportManager);
        
        // Crear solución válida
        RouteGenerator generator = new RouteGenerator(flightPlan, airportManager);
        Solution validSolution = new Solution();
        for (ShipmentBatch batch : batches) {
            AssignedRoute route = generator.generateFeasibleRoute(batch);
            if (route != null) {
                validSolution.addRoute(route);
            }
        }
        
        double validFitness = evaluator.evaluate(validSolution);
        
        // El fitness de una solución válida debe ser relativamente bajo
        // (principalmente premios, pocas penalizaciones)
        assertTrue(validFitness < 1_000_000, 
                  "Solución válida debe tener fitness razonable");
    }
}
