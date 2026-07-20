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
 *   <li><strong>Vuelos no utilizados:</strong> 5 puntos por vuelo no usado (desactivado bajo saturación; ver {@link #REWARD_UNUSED_FLIGHT})</li>
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
 *   <li>Uso eficiente de recursos solo cuando la red no está saturada (evitar concentración)</li>
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
     * <p>Valor: 5 puntos por vuelo no utilizado (antes 50).
     *
     * <p><strong>Por qué se redujo:</strong> un premio alto empuja a concentrar carga
     * en pocos vuelos ("usar menos vuelos = más premio"), lo cual pelea directamente
     * con el balanceo de red y con {@link #PENALTY_LOAD_CONVEX_FACTOR}. Bajo saturación
     * (vuelos o almacenes cargados) el premio se anula por completo — ver
     * {@link #calculateUnusedFlightRewards}.
     *
     * <p><strong>Validates: Requirement 9.7</strong>
     */
    public static final double REWARD_UNUSED_FLIGHT = 5.0;

    /**
     * Umbral de ocupación media de vuelos usados por encima del cual se desactiva
     * {@link #REWARD_UNUSED_FLIGHT} (evita premios que concentran carga).
     */
    public static final double UNUSED_FLIGHT_SATURATION_LOAD_RATIO = 0.70;

    /**
     * Umbral de ocupación relativa pico de almacén por encima del cual se desactiva
     * el premio por vuelos no usados.
     */
    public static final double UNUSED_FLIGHT_SATURATION_STORAGE_RATIO = 0.80;
    
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

    /**
     * Costo marginal CONVEXO por ocupación PICO de almacén: β · pico²/capacidad por aeropuerto.
     *
     * <p>Requisito del curso: la red debe repartir la carga entre TODOS los almacenes —
     * un aeropuerto al 90% mientras otros cinco están al 10% es una mala solución aunque
     * nadie exceda su capacidad (por eso existen las rutas con escalas). La penalización
     * dura de almacén solo castiga el DESBORDE; entre 0% y 100% el algoritmo era
     * indiferente y concentraba tránsito en los mismos hubs. Igual que el término convexo
     * de vuelos: concentrar 400 maletas en un almacén de 440 cuesta β·400²/440 ≈ 727 pts,
     * repartirlas 200/200 entre dos hubs ≈ 363 pts → el GA/Tabú prefiere repartir.</p>
     *
     * <p>Del mismo orden que los premios (cientos de puntos) y muy por debajo de las
     * restricciones duras (10k-50k): balancea sin sacrificar SLA ni factibilidad.</p>
     */
    public static final double PENALTY_STORAGE_CONVEX_FACTOR = 2.0;

    /**
     * Penalización por desbalance GLOBAL de ocupación de almacenes (varianza de ratios).
     *
     * <p>El término convexo por aeropuerto tocado no castiga "un hub al 90% y cinco al 10%"
     * si los vacíos nunca aparecen en la solución. Este término mira TODOS los aeropuertos
     * de la red (ocupación = max(pico del ciclo, baseline)) y penaliza la varianza de
     * ratios ocupación/capacidad → incentiva rutas multi-hop que usen hubs subutilizados.
     *
     * <p>Escala: con N≈30 aeropuertos y varianza 0.05 → ~540 pts; lejos de las restricciones
     * duras (10k–50k), pero del orden de las demás penalizaciones/premios "blandos" (cientos
     * de puntos) para que sí incline el desempate entre rutas de costo similar hacia hubs
     * subutilizados. No puede superar factibilidad/SLA.
     */
    public static final double PENALTY_GLOBAL_IMBALANCE_FACTOR = 360.0;
    
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
     * Ocupación de almacén YA EXISTENTE por aeropuerto al inicio de la ventana del ciclo
     * (maletas de rutas planificadas en ciclos anteriores que están físicamente en tránsito).
     *
     * <p>Sin esto, el GA/Tabú evaluaba cada ciclo EN EL VACÍO: un almacén al borde del
     * desborde por rutas de ciclos previos parecía vacío al planificar rutas nuevas, y el
     * algoritmo seguía metiéndole carga hasta el colapso. Con la línea base, tanto el
     * desborde duro como el término convexo de balanceo ven la ocupación absoluta real.</p>
     *
     * <p>Mapa de solo lectura (se reemplaza por referencia cada ciclo) → seguro para las
     * evaluaciones concurrentes del GA.</p>
     */
    private volatile java.util.Map<com.equipo2b.scheduler.model.Airport, Integer> storageBaseline = java.util.Map.of();
    
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

    /**
     * Fija la ocupación de almacén preexistente por aeropuerto (rutas de ciclos previos)
     * para que las evaluaciones del ciclo vean la carga absoluta real. Null = sin base.
     */
    public void setStorageBaseline(java.util.Map<com.equipo2b.scheduler.model.Airport, Integer> baseline) {
        this.storageBaseline = baseline != null ? baseline : java.util.Map.of();
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

        double penalty = 0.0;
        for (java.util.Map.Entry<com.equipo2b.scheduler.model.Flight, Integer> entry : bagsPerFlight.entrySet()) {
            penalty += calculateFlightPenaltyFor(entry.getKey(), entry.getValue());
        }

        return penalty;
    }

    /**
     * Penalización de UN vuelo (exceso duro + costo convexo) dada su carga TOTAL final —
     * misma fórmula que {@link #calculateFlightCapacityPenalties}, extraída para reutilizar
     * desde el tracking incremental de {@link com.equipo2b.scheduler.execution.Scheduler}
     * (un vuelo ya cerrado no vuelve a cambiar, así que su penalización se calcula una sola
     * vez con la carga final en vez de recorrer toda la solución acumulada cada ciclo).
     *
     * @param flight Vuelo evaluado
     * @param assignedBags Total de maletas asignadas a ese vuelo (de cualquier ciclo)
     * @return Penalización (exceso duro + convexo) de ese vuelo
     */
    public double calculateFlightPenaltyFor(com.equipo2b.scheduler.model.Flight flight, int assignedBags) {
        int capacity = flight.capacity();
        int excess = assignedBags - capacity;
        double penalty = 0.0;

        if (excess > 0) {
            penalty += excess * PENALTY_FLIGHT_CAPACITY;
        }
        if (capacity > 0 && assignedBags > 0) {
            // α · load²/cap — convexo: presiona a repartir carga entre vuelos alternativos
            // (retrasa el punto de colapso). Aritmética primitiva, sin asignaciones.
            double load = assignedBags;
            penalty += PENALTY_LOAD_CONVEX_FACTOR * (load * load) / capacity;
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
        allEvents.sort(com.equipo2b.scheduler.model.StorageEvent.CHRONOLOGICAL_ORDER);

        java.util.Map<com.equipo2b.scheduler.model.Airport, Integer> baseline = this.storageBaseline;
        StorageReplayResult replay = replayStorageEvents(allEvents, baseline);

        double penalty = replay.hardPenalty();

        // Balanceo de almacenes (requisito del curso): costo convexo β·pico²/capacidad por
        // aeropuerto tocado por la solución — repartir la carga entre hubs cuesta menos que
        // concentrarla, incluso sin desborde.
        for (java.util.Map.Entry<com.equipo2b.scheduler.model.Airport, Integer> entry : replay.peakOccupancy().entrySet()) {
            penalty += calculateStorageConvexPenaltyFor(entry.getKey(), entry.getValue());
        }

        // Desbalance GLOBAL: varianza de ratios en toda la red (incluye baseline / no tocados).
        penalty += calculateGlobalStorageImbalancePenalty(replay.peakOccupancy(), baseline);

        return penalty;
    }

    /**
     * Resultado de reproducir una secuencia de eventos de almacén: penalización dura
     * acumulada (excedente puntual en cada evento), pico de ocupación alcanzado por
     * aeropuerto durante la reproducción, y ocupación final por aeropuerto (para encadenar
     * con el siguiente lote de eventos sin volver a empezar desde cero).
     */
    public record StorageReplayResult(
        double hardPenalty,
        java.util.Map<com.equipo2b.scheduler.model.Airport, Integer> peakOccupancy,
        java.util.Map<com.equipo2b.scheduler.model.Airport, Integer> endingOccupancy) {
    }

    /**
     * Reproduce una secuencia de eventos de almacén YA ORDENADOS por timestamp, partiendo de
     * una ocupación inicial dada, y devuelve penalización dura + picos alcanzados. Extraído de
     * {@link #calculateStorageCapacityPenalties} para reutilizar desde el tracking incremental
     * de {@link com.equipo2b.scheduler.execution.Scheduler}: en vez de reproducir TODA la
     * historia de eventos cada ciclo, se reproducen solo los eventos NUEVOS de este ciclo
     * partiendo de la ocupación ya conocida al inicio de la ventana (línea base) — cada evento
     * es un hecho puntual del pasado que, una vez procesado, nunca se vuelve a evaluar.
     *
     * @param sortedEvents Eventos ordenados por timestamp ascendente
     * @param startingOccupancy Ocupación por aeropuerto al inicio de la secuencia
     * @return Penalización dura total, picos alcanzados y ocupación final por aeropuerto
     */
    public StorageReplayResult replayStorageEvents(
            java.util.List<com.equipo2b.scheduler.model.StorageEvent> sortedEvents,
            java.util.Map<com.equipo2b.scheduler.model.Airport, Integer> startingOccupancy) {
        java.util.Map<com.equipo2b.scheduler.model.Airport, Integer> currentOccupancy =
            new java.util.HashMap<>(startingOccupancy);
        java.util.Map<com.equipo2b.scheduler.model.Airport, Integer> peakOccupancy = new java.util.HashMap<>();
        double penalty = 0.0;

        for (com.equipo2b.scheduler.model.StorageEvent event : sortedEvents) {
            com.equipo2b.scheduler.model.Airport airport = event.airport();
            int quantity = event.quantity();

            int previous = currentOccupancy.getOrDefault(airport, 0);
            int newOccupancy = event.type() == com.equipo2b.scheduler.model.StorageEventType.ARRIVAL
                ? previous + quantity
                : previous - quantity;
            currentOccupancy.put(airport, newOccupancy);
            peakOccupancy.merge(airport, newOccupancy, Math::max);

            int excess = newOccupancy - airport.storageCapacity();
            if (excess > 0) {
                penalty += excess * PENALTY_STORAGE_CAPACITY;
            }
        }

        return new StorageReplayResult(penalty, peakOccupancy, currentOccupancy);
    }

    /**
     * Costo convexo β·pico²/capacidad de UN aeropuerto dado su pico de ocupación — misma
     * fórmula que el término convexo dentro de {@link #calculateStorageCapacityPenalties},
     * extraída para reutilizar desde el tracking incremental (el pico histórico de un
     * aeropuerto es un valor pequeño que se mantiene entre ciclos; recalcular esta fórmula
     * sobre él cada ciclo es O(aeropuertos), no O(eventos históricos)).
     */
    public double calculateStorageConvexPenaltyFor(com.equipo2b.scheduler.model.Airport airport, int peak) {
        int capacity = airport.storageCapacity();
        if (capacity > 0 && peak > 0) {
            double p = peak;
            return PENALTY_STORAGE_CONVEX_FACTOR * (p * p) / capacity;
        }
        return 0.0;
    }

    /**
     * Varianza de ocupación relativa entre todos los aeropuertos de la red.
     * Premia soluciones que repartan carga (multi-hop hacia hubs libres).
     */
    double calculateGlobalStorageImbalancePenalty(
            java.util.Map<com.equipo2b.scheduler.model.Airport, Integer> peakOccupancy,
            java.util.Map<com.equipo2b.scheduler.model.Airport, Integer> baseline) {
        java.util.Collection<com.equipo2b.scheduler.model.Airport> airports = airportManager.getAllAirports();
        if (airports == null || airports.isEmpty()) {
            return 0.0;
        }

        java.util.List<Double> ratios = new java.util.ArrayList<>(airports.size());
        for (com.equipo2b.scheduler.model.Airport airport : airports) {
            int capacity = airport.storageCapacity();
            if (capacity <= 0) {
                continue;
            }
            int peak = peakOccupancy.getOrDefault(airport, 0);
            int base = baseline.getOrDefault(airport, 0);
            int absolute = Math.max(peak, base);
            ratios.add((double) absolute / capacity);
        }

        if (ratios.size() < 2) {
            return 0.0;
        }

        double sum = 0.0;
        for (double r : ratios) {
            sum += r;
        }
        double mean = sum / ratios.size();
        double variance = 0.0;
        for (double r : ratios) {
            double d = r - mean;
            variance += d * d;
        }
        variance /= ratios.size();

        return PENALTY_GLOBAL_IMBALANCE_FACTOR * variance * ratios.size();
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
                // Horas CONTINUAS (minutos/60.0): toHours() truncaba 30–59 min a 0 y el
                // retraso parcial no generaba gradiente (mismo bug que el premio de holgura).
                java.time.Duration slack = route.getSLASlack();
                double delayHours = Math.abs(slack.toMinutes()) / 60.0;
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
     * Contribución de fitness INTRÍNSECA de una ruta: penalización de SLA + penalización de
     * escala - premio de holgura. A diferencia de la capacidad de vuelo/almacén, estos tres
     * términos dependen solo de la ruta misma (sus propios vuelos y horarios) — nunca de
     * otras rutas — así que quedan fijos para siempre desde el momento en que la ruta se
     * crea. Usado por el tracking incremental de {@link com.equipo2b.scheduler.execution.Scheduler}
     * para sumar la contribución de una ruta nueva una sola vez, sin esperar a que "asiente"
     * ni volver a visitarla en ciclos futuros — misma fórmula que
     * {@link #calculateSLAPenalties}/{@link #calculateLayoverPenalties}/
     * {@link #calculateTimeSlackRewards} aplicada a una sola ruta.
     *
     * @param route Ruta recién creada
     * @return Penalización de SLA + penalización de escala - premio de holgura, para esta ruta
     */
    public double calculateIntrinsicRoutePenalty(com.equipo2b.scheduler.model.AssignedRoute route) {
        double penalty = 0.0;

        if (!route.meetsSLA()) {
            java.time.Duration slack = route.getSLASlack();
            double delayHours = Math.abs(slack.toMinutes()) / 60.0;
            penalty += delayHours * PENALTY_SLA_VIOLATION;
        } else {
            java.time.Duration slack = route.getSLASlack();
            double slackHours = slack.toMinutes() / 60.0;
            double routeReward = Math.min(slackHours * REWARD_TIME_SLACK_PER_HOUR, REWARD_TIME_SLACK_MAX);
            routeReward += slackHours * REWARD_SLACK_TIEBREAK_PER_HOUR;
            penalty -= routeReward;
        }

        java.util.List<com.equipo2b.scheduler.model.Flight> flights = route.getFlights();
        for (int i = 0; i < flights.size() - 1; i++) {
            java.time.Duration layover = java.time.Duration.between(
                flights.get(i).arrivalTime(),
                flights.get(i + 1).departureTime()
            );
            if (layover.toMinutes() < 10) {
                penalty += PENALTY_LAYOVER_VIOLATION;
            }
        }

        return penalty;
    }

    /**
     * Calcula premios por vuelos no utilizados.
     *
     * <p>Premio reducido ({@link #REWARD_UNUSED_FLIGHT}=5). Se anula bajo saturación de
     * vuelos o almacenes para no pelear contra el balanceo de carga.
     *
     * <p><strong>Validates: Requirement 9.7</strong>
     *
     * @param solution La solución a evaluar
     * @return Premio total por vuelos no utilizados (0 si saturado)
     */
    public double calculateUnusedFlightRewards(com.equipo2b.scheduler.model.Solution solution) {
        if (isNetworkSaturated(solution)) {
            return 0.0;
        }
        int totalFlights = flightPlan.getTotalFlights();
        int usedFlights = solution.getUsedFlights().size();
        int unusedFlights = totalFlights - usedFlights;
        if (unusedFlights <= 0) {
            return 0.0;
        }
        return unusedFlights * REWARD_UNUSED_FLIGHT;
    }

    /**
     * True si la red está saturada: carga media de vuelos usados alta o algún almacén
     * cerca del límite (incluyendo baseline). En ese régimen el premio por "no usar
     * vuelos" concentraría aún más la carga.
     */
    boolean isNetworkSaturated(com.equipo2b.scheduler.model.Solution solution) {
        java.util.Map<com.equipo2b.scheduler.model.Flight, Integer> bagsPerFlight = new java.util.HashMap<>();
        for (com.equipo2b.scheduler.model.AssignedRoute route : solution.getRoutes().values()) {
            int qty = route.getBatch().quantity();
            for (com.equipo2b.scheduler.model.Flight flight : route.getFlights()) {
                bagsPerFlight.merge(flight, qty, Integer::sum);
            }
        }
        if (!bagsPerFlight.isEmpty()) {
            double loadSum = 0.0;
            for (java.util.Map.Entry<com.equipo2b.scheduler.model.Flight, Integer> e : bagsPerFlight.entrySet()) {
                int cap = e.getKey().capacity();
                if (cap > 0) {
                    loadSum += (double) e.getValue() / cap;
                }
            }
            if (loadSum / bagsPerFlight.size() >= UNUSED_FLIGHT_SATURATION_LOAD_RATIO) {
                return true;
            }
        }

        java.util.Map<com.equipo2b.scheduler.model.Airport, Integer> baseline = this.storageBaseline;
        java.util.Map<com.equipo2b.scheduler.model.Airport, Integer> occupancy = new java.util.HashMap<>(baseline);
        for (com.equipo2b.scheduler.model.AssignedRoute route : solution.getRoutes().values()) {
            int qty = route.getBatch().quantity();
            occupancy.merge(route.getBatch().origin(), qty, Integer::sum);
            for (com.equipo2b.scheduler.model.Flight flight : route.getFlights()) {
                occupancy.merge(flight.destination(), qty, Integer::sum);
            }
        }
        for (java.util.Map.Entry<com.equipo2b.scheduler.model.Airport, Integer> e : occupancy.entrySet()) {
            int cap = e.getKey().storageCapacity();
            if (cap > 0 && (double) e.getValue() / cap >= UNUSED_FLIGHT_SATURATION_STORAGE_RATIO) {
                return true;
            }
        }
        return false;
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
     *   <li>Vuelos no utilizados: 5 puntos/vuelo (0 bajo saturación)</li>
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
