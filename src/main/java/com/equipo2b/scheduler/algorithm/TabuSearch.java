package com.equipo2b.scheduler.algorithm;

import com.equipo2b.scheduler.logic.*;
import com.equipo2b.scheduler.model.*;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Implementación de Búsqueda Tabú para refinamiento local y replanificación.
 * 
 * Uso dual:
 * 1. Refinamiento: mejorar las mejores soluciones del GA
 * 2. Replanificación: buscar alternativas ante cancelaciones
 * 
 * Características:
 * - Exploración de vecindario (variaciones de rutas)
 * - Lista tabú con tenure configurable
 * - Criterio de aspiración (aceptar movimientos tabú si mejoran récord global)
 * 
 * **Validates: Requirements 11.1, 15.4, 15.5, 15.6, 18.3**
 */
public class TabuSearch implements OptimizationAlgorithm {
    private final RouteGenerator routeGenerator;
    private final SolutionEvaluator evaluator;
    private final FlightPlan flightPlan;
    private final AirportManager airportManager;
    private final Random random;
    
    // Parámetros configurables
    private int maxIterations = 200;
    private int tabuTenure = 15;
    private int neighborhoodSize = 20;
    
    /**
     * Constructor que inicializa Búsqueda Tabú con dependencias.
     * 
     * @param flightPlan Plan maestro de vuelos
     * @param airportManager Gestor de aeropuertos
     */
    public TabuSearch(FlightPlan flightPlan, AirportManager airportManager) {
        this.flightPlan = Objects.requireNonNull(flightPlan, "FlightPlan cannot be null");
        this.airportManager = Objects.requireNonNull(airportManager, "AirportManager cannot be null");
        this.routeGenerator = new RouteGenerator(flightPlan, airportManager);
        this.evaluator = new SolutionEvaluator(flightPlan, airportManager);
        // Usar System.nanoTime() para mejor variabilidad entre corridas rápidas
        this.random = new Random(System.nanoTime());
    }
    
    /**
     * Optimiza una lista de lotes desde cero (planificación inicial).
     * Genera solución inicial con rutas factibles y aplica búsqueda tabú.
     * 
     * @param batches Lista de lotes de maletas a planificar
     * @return Mejor solución encontrada
     * 
     * **Validates: Requirements 11.1, 11.2, 11.3, 11.4, 11.5, 11.6**
     */
    @Override
    public Solution optimize(List<ShipmentBatch> batches) {
        // Generar solución inicial con rutas factibles
        Solution currentSolution = generateInitialSolution(batches);
        currentSolution.setFitness(evaluator.evaluate(currentSolution));
        
        Solution bestSolution = new Solution(currentSolution);
        
        // Lista tabú: contiene batch IDs de rutas modificadas recientemente
        Queue<String> tabuList = new LinkedList<>();
        Set<String> tabuSet = new HashSet<>();
        
        // Iterar durante maxIterations iteraciones
        for (int iter = 0; iter < maxIterations; iter++) {
            Solution bestNeighbor = null;
            String bestMoveBatchId = null;
            
            // Explorar vecindario
            for (int n = 0; n < neighborhoodSize; n++) {
                Move move = generateMove(currentSolution, batches);
                Solution neighbor = move.solution();
                neighbor.setFitness(evaluator.evaluate(neighbor));
                
                boolean isTabu = tabuSet.contains(move.batchId());
                boolean satisfiesAspiration = neighbor.getFitness() < bestSolution.getFitness();
                
                // Aceptar si: no es tabú O (es tabú pero cumple aspiración)
                if (!isTabu || satisfiesAspiration) {
                    if (bestNeighbor == null || neighbor.getFitness() < bestNeighbor.getFitness()) {
                        bestNeighbor = neighbor;
                        bestMoveBatchId = move.batchId();
                    }
                }
            }
            
            // Moverse al mejor vecino
            if (bestNeighbor != null) {
                currentSolution = bestNeighbor;
                
                // Actualizar mejor solución global cuando se encuentre mejora
                if (currentSolution.getFitness() < bestSolution.getFitness()) {
                    bestSolution = new Solution(currentSolution);
                    // Imprimir progreso cuando se encuentra nueva mejor solución
                    System.out.printf("Iteration %d - New Best: %.2f%n", 
                                    iter, bestSolution.getFitness());
                }
                
                // Actualizar lista tabú
                updateTabuList(tabuList, tabuSet, bestMoveBatchId);
            }
        }
        
        return bestSolution;
    }
    
    /**
     * Refina una solución existente (uso típico después del GA).
     * Aplica búsqueda tabú para mejorar localmente.
     * 
     * @param initialSolution Solución inicial del Algoritmo Genético
     * @return Mejor solución encontrada después del refinamiento
     * 
     * **Validates: Requirements 11.1, 11.2, 11.3, 11.4, 11.5, 11.6**
     */
    public Solution refine(Solution initialSolution) {
        Solution currentSolution = new Solution(initialSolution);
        if (!currentSolution.isEvaluated()) {
            currentSolution.setFitness(evaluator.evaluate(currentSolution));
        }
        
        Solution bestSolution = new Solution(currentSolution);
        
        Queue<String> tabuList = new LinkedList<>();
        Set<String> tabuSet = new HashSet<>();
        
        for (int iter = 0; iter < maxIterations; iter++) {
            Solution bestNeighbor = null;
            String bestMoveBatchId = null;
            
            // Explorar vecindario generando variaciones de rutas individuales
            for (int n = 0; n < neighborhoodSize; n++) {
                Move move = generateMoveFromSolution(currentSolution);
                Solution neighbor = move.solution();
                neighbor.setFitness(evaluator.evaluate(neighbor));
                
                boolean isTabu = tabuSet.contains(move.batchId());
                boolean satisfiesAspiration = neighbor.getFitness() < bestSolution.getFitness();
                
                if (!isTabu || satisfiesAspiration) {
                    if (bestNeighbor == null || neighbor.getFitness() < bestNeighbor.getFitness()) {
                        bestNeighbor = neighbor;
                        bestMoveBatchId = move.batchId();
                    }
                }
            }
            
            if (bestNeighbor != null) {
                currentSolution = bestNeighbor;
                
                if (currentSolution.getFitness() < bestSolution.getFitness()) {
                    bestSolution = new Solution(currentSolution);
                }
                
                updateTabuList(tabuList, tabuSet, bestMoveBatchId);
            }
        }
        
        // Retornar mejor solución encontrada
        return bestSolution;
    }
    
    /**
     * Replanifica lotes afectados por cancelación de vuelo.
     * Busca vuelos alternativos en ventana +2h desde mismo aeropuerto.
     * 
     * @param currentSolution Solución actual
     * @param cancelledFlight Vuelo cancelado
     * @param affectedBatches Lotes afectados por la cancelación
     * @return Solución actualizada con nuevas rutas
     * 
     * **Validates: Requirements 12.1, 12.2, 12.3, 12.4, 12.5, 12.6, 25.1, 25.2, 25.3, 25.4**
     */
    public Solution replan(Solution currentSolution, Flight cancelledFlight, 
                          List<ShipmentBatch> affectedBatches) {
        Solution updatedSolution = new Solution(currentSolution);
        
        System.out.println("\n=== REPLANIFICACIÓN TABÚ ===");
        System.out.println("Rutas antes de eliminar afectadas: " + updatedSolution.getRoutes().size());
        
        // 1. ELIMINAR rutas afectadas de la solución
        for (ShipmentBatch batch : affectedBatches) {
            updatedSolution.removeRoute(batch.batchId());
        }
        System.out.println("Rutas después de eliminar afectadas: " + updatedSolution.getRoutes().size());
        
        // 2. Buscar vuelos alternativos en ventana +2h desde mismo aeropuerto
        ZonedDateTime windowStart = cancelledFlight.departureTime();
        ZonedDateTime windowEnd = windowStart.plusHours(2);
        
        List<Flight> alternatives = flightPlan.getFlightsFromAirport(
            cancelledFlight.origin(),
            windowStart,
            windowEnd
        );
        
        System.out.println("Vuelos alternativos en ventana +2h: " + alternatives.size());
        
        // 3. Para cada lote afectado, generar nueva ruta usando solo vuelos alternativos
        int replanedCount = 0;
        int failedCount = 0;
        
        for (ShipmentBatch batch : affectedBatches) {
            AssignedRoute newRoute = routeGenerator.generateFeasibleRoute(
                batch, 
                alternatives
            );
            
            if (newRoute != null) {
                updatedSolution.addRoute(newRoute);
                replanedCount++;
            } else {
                // Registrar error si no se puede replanificar algún lote
                System.err.printf("Cannot replan batch %s - no alternatives found%n", 
                                batch.batchId());
                failedCount++;
            }
        }
        
        System.out.println("Lotes replanificados exitosamente: " + replanedCount);
        System.out.println("Lotes que no pudieron replanificarse: " + failedCount);
        System.out.println("Rutas totales después de replanificación: " + updatedSolution.getRoutes().size());
        
        // 4. Re-evaluar fitness de la solución completa
        double fitnessBefore = currentSolution.getFitness();
        double fitnessAfter = evaluator.evaluate(updatedSolution);
        System.out.println("Fitness antes: " + String.format("%.2f", fitnessBefore));
        System.out.println("Fitness después: " + String.format("%.2f", fitnessAfter));
        
        // Retornar solución actualizada
        return updatedSolution;
    }
    
    /**
     * Replanifica incorporando nuevos lotes a la solución actual.
     * Usado por SimulationRunner para replanificación día a día.
     * 
     * @param currentSolution Solución actual
     * @param newBatches Nuevos lotes a incorporar
     * @return Solución actualizada con nuevos lotes
     */
    public Solution replan(Solution currentSolution, List<ShipmentBatch> newBatches) {
        Solution updatedSolution = new Solution(currentSolution);
        
        // Generar rutas para nuevos lotes
        for (ShipmentBatch batch : newBatches) {
            AssignedRoute newRoute = routeGenerator.generateFeasibleRoute(batch);
            if (newRoute != null) {
                updatedSolution.addRoute(newRoute);
            }
        }
        
        // Aplicar búsqueda tabú limitada para optimizar
        updatedSolution.setFitness(evaluator.evaluate(updatedSolution));
        
        return updatedSolution;
    }
    
    /**
     * Genera solución inicial con rutas factibles para todos los lotes.
     * 
     * @param batches Lista de lotes a planificar
     * @return Solución inicial
     */
    private Solution generateInitialSolution(List<ShipmentBatch> batches) {
        Solution solution = new Solution();
        for (ShipmentBatch batch : batches) {
            AssignedRoute route = routeGenerator.generateFeasibleRoute(batch);
            if (route != null) {
                solution.addRoute(route);
            }
        }
        return solution;
    }
    
    /**
     * Genera un movimiento (vecino) eligiendo lote aleatorio y regenerando su ruta.
     * 
     * @param current Solución actual
     * @param batches Lista de lotes disponibles
     * @return Move con solución vecina y batch ID modificado
     * 
     * **Validates: Requirements 11.2**
     */
    private Move generateMove(Solution current, List<ShipmentBatch> batches) {
        Solution neighbor = new Solution(current);
        
        // Elegir lote aleatorio
        ShipmentBatch randomBatch = batches.get(random.nextInt(batches.size()));
        
        // Generar nueva ruta para ese lote usando RouteGenerator
        AssignedRoute newRoute = routeGenerator.generateFeasibleRoute(randomBatch);
        if (newRoute != null) {
            neighbor.addRoute(newRoute);
        }
        
        // Retornar Move(solution, batchId)
        return new Move(neighbor, randomBatch.batchId());
    }
    
    /**
     * Genera un movimiento desde una solución existente.
     * Elige una ruta aleatoria de la solución y la regenera.
     * 
     * @param current Solución actual
     * @return Move con solución vecina y batch ID modificado
     */
    private Move generateMoveFromSolution(Solution current) {
        Solution neighbor = new Solution(current);
        
        // Elegir ruta aleatoria de la solución actual
        List<String> batchIds = new ArrayList<>(current.getRoutes().keySet());
        if (batchIds.isEmpty()) {
            return new Move(neighbor, "");
        }
        
        String randomBatchId = batchIds.get(random.nextInt(batchIds.size()));
        
        AssignedRoute currentRoute = current.getRoute(randomBatchId);
        ShipmentBatch batch = currentRoute.getBatch();
        
        // Generar nueva ruta para ese lote
        AssignedRoute newRoute = routeGenerator.generateFeasibleRoute(batch);
        if (newRoute != null) {
            neighbor.addRoute(newRoute);
        }
        
        return new Move(neighbor, randomBatchId);
    }
    
    /**
     * Actualiza la lista tabú agregando un batch ID.
     * Si el tamaño excede tabuTenure, remueve el más antiguo.
     * 
     * @param list Cola FIFO de batch IDs tabú
     * @param set Set para búsqueda O(1)
     * @param batchId Batch ID a agregar
     * 
     * **Validates: Requirements 11.3**
     */
    private void updateTabuList(Queue<String> list, Set<String> set, String batchId) {
        // Agregar batchId a cola y set tabú
        list.add(batchId);
        set.add(batchId);
        
        // Si tamaño excede tabuTenure, remover el más antiguo
        if (list.size() > tabuTenure) {
            String removed = list.poll();
            set.remove(removed);
        }
    }
    
    /**
     * Configura parámetros del algoritmo desde AlgorithmConfig.
     * 
     * @param config Configuración con parámetros
     * 
     * **Validates: Requirements 15.4, 15.5, 15.6**
     */
    @Override
    public void configure(AlgorithmConfig config) {
        // Leer parámetros de AlgorithmConfig y actualizar campos
        this.maxIterations = config.getInt("maxIterations", 200);
        this.tabuTenure = config.getInt("tabuTenure", 15);
        this.neighborhoodSize = config.getInt("neighborhoodSize", 20);
        
        // Permitir configurar semilla aleatoria para experimentación
        if (config.hasParameter("randomSeed")) {
            long seed = config.getInt("randomSeed", 0);
            this.random.setSeed(seed);
        }
    }
    
    /**
     * Record interno para representar un movimiento en el espacio de búsqueda.
     */
    private record Move(Solution solution, String batchId) {}
}