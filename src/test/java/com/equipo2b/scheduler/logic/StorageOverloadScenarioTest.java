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
 * Escenario operativo concreto: red de 20 almacenes donde la mayoría ronda el 55% y unos pocos
 * se disparan al 90%. Verifica el requisito pedido: <em>toda solución que empuje MÁS almacenes
 * por encima del nivel típico, o que agrave a los ya sobrecargados, debe costar más</em> — y
 * que ese costo nunca compita con los tiempos de entrega (SLA).
 *
 * <p>Mide el costo de almacén COMPLETO (desbalance de red + convexo por aeropuerto + término de
 * picos sobre el 80%), que es lo que realmente compara el GA/Tabú al elegir entre soluciones.</p>
 */
class StorageOverloadScenarioTest {

    private static final ZoneId ZONE = ZoneId.of("America/Lima");
    private static final int CAPACITY = 420;
    private static final int AIRPORTS = 20;
    private static final double TYPICAL = 0.55;
    private static final double HOT = 0.90;

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
        Flight f = new Flight("F1", airports.get(0), airports.get(1),
            ZonedDateTime.of(2026, 1, 1, 8, 0, 0, 0, ZONE),
            ZonedDateTime.of(2026, 1, 1, 11, 0, 0, 0, ZONE),
            200, FlightType.INTRACONTINENTAL);
        evaluator = new SolutionEvaluator(new FlightPlan(List.of(f)), new AirportManager(airports));
    }

    private Map<Airport, Integer> occupancy(int hotCount, double hotPct, double restPct) {
        Map<Airport, Integer> map = new HashMap<>();
        for (int i = 0; i < airports.size(); i++) {
            map.put(airports.get(i), (int) Math.round(CAPACITY * (i < hotCount ? hotPct : restPct)));
        }
        return map;
    }

    /** Costo de almacén total que ve el fitness: desbalance de red + convexo/picos por aeropuerto. */
    private double totalStorageCost(Map<Airport, Integer> occ) {
        double total = evaluator.calculateGlobalStorageImbalancePenalty(occ, Map.of());
        for (Map.Entry<Airport, Integer> e : occ.entrySet()) {
            total += evaluator.calculateStorageConvexPenaltyFor(e.getKey(), e.getValue());
        }
        return total;
    }

    @Test
    void pushingMoreWarehousesAboveTheTypicalLevelCostsProgressivelyMore() {
        double three = totalStorageCost(occupancy(3, HOT, TYPICAL));
        double four = totalStorageCost(occupancy(4, HOT, TYPICAL));
        double five = totalStorageCost(occupancy(5, HOT, TYPICAL));

        System.out.printf("%n=== 20 almacenes, mayoría al %.0f%% ===%n", TYPICAL * 100);
        System.out.printf("  3 al %.0f%%: %,.0f pts%n", HOT * 100, three);
        System.out.printf("  4 al %.0f%%: %,.0f pts  (+%,.0f)%n", HOT * 100, four, four - three);
        System.out.printf("  5 al %.0f%%: %,.0f pts  (+%,.0f)%n", HOT * 100, five, five - four);

        assertTrue(four > three, "Un 4º almacén disparado debe costar más que 3");
        assertTrue(five > four, "Un 5º debe costar más que 4");
        assertTrue(four - three > 1_000,
            "El salto por cada almacén que se dispara debe ser sustancial, fue: " + (four - three));
    }

    @Test
    void worseningAnAlreadyHotWarehouseCostsMoreThanSpreadingTheSameBags() {
        Map<Airport, Integer> base = occupancy(3, HOT, TYPICAL);
        double baseCost = totalStorageCost(base);
        int bags = 42;  // 10% de la capacidad

        // Opción A: cargar más el que ya está caliente (90% -> 100%).
        Map<Airport, Integer> concentrate = new HashMap<>(base);
        concentrate.merge(airports.get(0), bags, Integer::sum);

        // Opción B: repartir esas mismas maletas en un almacén del nivel típico.
        Map<Airport, Integer> spread = new HashMap<>(base);
        spread.merge(airports.get(AIRPORTS - 1), bags, Integer::sum);

        double concentrateCost = totalStorageCost(concentrate);
        double spreadCost = totalStorageCost(spread);

        System.out.printf("%n=== %d maletas extra sobre la misma red ===%n", bags);
        System.out.printf("  al almacén ya al %.0f%%  : %,.0f pts (+%,.0f)%n",
            HOT * 100, concentrateCost, concentrateCost - baseCost);
        System.out.printf("  a uno del nivel típico : %,.0f pts (+%,.0f)%n",
            spreadCost, spreadCost - baseCost);

        assertTrue(concentrateCost > spreadCost,
            "Agravar un almacén ya sobrecargado debe costar más que repartir las mismas maletas");
    }

    @Test
    void saturatingTheWholeNetworkIsStillTheMostExpensiveOutcome() {
        // Matiz importante del término de desbalance: mide desnivel RELATIVO contra la mediana,
        // así que si TODA la red sube junta al 90% el desnivel es cero. La protección contra ese
        // caso no viene de ahí sino del convexo + el término de picos sobre el 80%. Verificamos
        // que, sumados, una red uniformemente saturada sigue siendo mucho peor que unos pocos
        // hubs calientes — es decir, no hay ningún incentivo perverso a saturar a todos.
        double fewHot = totalStorageCost(occupancy(3, HOT, TYPICAL));
        double allHot = totalStorageCost(occupancy(AIRPORTS, HOT, HOT));
        double allTypical = totalStorageCost(occupancy(0, HOT, TYPICAL));

        System.out.printf("%n=== Saturación de toda la red ===%n");
        System.out.printf("  todos al %.0f%%      : %,.0f pts%n", TYPICAL * 100, allTypical);
        System.out.printf("  3 al %.0f%%, resto %.0f%%: %,.0f pts%n", HOT * 100, TYPICAL * 100, fewHot);
        System.out.printf("  todos al %.0f%%      : %,.0f pts%n", HOT * 100, allHot);

        assertTrue(allHot > fewHot,
            "Saturar toda la red debe ser peor que tener unos pocos hubs calientes");
        assertTrue(fewHot > allTypical,
            "Tener hubs disparados debe ser peor que una red pareja al nivel típico");
    }

    @Test
    void storageBalancingNeverOutweighsDeliveryTime() {
        // Invariante clave: el balanceo es un desempate BLANDO. Ni el peor desnivel imaginable
        // puede justificar retrasar una entrega (SLA = 20,000 pts por HORA de retraso).
        Map<Airport, Integer> base = occupancy(3, HOT, TYPICAL);
        Map<Airport, Integer> worse = new HashMap<>(base);
        worse.merge(airports.get(0), 42, Integer::sum);

        double storageDelta = totalStorageCost(worse) - totalStorageCost(base);
        double oneHourOfDelay = SolutionEvaluator.PENALTY_SLA_VIOLATION;
        double tenMinutesOfDelay = oneHourOfDelay / 6.0;

        System.out.printf("%n=== Balanceo vs tiempos de entrega ===%n");
        System.out.printf("  agravar el peor almacén 10%%: %,.0f pts%n", storageDelta);
        System.out.printf("  retrasar una entrega 10 min: %,.0f pts%n", tenMinutesOfDelay);
        System.out.printf("  retrasar una entrega 1 hora: %,.0f pts%n", oneHourOfDelay);

        assertTrue(storageDelta < tenMinutesOfDelay,
            "Ni agravar el peor almacén debe costar tanto como 10 minutos de retraso: "
                + storageDelta + " vs " + tenMinutesOfDelay);
    }
}
