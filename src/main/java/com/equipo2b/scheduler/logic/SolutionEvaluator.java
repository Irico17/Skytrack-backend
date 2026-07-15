package com.equipo2b.scheduler.logic;

import com.equipo2b.scheduler.model.FlightPlan;
import com.equipo2b.scheduler.model.AirportManager;

/**
 * Evalúa la calidad de una solución mediante función fitness.
 * 
 * <p>La función fitness guía la búsqueda de los algoritmos de optimización
 * (Algoritmo Genético y Búsqueda Tabú) hacia soluciones válidas y eficientes.
 * 
 * <h2>Fórmula de Fitness</h2>
 * <pre>
 * fitness = suma_penalizaciones - suma_premios
 * </pre>
 * 
 * <p><strong>Menor valor = mejor solución</strong>
 * 
 * <h2>Penalizaciones (Restricciones Duras)</h2>
 * <ul>
 *   <li><strong>Capacidad de vuelo excedida:</strong> 10,000 puntos por cada maleta excedente</li>
 *   <li><strong>Capacidad de almacén excedida:</strong> 15,000 puntos por cada maleta excedente en cualquier instante</li>
 *   <li><strong>Violación de SLA:</strong> 20,000 puntos por cada hora de retraso</li>
 *   <li><strong>Violación de tiempo de escala:</strong> 5,000 puntos por cada violación del mínimo de 10 minutos</li>
 * </ul>
 * 
 * <h2>Premios (Optimización)</h2>
 * <ul>
 *   <li><strong>Holgura de tiempo:</strong> 100 puntos por cada hora de holgura respecto al SLA (máximo 500 puntos por ruta)</li>
 *   <li><strong>Vuelos no utilizados:</strong> 50 puntos por cada vuelo del plan que no se utiliza</li>
 * </ul>
 * 
 * <h2>Justificación del Diseño</h2>
 * <p>Las penalizaciones son significativamente mayores que los premios para garantizar que:
 * <ol>
 *   <li>Las restricciones duras se respeten (soluciones factibles)</li>
 *   <li>Los algoritmos prioricen la validez sobre la optimización</li>
 *   <li>Las violaciones de SLA tengan el mayor impacto (20,000 puntos/hora)</li>
 *   <li>Los excesos de capacidad de almacén sean más costosos que los de vuelo (15,000 vs 10,000)</li>
 * </ol>
 * 
 * <p>Los premios incentivan:
 * <ol>
 *   <li>Rutas con holgura temporal (mayor robustez ante disrupciones)</li>
 *   <li>Uso eficiente de recursos (minimizar vuelos utilizados)</li>
 * </ol>
 * 
 * <p><strong>Validates: Requirements 9.2, 9.3, 9.4, 9.5, 9.6, 9.7</strong>
 * 
 * @see com.equipo2b.scheduler.algorithm.GeneticAlgorithm
 * @see com.equipo2b.scheduler.algorithm.TabuSearch
 */
public class SolutionEvaluator {
    
    // ==================== Constantes de Penalización ====================
    
    /**
     * Penalización por cada maleta que excede la capacidad de un vuelo.
     * 
     * <p>Valor: 10,000 puntos por maleta excedente
     * 
     * <p><strong>Validates: Requirement 9.2</strong>
     */
    public static final double PENALTY_FLIGHT_CAPACITY = 10_000.0;
    
    /**
     * Penalización por cada maleta que excede la capacidad de almacén de un aeropuerto
     * en cualquier instante de tiempo.
     * 
     * <p>Valor: 15,000 puntos por maleta excedente
     * 
     * <p>Esta penalización es mayor que la de capacidad de vuelo porque los excesos
     * de almacén pueden causar congestión operativa más severa.
     * 
     * <p><strong>Validates: Requirement 9.3</strong>
     */
    public static final double PENALTY_STORAGE_CAPACITY = 15_000.0;
    
    /**
     * Penalización por cada hora de retraso respecto al SLA (Service Level Agreement).
     * 
     * <p>Valor: 20,000 puntos por hora de retraso
     * 
     * <p>Esta es la penalización más alta porque las violaciones de SLA afectan
     * directamente los compromisos contractuales con los clientes.
     * 
     * <p><strong>Validates: Requirement 9.4</strong>
     */
    public static final double PENALTY_SLA_VIOLATION = 20_000.0;
    
    /**
     * Penalización por cada violación del tiempo mínimo de escala entre vuelos.
     * 
     * <p>Valor: 5,000 puntos por violación
     * 
     * <p>El tiempo mínimo de escala es 10 minutos. Violaciones de esta restricción
     * hacen que la ruta sea físicamente infactible.
     * 
     * <p><strong>Validates: Requirement 9.5</strong>
     */
    public static final double PENALTY_LAYOVER_VIOLATION = 5_000.0;
    
    // ==================== Constantes de Premio ====================
    
    /**
     * Premio por cada hora de holgura respecto al SLA.
     * 
     * <p>Valor: 100 puntos por hora de holgura
     * 
     * <p>La holgura temporal proporciona robustez ante disrupciones operativas
     * (retrasos, cancelaciones) y mejora la satisfacción del cliente.
     * 
     * <p><strong>Validates: Requirement 9.6</strong>
     * 
     * @see #REWARD_TIME_SLACK_MAX
     */
    public static final double REWARD_TIME_SLACK_PER_HOUR = 100.0;
    
    /**
     * Premio máximo por holgura de tiempo por ruta.
     * 
     * <p>Valor: 500 puntos máximo por ruta
     * 
     * <p>Este límite evita que el algoritmo priorice excesivamente rutas con
     * holgura muy grande en detrimento de otros objetivos de optimización.
     * 
     * <p><strong>Validates: Requirement 9.6</strong>
     * 
     * @see #REWARD_TIME_SLACK_PER_HOUR
     */
    public static final double REWARD_TIME_SLACK_MAX = 500.0;
    
    /**
     * Premio por cada vuelo del plan que no se utiliza en la solución.
     * 
     * <p>Valor: 50 puntos por vuelo no utilizado
     * 
     * <p>Este premio incentiva el uso eficiente de recursos, minimizando el número
     * total de vuelos necesarios para transportar todos los lotes de maletas.
     * 
     * <p><strong>Validates: Requirement 9.7</strong>
     */
    public static final double REWARD_UNUSED_FLIGHT = 50.0;
    
    /**
     * Penalización por cada lote de maletas que no pudo ser asignado a una ruta.
     *
     * <p>Valor: 50,000 puntos por lote no asignado
     *
     * <p>Esta penalización es la más alta para garantizar que los algoritmos
     * prioricen encontrar rutas para todos los lotes antes de optimizar.
     *
     * <p><strong>Validates: Requirement 9.8</strong>
     */
    public static final double PENALTY_UNASSIGNED_BATCH = 50_000.0;

    /**
     * Micro-recompensa CONTINUA por hora de holgura, SIN tope, como desempate de mesetas.
     *
     * <p>El premio principal de holgura (100/h) se topa en 500 pts: por encima de 5 h de
     * holgura, todos los vecinos empataban exactamente y el GA/Tabú no tenía gradiente que
     * seguir ("no improvement" instantáneo). Esta escala mínima (100× menor que el premio
     * principal, 10 000× menor que las penalizaciones duras) rompe los empates y da dirección
     * de búsqueda sin alterar ninguna decisión de negocio.</p>
     */
    public static final double REWARD_SLACK_TIEBREAK_PER_HOUR = 1.0;

    /**
     * Costo marginal CONVEXO por ocupación de vuelo: α · load²/capacidad por vuelo.
     *
     * <p>La penalización dura solo castiga el EXCESO (>100%); entre 0% y 100% el algoritmo
     * era indiferente y saturaba los vuelos "buenos" primero, adelantando el colapso por
     * congestión de almacenes intermedios. Este término suave y cuadrático hace que cargar
     * un vuelo al 90% cueste más que dos al 45% → el GA/Tabú balancea la carga entre rutas
     * alternativas de forma natural.
     *
     * <p>Escala calibrada MUY por debajo de las restricciones duras y del mismo orden que
     * los premios: dos vuelos de cap 100 con 100 maletas → todo en uno = α·100; repartido
     * 50/50 = α·50. Con α=2 la diferencia (100 pts) equivale a 1 h de holgura — orienta la
     * búsqueda sin sacrificar jamás SLA ni factibilidad (que valen 10k-50k pts).
     */
    public static final double PENALTY_LOAD_CONVEX_FACTOR = 2.0;
    
    // ==================== Dependencias ====================
    
    private final FlightPlan flightPlan;
    private final AirportManager airportManager;
    
    /**
     * Cantidad esperada de lotes a planificar.
     * Si > 0, se penalizan los lotes no asignados en evaluate().
     * Usar setExpectedBatchCount() para configurar antes de la evaluación.
     */
    private volatile int expectedBatchCount = 0;
    
    /**
     * Constructor que inicializa el evaluador con las dependencias necesarias.
     * 
     * @param flightPlan Plan de vuelos completo para calcular vuelos no utilizados
     * @param airportManager Gestor de aeropuertos para validar capacidades de almacén
     * @throws NullPointerException si algún parámetro es null
     */
    public SolutionEvaluator(FlightPlan flightPlan, AirportManager airportManager) {
        if (flightPlan == null) {
            throw new NullPointerException("FlightPlan cannot be null");
        }
        if (airportManager == null) {
            throw new NullPointerException("AirportManager cannot be null");
        }
        this.flightPlan = flightPlan;
        this.airportManager = airportManager;
    }
    
    /**
     * Establece la cantidad esperada de lotes para penalizar lotes no asignados.
     * Llamar antes de las evaluaciones en el loop de optimización.
     * 
     * @param count Cantidad total de lotes esperados (0 = no penalizar)
     */
    public void setExpectedBatchCount(int count) {
        this.expectedBatchCount = count;
    }
    
    // ==================== Métodos de Evaluación ====================
    
    /**
     * Calcula penalizaciones por exceso de capacidad de vuelos.
     * 
     * <p>Agrupa maletas por vuelo, calcula exceso respecto a capacidad,
     * y aplica penalización de 10,000 puntos por maleta excedente.
     * 
     * <p><strong>Validates: Requirement 9.2</strong>
     * 
     * @param solution La solución a evaluar
     * @return Penalización total por exceso de capacidad de vuelos
     */
    public double calculateFlightCapacityPenalties(com.equipo2b.scheduler.model.Solution solution) {
        // Agrupar maletas por vuelo
        java.util.Map<com.equipo2b.scheduler.model.Flight, Integer> bagsPerFlight = new java.util.HashMap<>();
        
        for (com.equipo2b.scheduler.model.AssignedRoute route : solution.getRoutes().values()) {
            int batchQuantity = route.getBatch().quantity();
            for (com.equipo2b.scheduler.model.Flight flight : route.getFlights()) {
                bagsPerFlight.merge(flight, batchQuantity, Integer::sum);
            }
        }
        
        // Calcular exceso (restricción dura) + costo marginal convexo de ocupación (suave).
        double penalty = 0.0;
        for (java.util.Map.Entry<com.equipo2b.scheduler.model.Flight, Integer> entry : bagsPerFlight.entrySet()) {
            com.equipo2b.scheduler.model.Flight flight = entry.getKey();
            int assignedBags = entry.getValue();
            int capacity = flight.capacity();
            int excess = assignedBags - capacity;

            if (excess > 0) {
                penalty += excess * PENALTY_FLIGHT_CAPACITY;
            }
            if (capacity > 0 && assignedBags > 0) {
                // α · load²/cap — convexo: presiona a repartir carga entre vuelos alternativos
                // (retrasa el punto de colapso). Aritmética primitiva, sin asignaciones.
                double load = assignedBags;
                penalty += PENALTY_LOAD_CONVEX_FACTOR * (load * load) / capacity;
            }
        }

        return penalty;
    }
    
    /**
     * Calcula penalizaciones por exceso de capacidad de almacenes.
     * 
     * <p>Recopila eventos de almacenamiento, simula ocupación a lo largo del tiempo,
     * y aplica penalización de 15,000 puntos por maleta excedente en cualquier instante.
     * 
     * <p><strong>Validates: Requirement 9.3</strong>
     * 
     * @param solution La solución a evaluar
     * @return Penalización total por exceso de capacidad de almacenes
     */
    public double calculateStorageCapacityPenalties(com.equipo2b.scheduler.model.Solution solution) {
        // Recopilar todos los eventos de almacenamiento
        java.util.List<com.equipo2b.scheduler.model.StorageEvent> allEvents = new java.util.ArrayList<>();
        for (com.equipo2b.scheduler.model.AssignedRoute route : solution.getRoutes().values()) {
            allEvents.addAll(route.getStorageEvents());
        }
        
        // Ordenar eventos por timestamp
        allEvents.sort(java.util.Comparator.comparing(com.equipo2b.scheduler.model.StorageEvent::timestamp));
        
        // Simular ocupación a lo largo del tiempo
        java.util.Map<com.equipo2b.scheduler.model.Airport, Integer> currentOccupancy = new java.util.HashMap<>();
        double penalty = 0.0;
        
        for (com.equipo2b.scheduler.model.StorageEvent event : allEvents) {
            com.equipo2b.scheduler.model.Airport airport = event.airport();
            int quantity = event.quantity();
            
            // Actualizar ocupación según tipo de evento
            int newOccupancy;
            if (event.type() == com.equipo2b.scheduler.model.StorageEventType.ARRIVAL) {
                newOccupancy = currentOccupancy.getOrDefault(airport, 0) + quantity;
            } else { // DEPARTURE
                newOccupancy = currentOccupancy.getOrDefault(airport, 0) - quantity;
            }
            currentOccupancy.put(airport, newOccupancy);
            
            // Calcular exceso respecto a capacidad
            int excess = newOccupancy - airport.storageCapacity();
            if (excess > 0) {
                penalty += excess * PENALTY_STORAGE_CAPACITY;
            }
        }
        
        return penalty;
    }
    
    /**
     * Calcula penalizaciones por violación de SLA.
     * 
     * <p>Calcula horas de retraso para rutas que no cumplen SLA,
     * aplicando penalización de 20,000 puntos por hora de retraso.
     * 
     * <p><strong>Validates: Requirement 9.4</strong>
     * 
     * @param solution La solución a evaluar
     * @return Penalización total por violación de SLA
     */
    public double calculateSLAPenalties(com.equipo2b.scheduler.model.Solution solution) {
        double penalty = 0.0;
        
        for (com.equipo2b.scheduler.model.AssignedRoute route : solution.getRoutes().values()) {
            if (!route.meetsSLA()) {
                // Calcular horas de retraso (valor negativo de slack)
                java.time.Duration slack = route.getSLASlack();
                long delayHours = Math.abs(slack.toHours());
                penalty += delayHours * PENALTY_SLA_VIOLATION;
            }
        }
        
        return penalty;
    }
    
    /**
     * Calcula penalizaciones por violación de tiempo mínimo de escala.
     * 
     * <p>Verifica que haya al menos 10 minutos entre llegada y siguiente salida,
     * aplicando penalización de 5,000 puntos por cada violación.
     * 
     * <p><strong>Validates: Requirement 9.5</strong>
     * 
     * @param solution La solución a evaluar
     * @return Penalización total por violación de tiempo de escala
     */
    public double calculateLayoverPenalties(com.equipo2b.scheduler.model.Solution solution) {
        double penalty = 0.0;
        
        for (com.equipo2b.scheduler.model.AssignedRoute route : solution.getRoutes().values()) {
            java.util.List<com.equipo2b.scheduler.model.Flight> flights = route.getFlights();
            
            // Verificar tiempo de escala entre vuelos consecutivos
            for (int i = 0; i < flights.size() - 1; i++) {
                com.equipo2b.scheduler.model.Flight current = flights.get(i);
                com.equipo2b.scheduler.model.Flight next = flights.get(i + 1);
                
                java.time.Duration layover = java.time.Duration.between(
                    current.arrivalTime(), 
                    next.departureTime()
                );
                
                if (layover.toMinutes() < 10) {
                    penalty += PENALTY_LAYOVER_VIOLATION;
                }
            }
        }
        
        return penalty;
    }
    
    /**
     * Calcula premios por holgura de tiempo respecto al SLA.
     * 
     * <p>Otorga 100 puntos por cada hora de holgura, con máximo de 500 puntos por ruta.
     * La holgura proporciona robustez ante disrupciones operativas.
     * 
     * <p><strong>Validates: Requirement 9.6</strong>
     * 
     * @param solution La solución a evaluar
     * @return Premio total por holgura de tiempo
     */
    public double calculateTimeSlackRewards(com.equipo2b.scheduler.model.Solution solution) {
        double reward = 0.0;
        
        for (com.equipo2b.scheduler.model.AssignedRoute route : solution.getRoutes().values()) {
            if (route.meetsSLA()) {
                // Holgura CONTINUA (minutos/60.0, no toHours() truncado): antes dos rutas con
                // 30 min de diferencia empataban en el fitness — otra fuente de mesetas.
                java.time.Duration slack = route.getSLASlack();
                double slackHours = slack.toMinutes() / 60.0;

                // Premio principal con tope (comportamiento de negocio intacto)
                double routeReward = Math.min(slackHours * REWARD_TIME_SLACK_PER_HOUR, REWARD_TIME_SLACK_MAX);
                // Desempate continuo sin tope: da gradiente al GA/Tabú más allá del tope de 5h
                routeReward += slackHours * REWARD_SLACK_TIEBREAK_PER_HOUR;
                reward += routeReward;
            }
        }

        return reward;
    }
    
    /**
     * Calcula premios por vuelos no utilizados.
     * 
     * <p>Otorga 50 puntos por cada vuelo del plan que no se utiliza en la solución.
     * Incentiva el uso eficiente de recursos.
     * 
     * <p><strong>Validates: Requirement 9.7</strong>
     * 
     * @param solution La solución a evaluar
     * @return Premio total por vuelos no utilizados
     */
    public double calculateUnusedFlightRewards(com.equipo2b.scheduler.model.Solution solution) {
        int totalFlights = flightPlan.getTotalFlights();
        int usedFlights = solution.getUsedFlights().size();
        int unusedFlights = totalFlights - usedFlights;
        
        return unusedFlights * REWARD_UNUSED_FLIGHT;
    }
    
    /**
     * Evalúa la calidad de una solución mediante función fitness.
     * 
     * <p>Calcula fitness como: suma_penalizaciones - suma_premios
     * <p><strong>Menor valor = mejor solución</strong>
     * 
     * <p>Penalizaciones:
     * <ul>
     *   <li>Capacidad de vuelo excedida: 10,000 puntos/maleta</li>
     *   <li>Capacidad de almacén excedida: 15,000 puntos/maleta</li>
     *   <li>Violación de SLA: 20,000 puntos/hora</li>
     *   <li>Violación de tiempo de escala: 5,000 puntos/violación</li>
     * </ul>
     * 
     * <p>Premios:
     * <ul>
     *   <li>Holgura de tiempo: 100 puntos/hora (máx 500 por ruta)</li>
     *   <li>Vuelos no utilizados: 50 puntos/vuelo</li>
     * </ul>
     * 
     * <p><strong>Validates: Requirements 9.1, 9.8</strong>
     * 
     * @param solution La solución a evaluar
     * @return Valor de fitness (menor es mejor)
     */
    public double evaluate(com.equipo2b.scheduler.model.Solution solution) {
        // Calcular todas las penalizaciones
        double flightCapacityPenalty = calculateFlightCapacityPenalties(solution);
        double storageCapacityPenalty = calculateStorageCapacityPenalties(solution);
        double slaPenalty = calculateSLAPenalties(solution);
        double layoverPenalty = calculateLayoverPenalties(solution);
        double unassignedPenalty = calculateUnassignedBatchPenalties(solution);
        
        double totalPenalties = flightCapacityPenalty + storageCapacityPenalty + 
                               slaPenalty + layoverPenalty + unassignedPenalty;
        
        // Calcular todos los premios
        double timeSlackReward = calculateTimeSlackRewards(solution);
        double unusedFlightReward = calculateUnusedFlightRewards(solution);
        
        double totalRewards = timeSlackReward + unusedFlightReward;
        
        // Fitness = penalizaciones - premios (menor es mejor)
        double fitness = totalPenalties - totalRewards;
        
        // Actualizar el fitness en la solución
        solution.setFitness(fitness);
        
        return fitness;
    }
    
    /**
     * Calcula penalizaciones por lotes no asignados a ninguna ruta.
     * 
     * <p>Aplica 50,000 puntos por cada lote que no tiene ruta en la solución.
     * Solo se aplica si se configuró expectedBatchCount > 0.
     * 
     * <p><strong>Validates: Requirement 9.8</strong>
     * 
     * @param solution La solución a evaluar
     * @return Penalización total por lotes no asignados
     */
    public double calculateUnassignedBatchPenalties(com.equipo2b.scheduler.model.Solution solution) {
        if (expectedBatchCount <= 0) {
            return 0.0;
        }
        int assignedCount = solution.getRoutes().size();
        int unassignedCount = expectedBatchCount - assignedCount;
        if (unassignedCount > 0) {
            return unassignedCount * PENALTY_UNASSIGNED_BATCH;
        }
        return 0.0;
    }
}
