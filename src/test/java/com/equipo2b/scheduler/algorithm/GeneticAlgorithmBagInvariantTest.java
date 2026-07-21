package com.equipo2b.scheduler.algorithm;

import com.equipo2b.scheduler.model.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cubre el invariante de transporte por MALETAS (Tarea B) en {@code initializePopulation}:
 * un individuo con MÁS rutas pero MENOS maletas que la semilla no debe sobrevivir — debe ser
 * reemplazado por una copia de la semilla.
 *
 * <p>El invariante ANTERIOR comparaba número de RUTAS ({@code getRoutes().size()}), lo cual es
 * exactamente lo contrario de lo que se necesita cuando la semilla partió lotes: un individuo
 * con 10 rutas pequeñas (50 maletas) "ganaba" frente a una semilla con 1 ruta grande (200
 * maletas) porque 10 &gt;= 1, aunque transporte muchas menos maletas. Este test fija ese
 * escenario exacto de forma determinista.</p>
 *
 * <p>Se invoca {@code initializePopulation} por reflexión porque es privado y no existe otra
 * forma determinista de observar el invariante: reproducirlo de punta a punta vía
 * {@code optimize()} dependería del orden aleatorio de construcción de cada individuo.</p>
 */
class GeneticAlgorithmBagInvariantTest {

    @Test
    void individualWithMoreRoutesButFewerBagsIsReplacedBySeed() throws Exception {
        ZoneId zone = ZoneId.of("America/Lima");
        Airport origin = new Airport("SPIM", "Lima", "PE", zone, 1000, -12.0, -77.0, Continent.AMERICA);
        Airport destination = new Airport("SKBO", "Bogota", "CO", zone, 1000, 4.7, -74.1, Continent.AMERICA);
        AirportManager airports = new AirportManager(List.of(origin, destination));

        ZonedDateTime t0 = ZonedDateTime.of(2026, 1, 1, 8, 0, 0, 0, zone);
        Flight direct = new Flight(
            "D1", origin, destination, t0.plusHours(1), t0.plusHours(4), 500, FlightType.INTRACONTINENTAL);
        FlightPlan flightPlan = new FlightPlan(List.of(direct));

        GeneticAlgorithm ga = new GeneticAlgorithm(flightPlan, airports);

        // 10 lotes chicos (5 maletas c/u = 50 en total). Capacidad de sobra: CUALQUIER
        // individuo fresco construido por initializePopulation los enruta TODOS -> 10 rutas,
        // 50 maletas -- MÁS rutas pero MENOS maletas que la semilla de abajo.
        List<ShipmentBatch> smallBatches = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            smallBatches.add(new ShipmentBatch(
                "S" + i, "a", "c" + i, origin, destination, 5, t0.plusMinutes(i)));
        }

        // Semilla artesanal: UNA sola ruta con 200 maletas. No necesita venir de
        // buildHeuristicSolution -- initializePopulation solo la usa como (a) plantilla para el
        // individuo 0 y (b) umbral del invariante, así que basta con que sea una AssignedRoute
        // válida.
        ShipmentBatch bigBatch = new ShipmentBatch("BIG", "a", "cBig", origin, destination, 200, t0);
        AssignedRoute bigRoute = new AssignedRoute(bigBatch, List.of(direct));
        Solution seed = new Solution();
        seed.addRoute(bigRoute);
        assertEquals(1, seed.getRoutes().size());
        assertEquals(200, seed.getTotalBags());

        Method initializePopulation = GeneticAlgorithm.class.getDeclaredMethod(
            "initializePopulation", List.class, int.class, long.class, Solution.class);
        initializePopulation.setAccessible(true);

        long farDeadline = System.currentTimeMillis() + 20_000;
        @SuppressWarnings("unchecked")
        List<Solution> population = (List<Solution>) initializePopulation.invoke(
            ga, smallBatches, 5, farDeadline, seed);

        assertEquals(5, population.size());
        for (Solution individual : population) {
            // Verificación directa del invariante de la Tarea B. Con el invariante ANTERIOR
            // (por número de rutas) este assert habría fallado: 10 rutas >= 1 ruta de la
            // semilla, así que el individuo de 50 maletas NUNCA era reemplazado.
            assertTrue(individual.getTotalBags() >= seed.getTotalBags(),
                "Ningún individuo debe transportar menos maletas (" + individual.getTotalBags()
                    + ") que la semilla (" + seed.getTotalBags() + ")");
        }
    }
}
