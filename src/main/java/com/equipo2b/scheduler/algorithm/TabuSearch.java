package com.equipo2b.scheduler.algorithm;

import com.equipo2b.scheduler.logic.*;
import com.equipo2b.scheduler.model.*;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

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
    // ThreadLocalRandom se usa en cada método para thread-safety
    
    // Parámetros configurables
    private int maxIterations = 200;
    private int tabuTenure = 15;
    private int neighborhoodSize = 20;
    private int routeSearchAttempts = 8;
    private int routeCachedVariants = 4;
    private long maxTimeMillis = 0;  // 0 = sin límite; >0 = deadline duro (parte del presupuesto Ta)
    private volatile Map<Airport, Integer> storageBaseline = Map.of();

    // Sin topes por cantidad de rutas: el DEADLINE gobierna cuánto se explora (diseño
    // anytime). Con 400 rutas cada iteración es barata y caben cientos; con 3.000 caben
    // menos, pero las que caben se ejecutan igual — no hay acantilados de comportamiento.
    // Las copias de Solution son superficiales (comparten AssignedRoute) y los caches de
    // proyecciones/rutas están acotados, así que el costo por iteración es predecible.
    
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
        // Configurar evaluador con cantidad esperada de lotes y de MALETAS (Tarea I) —
        // mismo criterio que GeneticAlgorithm.optimize(): total de maletas de la lista
        // ORIGINAL de este ciclo (modo TABU_PURE construye la solución inicial desde cero,
        // sin splits previos, así que aquí no aplica la distinción original/efectiva de GA).
        evaluator.setExpectedBatchCount(batches.size());
        evaluator.setExpectedBagCount(batches.stream().mapToInt(ShipmentBatch::quantity).sum());

        // Generar solución inicial con rutas factibles
        Solution currentSolution = generateInitialSolution(batches);
        currentSolution.setFitness(evaluator.evaluate(currentSolution));
        
        Solution bestSolution = new Solution(currentSolution);

        // Lista tabú: contiene batch IDs de rutas modificadas recientemente
        Queue<String> tabuList = new LinkedList<>();
        Set<String> tabuSet = new HashSet<>();
        final long deadline = maxTimeMillis > 0 ? System.currentTimeMillis() + maxTimeMillis : Long.MAX_VALUE;

        // Iterar hasta maxIterations o hasta agotar el presupuesto de tiempo
        for (int iter = 0; iter < maxIterations; iter++) {
            if (System.currentTimeMillis() >= deadline) {
                System.out.printf("⏱️ Tabú detenido por presupuesto de tiempo en iteración %d%n", iter);
                break;
            }
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
        return refine(initialSolution, maxTimeMillis);
    }

    /**
     * Refinamiento con presupuesto de tiempo EXPLÍCITO (diseño anytime).
     *
     * <p>El Scheduler pasa aquí todo el Ta que la fase primaria no consumió: si la
     * semilla tomó 2s de un Ta de 30s, el Tabú explora ~27s en lugar de un porcentaje
     * fijo. Cuantas más iteraciones, más movimientos de descongestión de vuelos y
     * almacenes se prueban — y una ruta con escalas reemplaza a la directa siempre que
     * el fitness (que incluye el desbalance global de almacenes) mejore.</p>
     *
     * @param initialSolution Solución a refinar
     * @param budgetMillis Presupuesto duro en ms (0 = sin límite de tiempo)
     * @return Mejor solución encontrada dentro del presupuesto
     */
    public Solution refine(Solution initialSolution, long budgetMillis) {
        // Tarea H — coherencia con el GA: fija el término de no-asignados (lotes y MALETAS
        // esperados) a partir de LA MISMA solución que se va a refinar, no de un valor externo.
        // Los movimientos de este refinamiento (REGENERATE, CONGESTION_RELIEF,
        // STORAGE_CONGESTION_RELIEF, MULTI_REGENERATE) siempre regeneran la ruta de un lote
        // que YA está en la solución — ninguno elimina una ruta sin reemplazarla — así que
        // batches = rutas de initialSolution es un conteo estable durante todo refine(): el
        // término de no-asignados queda CONSTANTE y la escala del fitness es coherente con la
        // que usó el GA para construir initialSolution.
        evaluator.setExpectedBatchCount(initialSolution.getRoutes().size());
        evaluator.setExpectedBagCount(initialSolution.getTotalBags());

        Solution currentSolution = new Solution(initialSolution);
        if (!currentSolution.isEvaluated()) {
            currentSolution.setFitness(evaluator.evaluate(currentSolution));
        }

        Solution bestSolution = new Solution(currentSolution);

        Queue<String> tabuList = new LinkedList<>();
        Set<String> tabuSet = new HashSet<>();
        final long deadline = budgetMillis > 0 ? System.currentTimeMillis() + budgetMillis : Long.MAX_VALUE;
        int iterationsExecuted = 0;

        for (int iter = 0; iter < maxIterations; iter++) {
            if (System.currentTimeMillis() >= deadline) {
                break;
            }
            iterationsExecuted = iter + 1;
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

        System.out.printf(
            "🔎 Tabú: %d iteraciones (%d rutas), fitness %.2f → %.2f%n",
            iterationsExecuted, bestSolution.getRoutes().size(),
            initialSolution.getFitness(), bestSolution.getFitness()
        );
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
            CapacityContext capacity = CapacityContext.fromSolution(
                updatedSolution.getRoutes().values(), storageBaseline);
            AssignedRoute newRoute = routeGenerator.generateFeasibleRoute(
                batch,
                alternatives,
                capacity
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
        
        CapacityContext capacity = CapacityContext.fromSolution(
            updatedSolution.getRoutes().values(), storageBaseline);
        for (ShipmentBatch batch : newBatches) {
            AssignedRoute newRoute = routeGenerator.generateFeasibleRoute(batch, capacity);
            if (newRoute != null) {
                updatedSolution.addRoute(newRoute);
                capacity.applyRoute(newRoute);
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
        CapacityContext capacity = CapacityContext.fromBaseline(storageBaseline);
        long deadline = maxTimeMillis > 0 ? System.currentTimeMillis() + maxTimeMillis : Long.MAX_VALUE;
        int routed = 0;
        for (ShipmentBatch batch : batches) {
            if (System.currentTimeMillis() >= deadline) {
                System.out.printf(
                    "⏱ Tabú semilla truncada por Ta tras %d/%d lotes%n",
                    routed, batches.size());
                break;
            }
            AssignedRoute route = routeGenerator.generateFeasibleRoute(batch, capacity);
            if (route != null) {
                solution.addRoute(route);
                capacity.applyRoute(route);
                routed++;
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
    /**
     * Genera un movimiento eligiendo aleatoriamente entre tipos diversificados.
     * REGENERATE (45%): Ruta aleatoria regenerada.
     * FLIGHT_CONGESTION (20%): Regenera batch del vuelo más congestionado.
     * STORAGE_CONGESTION (20%): Regenera batches que saturan almacenes (multi-hop preferido).
     * MULTI_REGENERATE (15%): Regenera 2-3 batches simultáneamente.
     */
    private Move generateMove(Solution current, List<ShipmentBatch> batches) {
        int moveType = ThreadLocalRandom.current().nextInt(100);
        if (moveType < 45) {
            return generateRegenerateMove(current, batches);
        } else if (moveType < 65) {
            return generateCongestionReliefMove(current);
        } else if (moveType < 85) {
            return generateStorageCongestionReliefMove(current);
        } else {
            return generateMultiRegenerateMove(current, batches);
        }
    }
    
    /**
     * Genera un movimiento desde solución existente con tipos diversificados.
     */
    private Move generateMoveFromSolution(Solution current) {
        int moveType = ThreadLocalRandom.current().nextInt(100);
        if (moveType < 45) {
            return generateRegenerateMoveFromSolution(current);
        } else if (moveType < 65) {
            return generateCongestionReliefMove(current);
        } else if (moveType < 85) {
            return generateStorageCongestionReliefMove(current);
        } else {
            return generateMultiRegenerateMoveFromSolution(current);
        }
    }
    
    // ==================== Tipos de Movimiento ====================
    
    /** REGENERATE: Elige lote aleatorio de la lista y regenera su ruta. */
    private Move generateRegenerateMove(Solution current, List<ShipmentBatch> batches) {
        Solution neighbor = new Solution(current);
        ShipmentBatch batch = batches.get(ThreadLocalRandom.current().nextInt(batches.size()));
        CapacityContext capacity = capacityWithoutBatch(current, batch.batchId());
        boolean preferMultiHop = ThreadLocalRandom.current().nextBoolean();
        AssignedRoute newRoute = preferMultiHop
            ? routeGenerator.generateFeasibleRoutePreferMultiHop(batch, capacity)
            : routeGenerator.generateFeasibleRoute(batch, capacity);
        if (newRoute != null) {
            neighbor.addRoute(newRoute);
        }
        return new Move(neighbor, batch.batchId());
    }
    
    /** REGENERATE desde solución: Elige ruta existente y la regenera. */
    private Move generateRegenerateMoveFromSolution(Solution current) {
        Solution neighbor = new Solution(current);
        List<String> batchIds = new ArrayList<>(current.getRoutes().keySet());
        if (batchIds.isEmpty()) {
            return new Move(neighbor, "");
        }
        String batchId = batchIds.get(ThreadLocalRandom.current().nextInt(batchIds.size()));
        ShipmentBatch batch = current.getRoute(batchId).getBatch();
        CapacityContext capacity = capacityWithoutBatch(current, batchId);
        boolean preferMultiHop = ThreadLocalRandom.current().nextBoolean();
        AssignedRoute newRoute = preferMultiHop
            ? routeGenerator.generateFeasibleRoutePreferMultiHop(batch, capacity)
            : routeGenerator.generateFeasibleRoute(batch, capacity);
        if (newRoute != null) {
            neighbor.addRoute(newRoute);
        }
        return new Move(neighbor, batchId);
    }
    
    /**
     * CONGESTION_RELIEF (vuelos): Encuentra el vuelo más CARGADO (Tarea G), elige un batch
     * de ese vuelo y lo regenera para aliviar congestión.
     *
     * <p>ANTES: "más utilizado" se medía por NÚMERO de lotes en el vuelo
     * ({@code flightToBatchIds.size()}) — un vuelo con 2 lotes de 200 maletas cada uno
     * quedaba invisible frente a uno con 5 lotes de 5 maletas, aunque el primero esté mucho
     * más cerca de su capacidad real. AHORA: se acumulan las MALETAS reales por vuelo y se
     * elige el de mayor ratio maletas/capacidad — la métrica que de verdad importa para
     * descongestionar (análoga a la que ya usa {@link #generateStorageCongestionReliefMove}
     * para almacenes).</p>
     */
    private Move generateCongestionReliefMove(Solution current) {
        List<String> batchIds = new ArrayList<>(current.getRoutes().keySet());
        if (batchIds.isEmpty()) {
            return new Move(new Solution(current), "");
        }

        Map<String, Integer> flightBagLoad = new HashMap<>();
        Map<String, Flight> flightById = new HashMap<>();
        Map<String, List<String>> flightToBatchIds = new HashMap<>();
        for (Map.Entry<String, AssignedRoute> entry : current.getRoutes().entrySet()) {
            int qty = entry.getValue().getBatch().quantity();
            for (Flight flight : entry.getValue().getFlights()) {
                flightBagLoad.merge(flight.flightId(), qty, Integer::sum);
                flightById.putIfAbsent(flight.flightId(), flight);
                flightToBatchIds.computeIfAbsent(flight.flightId(), k -> new ArrayList<>())
                    .add(entry.getKey());
            }
        }

        String mostLoadedFlightId = null;
        double worstRatio = -1.0;
        for (Map.Entry<String, Integer> entry : flightBagLoad.entrySet()) {
            Flight flight = flightById.get(entry.getKey());
            if (flight.capacity() <= 0) {
                continue;
            }
            double ratio = (double) entry.getValue() / flight.capacity();
            if (ratio > worstRatio) {
                worstRatio = ratio;
                mostLoadedFlightId = entry.getKey();
            }
        }

        if (mostLoadedFlightId == null) {
            return generateRegenerateMoveFromSolution(current);
        }

        List<String> candidates = flightToBatchIds.get(mostLoadedFlightId);
        String targetBatchId = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));

        Solution neighbor = new Solution(current);
        ShipmentBatch batch = current.getRoute(targetBatchId).getBatch();
        CapacityContext capacity = capacityWithoutBatch(current, targetBatchId);
        AssignedRoute newRoute = routeGenerator.generateFeasibleRouteNoCache(batch, capacity, true);
        if (newRoute != null) {
            neighbor.addRoute(newRoute);
        }
        return new Move(neighbor, targetBatchId);
    }

    /**
     * STORAGE_CONGESTION_RELIEF: identifica el aeropuerto con mayor ocupación estimada
     * (baseline + rutas), elige un lote que lo usa como hub intermedio (o origen) y
     * regenera preferiendo multi-hop para desalojar carga hacia hubs alternativos.
     *
     * <p>Sanity-check de Tarea G: este método YA acumula {@code route.getBatch().quantity()}
     * (maletas reales, no número de lotes) por aeropuerto — ver {@code occupancy.merge(hub,
     * qty, ...)} abajo — así que no tiene el mismo defecto que tenía
     * {@link #generateCongestionReliefMove}. Se deja sin cambios.</p>
     */
    private Move generateStorageCongestionReliefMove(Solution current) {
        List<String> batchIds = new ArrayList<>(current.getRoutes().keySet());
        if (batchIds.isEmpty()) {
            return new Move(new Solution(current), "");
        }

        Map<Airport, Integer> occupancy = new HashMap<>(storageBaseline);
        Map<Airport, List<String>> airportToBatches = new HashMap<>();
        for (Map.Entry<String, AssignedRoute> entry : current.getRoutes().entrySet()) {
            AssignedRoute route = entry.getValue();
            int qty = route.getBatch().quantity();
            Set<Airport> hubs = new LinkedHashSet<>();
            hubs.add(route.getBatch().origin());
            List<Flight> flights = route.getFlights();
            for (int i = 0; i < flights.size(); i++) {
                hubs.add(flights.get(i).destination());
            }
            for (Airport hub : hubs) {
                occupancy.merge(hub, qty, Integer::sum);
                // Preferir regenerar lotes que usan el hub como ESCALA (no solo O/D)
                boolean intermediate = false;
                for (int i = 0; i < flights.size() - 1; i++) {
                    if (flights.get(i).destination().equals(hub)) {
                        intermediate = true;
                        break;
                    }
                }
                if (intermediate || hub.equals(route.getBatch().origin())) {
                    airportToBatches.computeIfAbsent(hub, k -> new ArrayList<>()).add(entry.getKey());
                }
            }
        }

        Airport mostLoaded = null;
        double worstRatio = 0.0;
        for (Map.Entry<Airport, Integer> e : occupancy.entrySet()) {
            int cap = e.getKey().storageCapacity();
            if (cap <= 0) continue;
            double ratio = (double) e.getValue() / cap;
            if (ratio > worstRatio) {
                worstRatio = ratio;
                mostLoaded = e.getKey();
            }
        }

        if (mostLoaded == null) {
            return generateRegenerateMoveFromSolution(current);
        }

        final Airport congestedHub = mostLoaded;
        List<String> candidates = airportToBatches.getOrDefault(congestedHub, List.of());
        if (candidates.isEmpty()) {
            List<String> touching = new ArrayList<>();
            for (Map.Entry<String, AssignedRoute> entry : current.getRoutes().entrySet()) {
                AssignedRoute route = entry.getValue();
                if (route.getBatch().origin().equals(congestedHub)
                        || route.getBatch().destination().equals(congestedHub)
                        || route.getFlights().stream().anyMatch(f -> f.destination().equals(congestedHub))) {
                    touching.add(entry.getKey());
                }
            }
            candidates = touching;
        }
        if (candidates.isEmpty()) {
            return generateRegenerateMoveFromSolution(current);
        }

        // Regenerar 1–2 lotes del hub congestionado hacia alternativas multi-hop
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<String> shuffled = new ArrayList<>(candidates);
        Collections.shuffle(shuffled, random);
        int regenCount = Math.min(shuffled.size(), random.nextInt(1, 3));
        Solution neighbor = new Solution(current);
        String firstBatchId = null;
        CapacityContext capacity = CapacityContext.fromSolution(
            neighbor.getRoutes().values(), storageBaseline);

        List<String> toRegen = new ArrayList<>(shuffled.subList(0, regenCount));
        for (int i = 0; i < toRegen.size(); i++) {
            String targetBatchId = toRegen.get(i);
            if (firstBatchId == null) firstBatchId = targetBatchId;
            AssignedRoute existing = neighbor.getRoute(targetBatchId);
            if (existing == null) continue;
            capacity.removeRoute(existing);
            ShipmentBatch batch = existing.getBatch();
            AssignedRoute newRoute = routeGenerator.generateFeasibleRouteNoCache(batch, capacity, true);
            if (newRoute != null) {
                neighbor.addRoute(newRoute);
                capacity.applyRoute(newRoute);
            } else {
                capacity.applyRoute(existing);
            }
        }
        return new Move(neighbor, firstBatchId != null ? firstBatchId : "");
    }

    private CapacityContext capacityWithoutBatch(Solution current, String batchId) {
        CapacityContext capacity = CapacityContext.fromSolution(
            current.getRoutes().values(), storageBaseline);
        AssignedRoute existing = current.getRoute(batchId);
        if (existing != null) {
            capacity.removeRoute(existing);
        }
        return capacity;
    }
    
    /** MULTI_REGENERATE: Regenera 2-3 lotes simultáneamente (salto grande en vecindario). */
    private Move generateMultiRegenerateMove(Solution current, List<ShipmentBatch> batches) {
        if (batches.size() < 2) {
            return batches.isEmpty() ? new Move(new Solution(current), "") : generateRegenerateMove(current, batches);
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Solution neighbor = new Solution(current);
        CapacityContext capacity = CapacityContext.fromSolution(
            neighbor.getRoutes().values(), storageBaseline);
        int count = random.nextInt(2, Math.min(4, batches.size() + 1));
        String firstBatchId = null;
        
        for (int i = 0; i < count; i++) {
            ShipmentBatch batch = batches.get(random.nextInt(batches.size()));
            if (firstBatchId == null) firstBatchId = batch.batchId();
            AssignedRoute existing = neighbor.getRoute(batch.batchId());
            if (existing != null) {
                capacity.removeRoute(existing);
            }
            boolean preferMultiHop = random.nextBoolean();
            AssignedRoute newRoute = preferMultiHop
                ? routeGenerator.generateFeasibleRoutePreferMultiHop(batch, capacity)
                : routeGenerator.generateFeasibleRoute(batch, capacity);
            if (newRoute != null) {
                neighbor.addRoute(newRoute);
                capacity.applyRoute(newRoute);
            } else if (existing != null) {
                capacity.applyRoute(existing);
            }
        }
        return new Move(neighbor, firstBatchId != null ? firstBatchId : "");
    }
    
    /** MULTI_REGENERATE desde solución existente. */
    private Move generateMultiRegenerateMoveFromSolution(Solution current) {
        List<String> batchIds = new ArrayList<>(current.getRoutes().keySet());
        if (batchIds.size() < 2) {
            return batchIds.isEmpty() ? new Move(new Solution(current), "") : generateRegenerateMoveFromSolution(current);
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        Solution neighbor = new Solution(current);
        CapacityContext capacity = CapacityContext.fromSolution(
            neighbor.getRoutes().values(), storageBaseline);
        int count = random.nextInt(2, Math.min(4, batchIds.size() + 1));
        String firstBatchId = null;
        
        for (int i = 0; i < count; i++) {
            String batchId = batchIds.get(random.nextInt(batchIds.size()));
            if (firstBatchId == null) firstBatchId = batchId;
            AssignedRoute existing = neighbor.getRoute(batchId);
            if (existing == null) continue;
            capacity.removeRoute(existing);
            ShipmentBatch batch = existing.getBatch();
            boolean preferMultiHop = random.nextBoolean();
            AssignedRoute newRoute = preferMultiHop
                ? routeGenerator.generateFeasibleRoutePreferMultiHop(batch, capacity)
                : routeGenerator.generateFeasibleRoute(batch, capacity);
            if (newRoute != null) {
                neighbor.addRoute(newRoute);
                capacity.applyRoute(newRoute);
            } else {
                capacity.applyRoute(existing);
            }
        }
        return new Move(neighbor, firstBatchId != null ? firstBatchId : "");
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
     * Propaga la ocupación de almacén preexistente (rutas de ciclos previos) al evaluador
     * y a la construcción capacity-aware / alivio de congestión de almacén.
     */
    public void setStorageBaseline(Map<Airport, Integer> baseline) {
        this.storageBaseline = baseline != null ? baseline : Map.of();
        this.evaluator.setStorageBaseline(this.storageBaseline);
    }

    /**
     * Prueba BARATA (una sola búsqueda BFS, sin capacidad ni cache) de si existe AL MENOS
     * un camino de vuelos que cumpla el SLA del lote, ignorando por completo la capacidad.
     *
     * <p>Distingue las dos causas de "sin ruta" que se ven idénticas desde afuera:</p>
     * <ul>
     *   <li><b>Congestión momentánea</b> (capacidad ocupada por otras rutas de este ciclo):
     *       {@code true} — SÍ existe camino, solo faltó espacio; reintentar en el próximo
     *       ciclo tiene sentido porque la ocupación cambia.</li>
     *   <li><b>Infactibilidad estructural</b> (el SLA no alcanza para NINGUNA combinación de
     *       vuelos entre ese origen/destino, sin importar la capacidad): {@code false} — la
     *       geometría del plan de vuelos (frecuencia + duración) hace el SLA imposible de
     *       cumplir. Reintentar es pura pérdida de presupuesto: el resultado NUNCA cambia
     *       de un ciclo a otro porque el horario de vuelos no cambia.</li>
     * </ul>
     *
     * @param batch Lote sin ruta a diagnosticar
     * @return true si existe algún camino factible por horario/SLA (con o sin capacidad)
     */
    public boolean hasFeasiblePathIgnoringCapacity(ShipmentBatch batch) {
        return routeGenerator.generateEarliestFeasibleRoute(batch, null) != null;
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
        this.maxIterations = config.getInt("maxIterations", 200);
        this.tabuTenure = config.getInt("tabuTenure", 15);
        this.neighborhoodSize = config.getInt("neighborhoodSize", 20);
        this.routeSearchAttempts = config.getInt("routeSearchAttempts", 8);
        this.routeCachedVariants = config.getInt("routeCachedVariants", 4);
        this.maxTimeMillis = config.getInt("maxTimeMillis", 0);
        this.routeGenerator.configureSearchEffort(routeSearchAttempts, routeCachedVariants);
    }
    
    /**
     * Record interno para representar un movimiento en el espacio de búsqueda.
     */
    private record Move(Solution solution, String batchId) {}
}