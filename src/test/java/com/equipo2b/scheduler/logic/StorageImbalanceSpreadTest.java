package com.equipo2b.scheduler.logic;

import com.equipo2b.scheduler.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifica el término de BRECHA del almacén más cargado
 * ({@link SolutionEvaluator#PENALTY_STORAGE_SPREAD_FACTOR}), añadido tras un caso reportado en
 * operación: "un almacén al 70% mientras todos los demás están al 15-20%".
 *
 * <p>Ese patrón quedaba casi invisible con solo la varianza: numéricamente da ≈205 pts, menos
 * que el término convexo del propio almacén, y al estar por debajo del umbral de 80% tampoco
 * activaba el término de picos.</p>
 */
class StorageImbalanceSpreadTest {

    private static final ZoneId ZONE = ZoneId.of("America/Lima");
    private static final int CAPACITY = 420;
    private static final int AIRPORTS = 30;

    private List<Airport> airports;
    private SolutionEvaluator evaluator;

    @BeforeEach
    void setUp() {
        airports = new ArrayList<>();
        for (int i = 0; i < AIRPORTS; i++) {
            airports.add(new Airport(
                String.format("A%03d", i), "Ciudad" + i, "XX", ZONE,
                CAPACITY, 0.0, i, Continent.AMERICA));
        }
        AirportManager manager = new AirportManager(airports);
        Flight f = new Flight("F1", airports.get(0), airports.get(1),
            ZonedDateTime.of(2026, 1, 1, 8, 0, 0, 0, ZONE),
            ZonedDateTime.of(2026, 1, 1, 11, 0, 0, 0, ZONE),
            200, FlightType.INTRACONTINENTAL);
        evaluator = new SolutionEvaluator(new FlightPlan(List.of(f)), manager);
    }

    /** Ocupación: el primer aeropuerto al {@code highPct}, el resto a {@code restPct}. */
    private Map<Airport, Integer> occupancy(double highPct, double restPct) {
        Map<Airport, Integer> map = new HashMap<>();
        for (int i = 0; i < airports.size(); i++) {
            double pct = i == 0 ? highPct : restPct;
            map.put(airports.get(i), (int) Math.round(CAPACITY * pct));
        }
        return map;
    }

    @Test
    void oneHubMuchHigherThanTheRest_isPenalizedFarMoreThanBefore() {
        // El caso reportado: uno al 70%, los demás al 15%.
        double penaltyUneven = evaluator.calculateGlobalStorageImbalancePenalty(
            occupancy(0.70, 0.15), Map.of());

        // La MISMA carga total repartida pareja entre todos (mismo número de maletas en red).
        double evenPct = (0.70 + 29 * 0.15) / 30.0;
        double penaltyEven = evaluator.calculateGlobalStorageImbalancePenalty(
            occupancy(evenPct, evenPct), Map.of());

        assertTrue(penaltyEven < 1.0,
            "Una red perfectamente pareja no debe pagar prácticamente nada de desbalance, fue: " + penaltyEven);
        assertTrue(penaltyUneven > 1_000,
            "El hub disparado debe costar del orden de mil puntos o más, fue: " + penaltyUneven);
        assertTrue(penaltyUneven > penaltyEven * 100,
            "Concentrar debe costar muchísimo más que repartir la misma carga");
    }

    @Test
    void spreadGrowsWithTheGap_andIsNegligibleWhenNetworkIsBalanced() {
        double mild = evaluator.calculateGlobalStorageImbalancePenalty(
            occupancy(0.25, 0.20), Map.of());   // desnivel leve
        double severe = evaluator.calculateGlobalStorageImbalancePenalty(
            occupancy(0.90, 0.15), Map.of());   // desnivel severo

        assertTrue(mild < 50,
            "Un desnivel leve (25% vs 20%) debe ser despreciable, fue: " + mild);
        assertTrue(severe > mild * 20,
            "El término debe crecer rápido con la brecha (cuadrático)");
    }

    @Test
    void movingBagsFromTheHottestHubAlwaysReducesPenalty() {
        // Gradiente correcto: mover carga del más cargado a uno libre siempre debe mejorar.
        double before = evaluator.calculateGlobalStorageImbalancePenalty(
            occupancy(0.70, 0.15), Map.of());

        Map<Airport, Integer> after = occupancy(0.70, 0.15);
        int moved = 42;  // 10% de la capacidad
        after.merge(airports.get(0), -moved, Integer::sum);
        after.merge(airports.get(1), moved, Integer::sum);

        double afterPenalty = evaluator.calculateGlobalStorageImbalancePenalty(after, Map.of());
        assertTrue(afterPenalty < before,
            "Mover maletas del hub más cargado a uno libre debe BAJAR la penalización");
    }

    /** Ocupación: los primeros {@code hotCount} al {@code hotPct}, el resto a {@code restPct}. */
    private Map<Airport, Integer> occupancyWithHotspots(int hotCount, double hotPct, double restPct) {
        Map<Airport, Integer> map = new HashMap<>();
        for (int i = 0; i < airports.size(); i++) {
            double pct = i < hotCount ? hotPct : restPct;
            map.put(airports.get(i), (int) Math.round(CAPACITY * pct));
        }
        return map;
    }

    @Test
    void twoOverloadedHubsCostAboutTwiceAsMuchAsOne() {
        // El caso que exige usar MEDIANA y no promedio: con el promedio como referencia, dos
        // almacenes disparados lo arrastran hacia arriba y la distancia se encoge, así que
        // DOS penalizaban MENOS que UNO. Con la mediana, dos cuestan ~el doble.
        double one = evaluator.calculateGlobalStorageImbalancePenalty(
            occupancyWithHotspots(1, 0.95, 0.55), Map.of());
        double two = evaluator.calculateGlobalStorageImbalancePenalty(
            occupancyWithHotspots(2, 0.95, 0.55), Map.of());
        double three = evaluator.calculateGlobalStorageImbalancePenalty(
            occupancyWithHotspots(3, 0.95, 0.55), Map.of());

        assertTrue(two > one * 1.7,
            "Dos hubs saturados deben costar cerca del doble que uno (uno=" + one + ", dos=" + two + ")");
        assertTrue(three > two,
            "Tres deben costar más que dos (dos=" + two + ", tres=" + three + ")");
    }

    @Test
    void networkAtAUniformHighLevelIsNotPenalizedAsImbalance() {
        // "Si la mayoría está al 50-60%, que todos ronden ahí": una red toda al 60% está
        // cargada pero NO desnivelada — este término no debe cobrarle nada (de la carga
        // absoluta ya se encargan el convexo y el término de picos sobre 80%).
        double uniformHigh = evaluator.calculateGlobalStorageImbalancePenalty(
            occupancyWithHotspots(0, 0.60, 0.60), Map.of());
        assertTrue(uniformHigh < 1.0,
            "Una red uniforme al 60% no debe pagar penalización de desnivel, fue: " + uniformHigh);
    }

    @Test
    void softPenaltyNeverOutweighsAbandoningABag() {
        // Invariante de seguridad: el costo marginal de una maleta más en el peor almacén debe
        // seguir MUY por debajo de dejarla sin asignar (si no, el algoritmo preferiría perderla).
        Map<Airport, Integer> base = occupancy(0.70, 0.15);
        double before = evaluator.calculateGlobalStorageImbalancePenalty(base, Map.of());

        Map<Airport, Integer> plusOne = new HashMap<>(base);
        plusOne.merge(airports.get(0), 1, Integer::sum);
        double marginal = evaluator.calculateGlobalStorageImbalancePenalty(plusOne, Map.of()) - before;

        assertTrue(marginal < SolutionEvaluator.PENALTY_UNASSIGNED_PER_BAG / 10.0,
            "El costo marginal de una maleta (" + marginal + ") debe ser muy inferior a abandonarla ("
                + SolutionEvaluator.PENALTY_UNASSIGNED_PER_BAG + ")");
    }
}
