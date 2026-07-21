package com.equipo2b.scheduler.algorithm;

import com.equipo2b.scheduler.logic.CapacityContext;
import com.equipo2b.scheduler.model.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cubre el rediseño de {@code findMaxRoutableQuantity} (Tarea D): ANTES hacía búsqueda binaria
 * con un Dijkstra completo por probe; AHORA hace UN solo probe de quantity=1 para fijar la
 * topología y calcula el cuello de botella como el mínimo residual entre almacenes y vuelos a
 * lo largo del camino — sin búsquedas de ruta adicionales.
 *
 * <p>Este test arma a mano un escenario con capacidad parcialmente ocupada (vía
 * {@link CapacityContext#applyRoute}, igual que {@code RouteGeneratorCapacityTest}) donde el
 * VUELO es el cuello de botella real (residual de vuelo &lt; residual de almacén de destino
 * &lt; residual de almacén de origen), y verifica que el método devuelve exactamente ese
 * mínimo.</p>
 *
 * <p>Se invoca por reflexión porque es privado — no hay otra forma de aislar este cálculo del
 * resto de la construcción de la semilla.</p>
 */
class GeneticAlgorithmMaxRoutableQuantityTest {

    @Test
    void returnsFlightResidualAsBottleneckWhenFlightIsTighterThanStorage() throws Exception {
        ZoneId zone = ZoneId.of("America/Lima");
        // Origen con almacén amplio (nunca es el cuello de botella).
        Airport origin = new Airport("SPIM", "Lima", "PE", zone, 1000, -12.0, -77.0, Continent.AMERICA);
        // Destino con almacén moderado (residual 55 tras el preload -- más holgado que el vuelo).
        Airport destination = new Airport("SKBO", "Bogota", "CO", zone, 90, 4.7, -74.1, Continent.AMERICA);
        AirportManager airports = new AirportManager(List.of(origin, destination));

        ZonedDateTime t0 = ZonedDateTime.of(2026, 1, 1, 8, 0, 0, 0, zone);
        // Vuelo con capacidad 60 -- el cuello de botella real tras ocupar 35.
        Flight flight = new Flight(
            "F1", origin, destination, t0.plusHours(1), t0.plusHours(3), 60, FlightType.INTRACONTINENTAL);
        FlightPlan flightPlan = new FlightPlan(List.of(flight));

        GeneticAlgorithm ga = new GeneticAlgorithm(flightPlan, airports);

        // Usar la MISMA instancia proyectada (-D<n>) que RouteGenerator obtendrá internamente
        // al buscar la ruta earliest -- así el preload de ocupación aplica al vuelo correcto
        // (CapacityContext indexa por flightId completo, ver nota en CapacityContext).
        List<Flight> projected = flightPlan.getFlightsFromAirport(origin, t0, t0.plusDays(1));
        Flight projectedFlight = projected.stream()
            .filter(f -> f.flightId().startsWith("F1"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No se proyectó el vuelo F1"));

        CapacityContext capacity = CapacityContext.empty();
        ShipmentBatch preloadBatch = new ShipmentBatch(
            "PRELOAD", "a", "cPre", origin, destination, 35, t0);
        capacity.applyRoute(new AssignedRoute(preloadBatch, List.of(projectedFlight)));

        // Tras el preload: residual vuelo = 60-35 = 25; residual almacén destino = 90-35 = 55;
        // residual almacén origen = 1000-35 = 965. El cuello de botella es el VUELO: 25.
        assertEquals(25, capacity.flightResidual(projectedFlight));
        assertEquals(55, capacity.storageResidual(destination));
        assertEquals(965, capacity.storageResidual(origin));

        ShipmentBatch batch = new ShipmentBatch("B1", "a", "c1", origin, destination, 40, t0);

        Method findMaxRoutableQuantity = GeneticAlgorithm.class.getDeclaredMethod(
            "findMaxRoutableQuantity", ShipmentBatch.class, CapacityContext.class);
        findMaxRoutableQuantity.setAccessible(true);

        int result = (int) findMaxRoutableQuantity.invoke(ga, batch, capacity);

        assertEquals(25, result,
            "Debe devolver el cuello de botella real (residual de vuelo), no el de almacén");
    }

    @Test
    void returnsNegativeOneWhenNoPathExistsEvenForOneBag() throws Exception {
        ZoneId zone = ZoneId.of("America/Lima");
        Airport origin = new Airport("SPIM", "Lima", "PE", zone, 200, -12.0, -77.0, Continent.AMERICA);
        Airport destination = new Airport("SKBO", "Bogota", "CO", zone, 200, 4.7, -74.1, Continent.AMERICA);
        Airport unreachable = new Airport("SEQM", "Quito", "EC", zone, 200, -0.1, -78.3, Continent.AMERICA);
        AirportManager airports = new AirportManager(List.of(origin, destination, unreachable));

        ZonedDateTime t0 = ZonedDateTime.of(2026, 1, 1, 8, 0, 0, 0, zone);
        // Ningún vuelo conecta origin -> destination directa o indirectamente.
        Flight unrelated = new Flight(
            "U1", unreachable, destination, t0.plusHours(1), t0.plusHours(3), 100, FlightType.INTRACONTINENTAL);
        FlightPlan flightPlan = new FlightPlan(List.of(unrelated));

        GeneticAlgorithm ga = new GeneticAlgorithm(flightPlan, airports);
        CapacityContext capacity = CapacityContext.empty();
        ShipmentBatch batch = new ShipmentBatch("B1", "a", "c1", origin, destination, 10, t0);

        Method findMaxRoutableQuantity = GeneticAlgorithm.class.getDeclaredMethod(
            "findMaxRoutableQuantity", ShipmentBatch.class, CapacityContext.class);
        findMaxRoutableQuantity.setAccessible(true);

        int result = (int) findMaxRoutableQuantity.invoke(ga, batch, capacity);

        assertEquals(-1, result, "Sin camino posible ni para 1 maleta, debe devolver -1");
    }
}
