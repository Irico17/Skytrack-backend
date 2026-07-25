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
 *   <li><strong>Maleta sin asignar:</strong> 5,000 puntos por cada maleta sin ruta (ver {@link #PENALTY_UNASSIGNED_PER_BAG});
 *       {@link #PENALTY_UNASSIGNED_BATCH} (50,000 pts/lote) se conserva como fallback de compatibilidad</li>
 * </ul>
 *
 * <h2>Penalizaciones Blandas (desempate, órdenes de magnitud por debajo de las duras)</h2>
 * <ul>
 *   <li><strong>Carga convexa de vuelo:</strong> α·carga²/capacidad, ver {@link #PENALTY_LOAD_CONVEX_FACTOR}</li>
 *   <li><strong>Pico convexo de almacén:</strong> β·pico²/capacidad en todo el rango, ver {@link #PENALTY_STORAGE_CONVEX_FACTOR}</li>
 *   <li><strong>Pico de almacén SOBRE el umbral ámbar (80%):</strong> término adicional γ·(pico−0.80·cap)²/capacidad
 *       que solo se activa por encima de {@link #STORAGE_PEAK_THRESHOLD_RATIO}, ver {@link #PENALTY_STORAGE_PEAK_FACTOR} —
 *       sin este término, un pico al 60% y uno al 95% escalaban igual aunque el segundo esté en zona de riesgo</li>
 *   <li><strong>Desbalance global de almacenes:</strong> varianza de ocupación relativa, ver {@link #PENALTY_GLOBAL_IMBALANCE_FACTOR}</li>
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
 *   <li>Abandonar maletas escale con el tamaño del lote (5,000 pts/maleta) en vez de costar
 *       lo mismo para 1 maleta que para 200, y quede siempre por debajo del costo de desbordar
 *       un almacén (15,000 pts/maleta) — ante el dilema, es más barato dejar una maleta sin
 *       asignar (se reintenta) que desbordar un almacén (colapso operativo)</li>
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
     * Penalización por cada MALETA (no lote) que no pudo ser asignada a ninguna ruta.
     *
     * <p>Valor: 5,000 puntos por maleta sin asignar.
     *
     * <p><strong>Por qué reemplaza a {@link #PENALTY_UNASSIGNED_BATCH} como métrica principal:
     * </strong> esa penalización se calcula como {@code expectedBatchCount - routes.size()}. Los
     * splits de {@link RouteGenerator} (sufijo -S&lt;n&gt;) generan VARIAS rutas por lote
     * original, así que cada split que sí encuentra ruta suma una unidad a
     * {@code routes.size()} y CANCELA aritméticamente el castigo de un lote genuinamente sin
     * ruta en otro punto de la solución — el fitness quedaba ciego a maletas abandonadas
     * mientras el número de rutas cuadrara. Además, 50,000 puntos fijos por lote hacían
     * indiferente abandonar un lote de 1 maleta o uno de 200: ambos costaban exactamente lo
     * mismo.
     *
     * <p><strong>Calibración:</strong> con un promedio observado de ~10 maletas/lote, 5,000
     * pts/maleta reproduce aproximadamente los 50,000 pts/lote históricos en el caso típico,
     * pero ahora el costo escala con el tamaño real del lote (200 maletas sin asignar cuestan
     * 200 veces lo que 1 maleta sin asignar, en vez de lo mismo). Se fija DELIBERADAMENTE por
     * debajo de {@link #PENALTY_STORAGE_CAPACITY} (15,000 pts/maleta de desborde de almacén):
     * ante el dilema "dejar 1 maleta sin asignar" vs. "desbordar un almacén en 1 maleta", el
     * algoritmo debe preferir lo primero — una maleta sin asignar se reintenta en el siguiente
     * ciclo, mientras que desbordar un almacén es un colapso operativo que el curso exige
     * evitar siempre.
     *
     * @see #setExpectedBagCount(int)
     */
    public static final double PENALTY_UNASSIGNED_PER_BAG = 5_000.0;

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
    public static final double PENALTY_LOAD_CONVEX_FACTOR = 5.0;

    /**
     * Costo marginal CONVEXO por ocupación PICO de almacén: β · pico²/capacidad por aeropuerto.
     *
     * <p>Requisito del curso: la red debe repartir la carga entre TODOS los almacenes —
     * un aeropuerto al 90% mientras otros cinco están al 10% es una mala solución aunque
     * nadie exceda su capacidad (por eso existen las rutas con escalas). La penalización
     * dura de almacén solo castiga el DESBORDE; entre 0% y 100% el algoritmo era
     * indiferente y concentraba tránsito en los mismos hubs. Igual que el término convexo
     * de vuelos: con β=8, concentrar 420 maletas en un almacén de 420 (100%) cuesta
     * β·420²/420 = 3,360 pts; repartirlas 210/210 entre dos hubs (50% cada uno) ≈ 1,680 pts
     * → el GA/Tabú prefiere repartir. Escalado 2→6→8 en sucesivas calibraciones para
     * suavizar también los picos transitorios de un solo hub, no solo el desbalance en
     * régimen permanente.</p>
     *
     * <p>Sigue muy por debajo de las restricciones duras (10k-50k) y de PENALTY_SLA_VIOLATION
     * (20k/hora) — nunca puede ganarle a SLA ni a factibilidad, solo desempata más fuerte
     * entre rutas de costo similar.</p>
     */
    public static final double PENALTY_STORAGE_CONVEX_FACTOR = 8.0;

    /**
     * Umbral de ocupación de almacén (ratio pico/capacidad) a partir del cual se activa el
     * término adicional {@link #PENALTY_STORAGE_PEAK_FACTOR} dentro de
     * {@link #calculateStorageConvexPenaltyFor}.
     *
     * <p>Alineado DELIBERADAMENTE al umbral ámbar de la UI y a
     * {@link CapacityContext#HUB_SOFT_LIMIT_RATIO} (ambos en 0.80): por debajo de ese punto un
     * pico de ocupación es "normal" y ya lo captura {@link #PENALTY_STORAGE_CONVEX_FACTOR}; por
     * encima es la zona que la operación quiere evitar activamente, y este término la hace
     * costar más que un pico equivalente por debajo del umbral.
     */
    public static final double STORAGE_PEAK_THRESHOLD_RATIO = 0.80;

    /**
     * Costo marginal CONVEXO adicional, aplicado SOLO al excedente por encima de
     * {@link #STORAGE_PEAK_THRESHOLD_RATIO}: γ · (pico − 0.80·capacidad)² / capacidad.
     *
     * <p><strong>Por qué hace falta además de {@link #PENALTY_STORAGE_CONVEX_FACTOR}:</strong>
     * ese término castiga pico²/capacidad de forma UNIFORME en todo el rango 0–100%, así que un
     * pico al 60% y uno al 95% reciben penalización proporcional a su magnitud pero ninguna
     * señal distingue que 95% está en la zona de riesgo (ámbar) que hay que evitar y 60% no —
     * el gradiente es el mismo a ambos lados del umbral. Este segundo término, activo solo por
     * encima del umbral, hace esa distinción explícita: empuja con más fuerza a alejarse
     * específicamente de la zona de peligro, no solo a "repartir un poco más".
     *
     * <p><strong>Calibración</strong> (cap = 420, mismo ejemplo que
     * {@link #PENALTY_STORAGE_CONVEX_FACTOR}). Subido de 60 a 150 por pedido operativo
     * ("penalizar más los picos"): al 100% (420) el excedente sobre el umbral es
     * 420 − 0.80·420 = 84 → 150·84²/420 ≈ 2,520 pts extra (antes ≈1,008). Al 110% (462) el
     * excedente es 126 → 150·126²/420 ≈ 5,670 pts extra (antes ≈2,268).
     *
     * <p><strong>Margen de seguridad verificado</strong>: lo que importa no es el valor
     * absoluto sino el COSTO MARGINAL de una maleta más frente a la alternativa de no
     * asignarla ({@link #PENALTY_UNASSIGNED_PER_BAG} = 5,000/maleta). En el peor caso (almacén
     * ya al 100%), sumar una maleta cuesta 150·(85²−84²)/420 ≈ 60 pts por este término más
     * 8·(421²−420²)/420 ≈ 16 pts del convexo base ≈ 76 pts — unas 65 veces más barato que
     * abandonarla. Es decir: el término empuja fuerte a REPARTIR entre almacenes, pero nunca
     * puede volver preferible dejar maletas sin ruta, ni ganarle a SLA (20k/hora) o al
     * desborde duro (15k/maleta).
     */
    public static final double PENALTY_STORAGE_PEAK_FACTOR = 150.0;

    /**
     * Penalización por desbalance GLOBAL de ocupación de almacenes (varianza de ratios).
     *
     * <p>El término convexo por aeropuerto tocado no castiga "un hub al 90% y cinco al 10%"
     * si los vacíos nunca aparecen en la solución. Este término mira TODOS los aeropuertos
     * de la red (ocupación = max(pico del ciclo, baseline)) y penaliza la varianza de
     * ratios ocupación/capacidad → incentiva rutas multi-hop que usen hubs subutilizados.
     *
     * <p>Escala: subido de 360 a 700 por pedido operativo ("penalizar más el desnivel entre
     * almacenes"). Con N≈30 aeropuertos y varianza 0.05 → ~1,050 pts (antes ~540); con la red
     * bien repartida (varianza ≈0.01) el término casi desaparece (~210 pts), que es
     * exactamente el comportamiento buscado: cuesta poco estar equilibrado y bastante estar
     * desnivelado. Sigue lejos de las restricciones duras (10k–50k) y de
     * {@link #PENALTY_SLA_VIOLATION}, así que inclina desempates hacia hubs subutilizados sin
     * poder superar factibilidad ni SLA.
     */
    public static final double PENALTY_GLOBAL_IMBALANCE_FACTOR = 700.0;

    /**
     * Penalización por SOBRECARGA RELATIVA: δ · Σ (ratio − mediana)² sobre los almacenes que
     * están POR ENCIMA del nivel típico de la red. Complementa a
     * {@link #PENALTY_GLOBAL_IMBALANCE_FACTOR}.
     *
     * <p><strong>Objetivo operativo</strong>: que la red se mantenga toda alrededor de su nivel
     * típico (si la mayoría anda al 50-60%, que todos ronden ahí) y que NINGUNO se dispare a
     * 80-90-100%. No penaliza estar cargado — penaliza estar cargado <em>de más que el resto</em>.
     *
     * <p><strong>Por qué la referencia es la MEDIANA y no el promedio</strong>: el promedio lo
     * arrastran hacia arriba los propios almacenes disparados, y el efecto es perverso. Con 20
     * almacenes, 18 al 55%: si DOS se van al 95%, el promedio sube a 59% y la distancia del peor
     * al promedio es 0.36; si se va UNO solo, el promedio es 57% y la distancia es 0.38. Es
     * decir, medido contra el promedio, <em>dos</em> almacenes saturados penalizaban MENOS que
     * uno. La mediana no se mueve por unos pocos extremos (sigue en 0.55 en ambos casos), así
     * que dos outliers cuestan exactamente el doble que uno — que es lo correcto.
     *
     * <p><strong>Por qué la suma es por almacén y no solo el peor</strong>: mirar únicamente el
     * máximo hace invisible al segundo y tercer almacén saturados. Sumando el exceso de cada uno,
     * cada hub que se dispara aporta su propio costo.
     *
     * <p><strong>Calibración</strong> (δ=4,000, cuadrático sobre excesos en [0,1]):
     * <ul>
     *   <li>Red pareja (todos ≈55%): exceso 0 → <b>0 pts</b>.</li>
     *   <li>18 al 55% + 1 al 95%: (0.40)²·4,000 ≈ <b>640 pts</b>.</li>
     *   <li>18 al 55% + 2 al 95%: 2·(0.40)²·4,000 ≈ <b>1,280 pts</b> (el doble, como debe ser).</li>
     *   <li>1 al 70% + resto al 15%: (0.55)²·4,000 ≈ <b>1,210 pts</b>.</li>
     *   <li>Desnivel leve (uno al 25%, resto al 20%): ≈<b>10 pts</b>, despreciable.</li>
     * </ul>
     *
     * <p><strong>Margen de seguridad</strong>: el costo marginal de una maleta más en el peor
     * almacén es 2·exceso/capacidad·δ ≈ 10 pts — unas 500 veces menor que
     * {@link #PENALTY_UNASSIGNED_PER_BAG}. Empuja a repartir, nunca a dejar maletas sin ruta.
     */
    public static final double PENALTY_STORAGE_SPREAD_FACTOR = 4_000.0;
    
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
     * Cantidad esperada de MALETAS (no de lotes) a planificar en el ciclo — suma de
     * {@code quantity()} de todos los lotes ORIGINALES, antes de cualquier split. Si > 0, tiene
     * prioridad sobre {@link #expectedBatchCount} en {@link #calculateUnassignedBatchPenalties}
     * (ver contrato en {@link #setExpectedBagCount(int)}).
     */
    private volatile int expectedBagCount = 0;

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
     * Establece la cantidad esperada de MALETAS (no de lotes) para penalizar maletas sin
     * asignar con {@link #PENALTY_UNASSIGNED_PER_BAG}. Llamar antes de las evaluaciones en el
     * loop de optimización, igual que {@link #setExpectedBatchCount}.
     *
     * <p><strong>Contrato:</strong> cuando {@code totalBags > 0}, esta vía tiene PRIORIDAD sobre
     * {@link #setExpectedBatchCount} dentro de {@link #calculateUnassignedBatchPenalties} — es
     * la métrica correcta porque no la enmascaran los splits de {@link RouteGenerator}. GA/Tabú
     * deben llamar a este método (con el total de maletas de todos los lotes del ciclo) en vez
     * de — o además de — {@link #setExpectedBatchCount}. Este último se conserva únicamente
     * como fallback de compatibilidad para llamadores que todavía no fueron migrados.
     *
     * @param totalBags Cantidad total de maletas esperadas en el ciclo (0 = no penalizar por
     *                   maleta; cae al fallback por lote si {@link #expectedBatchCount} > 0)
     */
    public void setExpectedBagCount(int totalBags) {
        this.expectedBagCount = totalBags;
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
     * Costo convexo de UN aeropuerto dado su pico de ocupación — misma fórmula que el término
     * convexo dentro de {@link #calculateStorageCapacityPenalties}, extraída para reutilizar
     * desde el tracking incremental (el pico histórico de un aeropuerto es un valor pequeño que
     * se mantiene entre ciclos; recalcular esta fórmula sobre él cada ciclo es O(aeropuertos),
     * no O(eventos históricos)).
     *
     * <p>Suma dos términos: β·pico²/capacidad ({@link #PENALTY_STORAGE_CONVEX_FACTOR}, activo
     * en todo el rango 0–100%) más, SOLO cuando el pico supera
     * {@link #STORAGE_PEAK_THRESHOLD_RATIO}, γ·(pico − 0.80·capacidad)²/capacidad
     * ({@link #PENALTY_STORAGE_PEAK_FACTOR}) — ver javadoc de esa constante para la aritmética
     * de calibración.
     *
     * <p><strong>Nota de integración:</strong> {@link AccumulatedFitnessTracker} llama a este
     * mismo método para su tracking incremental, así que hereda automáticamente ambos términos
     * sin cambios en ese archivo — no existe otra copia de esta fórmula en el código base.
     */
    public double calculateStorageConvexPenaltyFor(com.equipo2b.scheduler.model.Airport airport, int peak) {
        int capacity = airport.storageCapacity();
        if (capacity > 0 && peak > 0) {
            double p = peak;
            double penalty = PENALTY_STORAGE_CONVEX_FACTOR * (p * p) / capacity;

            double threshold = STORAGE_PEAK_THRESHOLD_RATIO * capacity;
            if (p > threshold) {
                double over = p - threshold;
                penalty += PENALTY_STORAGE_PEAK_FACTOR * (over * over) / capacity;
            }
            return penalty;
        }
        return 0.0;
    }

    /**
     * Desbalance de ocupación entre los almacenes de la red. Suma DOS términos complementarios:
     *
     * <ol>
     *   <li><b>Varianza</b> ({@link #PENALTY_GLOBAL_IMBALANCE_FACTOR}): dispersión general de
     *       los ratios ocupación/capacidad. Captura el desorden global de la red.</li>
     *   <li><b>Sobrecarga relativa</b> ({@link #PENALTY_STORAGE_SPREAD_FACTOR}): suma del
     *       exceso al cuadrado de CADA almacén por encima de la MEDIANA de la red.</li>
     * </ol>
     *
     * <p><strong>Por qué la varianza sola no bastaba</strong> (caso reportado en operación: un
     * almacén al 70% mientras la mayoría está al 15%): la varianza de ratios vive en un rango
     * numérico muy chico. Con 1 almacén al 70% y 29 al 15%, la suma de desviaciones cuadráticas
     * es ≈0.292 → apenas ≈205 pts con el factor de varianza, MENOS que los ≈1,646 pts que ya
     * aporta el término convexo de ese mismo almacén; y como 70% está por debajo de
     * {@link #STORAGE_PEAK_THRESHOLD_RATIO} (0.80), el término de picos ni siquiera se activa.
     * Resultado: un desnivel evidente a simple vista era casi invisible para el fitness.
     *
     * <p>El término de sobrecarga relativa ataca exactamente ese patrón: mide cuánto sobresale
     * cada almacén sobre la MEDIANA de la red (el nivel "típico"), no sobre el promedio. En ese
     * caso el exceso del hub es 0.70 − 0.15 = 0.55 → 0.55²·4,000 ≈ 1,210 pts, del orden del
     * término convexo y ahora sí decisivo. Ver {@link #PENALTY_STORAGE_SPREAD_FACTOR} para por
     * qué la mediana (y no el promedio) es la referencia correcta, y para la tabla de
     * calibración completa.
     *
     * <p><strong>Margen de seguridad</strong>: el gradiente por maleta movida del hub más
     * cargado a uno libre es ≈10 pts (2·exceso/capacidad·factor), comparable al término convexo
     * y ~500 veces menor que {@link #PENALTY_UNASSIGNED_PER_BAG} — empuja a REPARTIR, nunca a
     * dejar maletas sin ruta ni a incumplir SLA.
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

        double penalty = PENALTY_GLOBAL_IMBALANCE_FACTOR * variance * ratios.size();

        // Exceso de CADA almacén sobre el nivel TÍPICO de la red (ver javadoc). La referencia
        // es la MEDIANA, no el promedio: el promedio lo arrastran hacia arriba los propios
        // almacenes disparados, así que con 2-3 outliers la distancia al promedio se encoge y
        // el desnivel se auto-oculta. La mediana no se mueve por unos pocos valores extremos.
        java.util.List<Double> sorted = new java.util.ArrayList<>(ratios);
        java.util.Collections.sort(sorted);
        int n = sorted.size();
        double median = (n % 2 == 0)
            ? (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0
            : sorted.get(n / 2);

        double overloadSpread = 0.0;
        for (double r : ratios) {
            double over = r - median;
            if (over > 0) {
                overloadSpread += over * over;  // cada almacén sobrecargado suma lo suyo
            }
        }
        penalty += PENALTY_STORAGE_SPREAD_FACTOR * overloadSpread;
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
     *   <li>Maleta sin asignar: 5,000 puntos/maleta ({@link #PENALTY_UNASSIGNED_PER_BAG}, fallback
     *       por lote en {@link #PENALTY_UNASSIGNED_BATCH})</li>
     *   <li>Pico de almacén sobre el umbral 80%: término blando adicional
     *       ({@link #PENALTY_STORAGE_PEAK_FACTOR}), ver {@link #calculateStorageConvexPenaltyFor}</li>
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
     * Calcula penalizaciones por maletas (o, en fallback, lotes) no asignadas a ninguna ruta.
     *
     * <p><strong>Vía principal — por maleta:</strong> si se configuró
     * {@link #setExpectedBagCount}, aplica {@link #PENALTY_UNASSIGNED_PER_BAG} por cada maleta
     * de diferencia entre {@code expectedBagCount} y {@link
     * com.equipo2b.scheduler.model.Solution#getTotalBags()}. Esta cuenta suma la cantidad real
     * de maletas asignadas en TODAS las rutas de la solución (incluidos los splits, cada uno con
     * su propia {@code quantity()}), así que un split no puede enmascarar maletas genuinamente
     * sin ruta.
     *
     * <p><strong>Fallback — por lote:</strong> si no hay {@code expectedBagCount} configurado
     * pero sí {@link #setExpectedBatchCount}, conserva el comportamiento antiguo (50,000 puntos
     * por cada lote de diferencia entre {@code expectedBatchCount} y {@code routes.size()}) para
     * compatibilidad con llamadores aún no migrados a la vía por maleta.
     *
     * <p><strong>Validates: Requirement 9.8</strong>
     *
     * @param solution La solución a evaluar
     * @return Penalización total por maletas (o lotes) no asignados
     */
    public double calculateUnassignedBatchPenalties(com.equipo2b.scheduler.model.Solution solution) {
        if (expectedBagCount > 0) {
            int unassignedBags = Math.max(0, expectedBagCount - solution.getTotalBags());
            return unassignedBags * PENALTY_UNASSIGNED_PER_BAG;
        }
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
