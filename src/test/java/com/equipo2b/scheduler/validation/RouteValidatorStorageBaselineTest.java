package com.equipo2b.scheduler.validation;

import com.equipo2b.scheduler.model.*;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La validación de almacenes debe contar también las maletas SIN ruta que ya están en suelo.
 *
 * <p>Contexto del bug: {@code validateStorageCapacities} reproducía únicamente los eventos de
 * las rutas, así que un ciclo podía reportar "almacén=0 violaciones" mientras el panel mostraba
 * ese mismo aeropuerto al 109%: las maletas registradas y todavía sin ruta ocupan el almacén
 * igual que las planificadas, pero eran invisibles para el validador. Con el piso incluido, la
 * validación mide lo mismo que la pantalla.</p>
 */
class RouteValidatorStorageBaselineTest {

    private static final ZoneId ZONE = ZoneId.of("America/Lima");
    private static final ZonedDateTime T0 = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZONE);

    /** Almacén de 100 maletas en origen; el destino es holgado para no interferir. */
    private final Airport origin = new Airport("SPIM", "Lima", "PE", ZONE, 100, -12.0, -77.0, Continent.AMERICA);
    private final Airport dest = new Airport("SKBO", "Bogota", "CO", ZONE, 1000, 4.7, -74.1, Continent.AMERICA);

    private AssignedRoute routeOf(int quantity) {
        ShipmentBatch batch = new ShipmentBatch("B1", "a", "C1", origin, dest, quantity, T0);
        Flight flight = new Flight(
            "SPIM-SKBO-06:00", origin, dest, T0.plusHours(6), T0.plusHours(9), 300,
            FlightType.INTRACONTINENTAL);
        return new AssignedRoute(batch, List.of(flight));
    }

    @Test
    void routedPlanAloneFitsButOverflowsOnceUnroutedBagsCount() {
        Solution solution = new Solution();
        solution.addRoute(routeOf(60));   // 60 de 100: por sí solo cabe

        RouteValidator validator = new RouteValidator(new AirportManager(List.of(origin, dest)));

        // Sin piso: no ve nada raro — es el comportamiento que ocultaba el desborde real.
        long withoutBaseline = validator.validate(solution).getViolations().stream()
            .filter(v -> v.type() == ViolationType.STORAGE_CAPACITY)
            .count();
        assertEquals(0, withoutBaseline);

        // Con 70 maletas ya en suelo sin ruta, el almacén se va a 130 de 100.
        long withBaseline = validator.validate(solution, Map.of(origin, 70)).getViolations().stream()
            .filter(v -> v.type() == ViolationType.STORAGE_CAPACITY)
            .count();
        assertTrue(withBaseline > 0,
            "con 70 maletas sin ruta más 60 planificadas sobre capacidad 100 debe haber violación");
    }

    @Test
    void baselineThatStillFitsReportsNoViolation() {
        Solution solution = new Solution();
        solution.addRoute(routeOf(30));

        RouteValidator validator = new RouteValidator(new AirportManager(List.of(origin, dest)));
        long violations = validator.validate(solution, Map.of(origin, 40)).getViolations().stream()
            .filter(v -> v.type() == ViolationType.STORAGE_CAPACITY)
            .count();

        assertEquals(0, violations, "70 de 100 no desborda: no debe inventar violaciones");
    }

    @Test
    void nullBaselineBehavesLikeTheOldOverload() {
        Solution solution = new Solution();
        solution.addRoute(routeOf(60));

        RouteValidator validator = new RouteValidator(new AirportManager(List.of(origin, dest)));
        assertEquals(
            validator.validate(solution).getViolations().size(),
            validator.validate(solution, null).getViolations().size());
    }
}
