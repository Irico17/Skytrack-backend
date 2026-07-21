package com.equipo2b.scheduler.algorithm;

import com.equipo2b.scheduler.model.*;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cubre el defecto verificado #2 (DUPLICACIÓN DE MALETAS EN MUTACIÓN): antes de la Tarea A,
 * {@code mutate()} elegía lotes de la lista ORIGINAL de la semilla. Si esta partía un lote en
 * sub-lotes ("-S1"/"-S2"), {@code solution.getRoute(originalBatchId)} daba {@code null} (las
 * claves reales son los sub-lotes) y la mutación generaba una ruta NUEVA por la cantidad
 * COMPLETA del lote original, que quedaba sumada a los sub-lotes ya existentes — las mismas
 * maletas contadas dos veces.
 *
 * <p>Este test fuerza el split (capacidad de almacén de origen insuficiente para el lote
 * completo) y corre el GA de punta a punta con mutationRate=1.0 durante varias generaciones,
 * para maximizar la probabilidad de disparar el defecto si la lista efectiva no se usara.</p>
 */
class GeneticAlgorithmSeedSplitTest {

    @Test
    void mutationNeverDuplicatesBagsWhenSeedSplitsBatch() {
        ZoneId zone = ZoneId.of("America/Lima");
        Airport origin = new Airport("SPIM", "Lima", "PE", zone, 70, -12.0, -77.0, Continent.AMERICA);
        Airport destination = new Airport("SKBO", "Bogota", "CO", zone, 500, 4.7, -74.1, Continent.AMERICA);
        AirportManager airports = new AirportManager(List.of(origin, destination));

        ZonedDateTime t0 = ZonedDateTime.of(2026, 1, 1, 8, 0, 0, 0, zone);
        Flight direct = new Flight(
            "D1", origin, destination, t0.plusHours(1), t0.plusHours(4), 200, FlightType.INTRACONTINENTAL);
        FlightPlan flightPlan = new FlightPlan(List.of(direct));

        // Lote de 50 maletas: con 30 ya ocupadas en origen (baseline) el residual es 40 < 50,
        // así que la semilla greedy DEBE partirlo (admisión de origen) en B1-S1(40) + B1-S2(10).
        ShipmentBatch batch = new ShipmentBatch("B1", "a", "c1", origin, destination, 50, t0);

        GeneticAlgorithm ga = new GeneticAlgorithm(flightPlan, airports);
        ga.setStorageBaseline(Map.of(origin, 30));

        AlgorithmConfig config = new AlgorithmConfig();
        config.setInt("populationSize", 6);
        config.setInt("generations", 10);
        config.setDouble("mutationRate", 1.0);
        config.setInt("tournamentSize", 2);
        config.setInt("eliteCount", 1);
        config.setInt("stagnationLimit", 6);
        config.setInt("maxTimeMillis", 3000);
        ga.configure(config);

        Solution result = ga.optimize(List.of(batch));

        assertFalse(result.getRoutes().containsKey("B1"),
            "El lote original no debe reaparecer entero como ruta: la semilla lo partió en "
                + "B1-S1/B1-S2, y mutate() no debe regenerarlo por su id original");
        assertTrue(result.getTotalBags() <= batch.quantity(),
            "Las maletas totales de la solución (" + result.getTotalBags()
                + ") nunca deben exceder las del lote original (" + batch.quantity()
                + ") — de lo contrario, mutate() duplicó maletas");
    }
}
