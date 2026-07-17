package com.equipo2b.scheduler.logic;

import com.equipo2b.scheduler.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SolutionEvaluatorGatsTest {

    private Airport lima;
    private Airport bogota;
    private Airport quito;
    private Airport santiago;
    private FlightPlan flightPlan;
    private AirportManager airportManager;
    private SolutionEvaluator evaluator;

    @BeforeEach
    void setUp() {
        ZoneId zone = ZoneId.of("America/Lima");
        lima = new Airport("SPIM", "Lima", "PE", zone, 400, -12.0, -77.0, Continent.AMERICA);
        bogota = new Airport("SKBO", "Bogota", "CO", zone, 400, 4.7, -74.1, Continent.AMERICA);
        quito = new Airport("SEQM", "Quito", "EC", zone, 400, -0.1, -78.3, Continent.AMERICA);
        santiago = new Airport("SCEL", "Santiago", "CL", zone, 400, -33.4, -70.8, Continent.AMERICA);

        airportManager = new AirportManager(List.of(lima, bogota, quito, santiago));

        ZonedDateTime t0 = ZonedDateTime.of(2026, 1, 1, 8, 0, 0, 0, zone);
        Flight f1 = new Flight("F1", lima, bogota, t0.plusHours(1), t0.plusHours(4), 200, FlightType.INTRACONTINENTAL);
        Flight f2 = new Flight("F2", lima, quito, t0.plusHours(1), t0.plusHours(3), 200, FlightType.INTRACONTINENTAL);
        Flight f3 = new Flight("F3", bogota, santiago, t0.plusHours(5), t0.plusHours(9), 200, FlightType.INTRACONTINENTAL);
        Flight f4 = new Flight("F4", quito, santiago, t0.plusHours(4), t0.plusHours(8), 200, FlightType.INTRACONTINENTAL);
        Flight f5 = new Flight("F5", lima, santiago, t0.plusHours(1), t0.plusHours(6), 200, FlightType.INTRACONTINENTAL);
        flightPlan = new FlightPlan(List.of(f1, f2, f3, f4, f5));

        evaluator = new SolutionEvaluator(flightPlan, airportManager);
    }

    @Test
    void slaPenaltyUsesFractionalHoursNotTruncated() {
        ZoneId zone = lima.zoneId();
        ZonedDateTime ingress = ZonedDateTime.of(2026, 1, 1, 8, 0, 0, 0, zone);
        // SLA intra = 24h; llegada a 24h30 → retraso 30 min = 0.5h (toHours() daría 0)
        Flight late = new Flight(
            "LATE", lima, bogota,
            ingress.plusMinutes(30),
            ingress.plusHours(24).plusMinutes(30),
            200, FlightType.INTRACONTINENTAL
        );
        ShipmentBatch batch = new ShipmentBatch(
            "B1", "A1", "C1", lima, bogota, 10, ingress
        );
        AssignedRoute route = new AssignedRoute(batch, List.of(late));
        assertFalse(route.meetsSLA());

        Solution solution = new Solution();
        solution.addRoute(route);

        double penalty = evaluator.calculateSLAPenalties(solution);
        assertEquals(0.5 * SolutionEvaluator.PENALTY_SLA_VIOLATION, penalty, 1e-6);
    }

    @Test
    void globalImbalancePrefersSpreadLoadWhenOtherTermsEqual() {
        ZoneId zone = lima.zoneId();
        ZonedDateTime ingress = ZonedDateTime.of(2026, 1, 1, 8, 0, 0, 0, zone);

        Flight viaBogota1 = flightPlan.getAllFlights().stream()
            .filter(f -> f.flightId().equals("F1")).findFirst().orElseThrow();
        Flight viaBogota2 = flightPlan.getAllFlights().stream()
            .filter(f -> f.flightId().equals("F3")).findFirst().orElseThrow();
        Flight viaQuito1 = flightPlan.getAllFlights().stream()
            .filter(f -> f.flightId().equals("F2")).findFirst().orElseThrow();
        Flight viaQuito2 = flightPlan.getAllFlights().stream()
            .filter(f -> f.flightId().equals("F4")).findFirst().orElseThrow();

        // Concentrada: ambos lotes por Bogotá
        Solution concentrated = new Solution();
        concentrated.addRoute(new AssignedRoute(
            new ShipmentBatch("C1", "a", "c", lima, santiago, 80, ingress),
            List.of(viaBogota1, viaBogota2)));
        concentrated.addRoute(new AssignedRoute(
            new ShipmentBatch("C2", "a", "c", lima, santiago, 80, ingress.plusMinutes(1)),
            List.of(viaBogota1, viaBogota2)));

        // Balanceada: uno por Bogotá, uno por Quito
        Solution balanced = new Solution();
        balanced.addRoute(new AssignedRoute(
            new ShipmentBatch("B1", "a", "c", lima, santiago, 80, ingress),
            List.of(viaBogota1, viaBogota2)));
        balanced.addRoute(new AssignedRoute(
            new ShipmentBatch("B2", "a", "c", lima, santiago, 80, ingress.plusMinutes(1)),
            List.of(viaQuito1, viaQuito2)));

        double concentratedFitness = evaluator.evaluate(concentrated);
        double balancedFitness = evaluator.evaluate(balanced);

        assertTrue(balancedFitness < concentratedFitness,
            "Balanced multi-hub routing should beat concentrated load: balanced="
                + balancedFitness + " concentrated=" + concentratedFitness);
    }

    @Test
    void unusedFlightRewardDisabledUnderStorageSaturation() {
        evaluator.setStorageBaseline(Map.of(bogota, 350)); // 350/400 = 0.875 > 0.80
        ZoneId zone = lima.zoneId();
        ZonedDateTime ingress = ZonedDateTime.of(2026, 1, 1, 8, 0, 0, 0, zone);
        Flight direct = flightPlan.getAllFlights().stream()
            .filter(f -> f.flightId().equals("F5")).findFirst().orElseThrow();

        Solution solution = new Solution();
        solution.addRoute(new AssignedRoute(
            new ShipmentBatch("S1", "a", "c", lima, santiago, 10, ingress),
            List.of(direct)));

        assertEquals(0.0, evaluator.calculateUnusedFlightRewards(solution), 1e-9);
    }
}
