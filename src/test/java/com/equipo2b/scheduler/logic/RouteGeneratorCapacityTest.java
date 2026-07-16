package com.equipo2b.scheduler.logic;

import com.equipo2b.scheduler.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RouteGeneratorCapacityTest {

    private Airport lima;
    private Airport bogota;
    private Airport quito;
    private Airport santiago;
    private FlightPlan flightPlan;
    private RouteGenerator generator;
    private Flight direct;
    private Flight viaBogotaLeg1;
    private Flight viaBogotaLeg2;
    private Flight viaQuitoLeg1;
    private Flight viaQuitoLeg2;

    @BeforeEach
    void setUp() {
        ZoneId zone = ZoneId.of("America/Lima");
        lima = new Airport("SPIM", "Lima", "PE", zone, 200, -12.0, -77.0, Continent.AMERICA);
        bogota = new Airport("SKBO", "Bogota", "CO", zone, 200, 4.7, -74.1, Continent.AMERICA);
        quito = new Airport("SEQM", "Quito", "EC", zone, 200, -0.1, -78.3, Continent.AMERICA);
        santiago = new Airport("SCEL", "Santiago", "CL", zone, 200, -33.4, -70.8, Continent.AMERICA);

        AirportManager airports = new AirportManager(List.of(lima, bogota, quito, santiago));
        ZonedDateTime t0 = ZonedDateTime.of(2026, 1, 1, 8, 0, 0, 0, zone);

        direct = new Flight("D1", lima, santiago, t0.plusHours(1), t0.plusHours(5), 50, FlightType.INTRACONTINENTAL);
        viaBogotaLeg1 = new Flight("B1", lima, bogota, t0.plusHours(1), t0.plusHours(3), 100, FlightType.INTRACONTINENTAL);
        viaBogotaLeg2 = new Flight("B2", bogota, santiago, t0.plusHours(4), t0.plusHours(7), 100, FlightType.INTRACONTINENTAL);
        viaQuitoLeg1 = new Flight("Q1", lima, quito, t0.plusHours(1), t0.plusHours(3), 100, FlightType.INTRACONTINENTAL);
        viaQuitoLeg2 = new Flight("Q2", quito, santiago, t0.plusHours(4), t0.plusHours(7), 100, FlightType.INTRACONTINENTAL);

        flightPlan = new FlightPlan(List.of(direct, viaBogotaLeg1, viaBogotaLeg2, viaQuitoLeg1, viaQuitoLeg2));
        generator = new RouteGenerator(flightPlan, airports);
        generator.configureSearchEffort(8, 5);
    }

    @Test
    void maxHopsAllowsMultiLegRoutes() {
        assertEquals(5, RouteGenerator.maxHops());
    }

    @Test
    void filtersFlightWithZeroResidualCapacity() {
        ZoneId zone = lima.zoneId();
        ZonedDateTime ingress = ZonedDateTime.of(2026, 1, 1, 8, 0, 0, 0, zone);
        ShipmentBatch batch = new ShipmentBatch("B1", "a", "c", lima, santiago, 40, ingress);

        // Saturar usando las MISMAS instancias proyectadas (-D<n>) que devuelve el plan:
        // así es exactamente como el GA aplica rutas al contexto en producción. FL-D1 y
        // FL-D2 son vuelos físicos distintos, por eso el contexto indexa por id completo.
        List<Flight> projected = flightPlan.getFlightsFromAirport(lima, ingress, ingress.plusDays(1));
        Flight projDirect = byBaseId(projected, "D1");
        Flight projB1 = byBaseId(projected, "B1");
        List<Flight> projectedFromBogota = flightPlan.getFlightsFromAirport(bogota, ingress, ingress.plusDays(1));
        Flight projB2 = byBaseId(projectedFromBogota, "B2");

        CapacityContext capacity = CapacityContext.empty();
        capacity.applyRoute(new AssignedRoute(
            new ShipmentBatch("X1", "a", "c", lima, santiago, 50, ingress),
            List.of(projDirect)));
        capacity.applyRoute(new AssignedRoute(
            new ShipmentBatch("X2", "a", "c", lima, santiago, 100, ingress),
            List.of(projB1, projB2)));

        assertFalse(capacity.hasFlightCapacity(projDirect, 40));
        assertFalse(capacity.hasFlightCapacity(projB1, 40));

        AssignedRoute route = generator.generateFeasibleRoute(batch, capacity);
        assertNotNull(route, "Should find alternative via Quito when other flights are full");
        assertTrue(route.getFlights().stream()
            .noneMatch(f -> projectedBaseId(f.flightId()).equals("D1")));
        assertTrue(route.getFlights().stream()
            .noneMatch(f -> projectedBaseId(f.flightId()).equals("B1")));
        assertTrue(route.getFlights().stream()
            .anyMatch(f -> projectedBaseId(f.flightId()).equals("Q1")));
    }

    private static Flight byBaseId(List<Flight> flights, String baseId) {
        return flights.stream()
            .filter(f -> projectedBaseId(f.flightId()).equals(baseId))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No projected flight with base id " + baseId));
    }

    /** Quita el sufijo -D<n> que agrega la proyección del FlightPlan (solo para asserts). */
    private static String projectedBaseId(String flightId) {
        return flightId.replaceAll("-D\\d+$", "");
    }

    @Test
    void filtersHubNearStorageLimitUsingBaseline() {
        ZoneId zone = lima.zoneId();
        ZonedDateTime ingress = ZonedDateTime.of(2026, 1, 1, 8, 0, 0, 0, zone);
        ShipmentBatch batch = new ShipmentBatch("B1", "a", "c", lima, santiago, 30, ingress);

        // Bogotá al 95% por baseline → soft-limit bloquea escalas ahí
        CapacityContext capacity = CapacityContext.fromBaseline(Map.of(bogota, 190));
        assertTrue(capacity.isHubNearLimit(bogota));

        AssignedRoute route = generator.generateFeasibleRoutePreferMultiHop(batch, capacity);
        assertNotNull(route);
        assertTrue(route.getFlights().stream().noneMatch(f -> f.destination().equals(bogota)
                && !f.destination().equals(santiago)),
            "Should avoid congested intermediate hub Bogotá");
    }
}
