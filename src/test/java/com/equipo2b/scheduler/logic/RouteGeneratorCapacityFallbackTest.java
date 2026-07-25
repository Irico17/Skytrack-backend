package com.equipo2b.scheduler.logic;

import com.equipo2b.scheduler.model.*;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifica que el fallback escalonado de {@link RouteGenerator} (capacidad de vuelo/hub
 * suave relajable, capacidad DURA de almacén nunca relajable) nunca produce una ruta que
 * desborde un almacén — a diferencia del comportamiento anterior, donde el último recurso
 * apagaba TODO chequeo de capacidad (incluida {@link CapacityContext#hasHubCapacity}) con
 * el argumento de que "una ruta que sobrecarga es mejor que un lote sin asignar". El curso
 * exige que ningún almacén supere el 100% de ocupación (pasar de 100% cuenta como colapso),
 * así que ahora, si NINGÚN camino respeta la capacidad dura del almacén, el lote debe quedar
 * sin ruta (para reintentar más tarde) en vez de forzar el desborde.
 */
class RouteGeneratorCapacityFallbackTest {

    private static final ZoneId ZONE = ZoneId.of("America/Lima");

    private final Airport origin = new Airport("SPIM", "Lima", "PE", ZONE, 1000, -12.0, -77.0, Continent.AMERICA);
    private final Airport hub = new Airport("SCEL", "Santiago", "CL", ZONE, 100, -33.4, -70.8, Continent.AMERICA);
    private final Airport dest = new Airport("SKBO", "Bogota", "CO", ZONE, 1000, 4.7, -74.1, Continent.AMERICA);

    private final ZonedDateTime t0 = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZONE);

    private final Flight leg1 = new Flight(
        "LEG1", origin, hub, t0.plusHours(1), t0.plusHours(2), 500, FlightType.INTRACONTINENTAL);
    private final Flight leg2 = new Flight(
        "LEG2", hub, dest, t0.plusHours(2).plusMinutes(30), t0.plusHours(4), 500, FlightType.INTRACONTINENTAL);
    private final Flight direct = new Flight(
        "DIRECT", origin, dest, t0.plusHours(1), t0.plusHours(3), 500, FlightType.INTRACONTINENTAL);

    private ShipmentBatch batch(int quantity) {
        return new ShipmentBatch("B1", "a", "c1", origin, dest, quantity, t0);
    }

    @Test
    void hubFullAndNoAlternative_returnsNullInsteadOfOverflowing() {
        // Solo existe el camino vía hub; el hub ya está a capacidad exacta (0 residual).
        AirportManager airports = new AirportManager(List.of(origin, hub, dest));
        FlightPlan flightPlan = new FlightPlan(List.of(leg1, leg2));
        RouteGenerator generator = new RouteGenerator(flightPlan, airports);

        CapacityContext capacity = CapacityContext.fromBaseline(Map.of(hub, hub.storageCapacity()));

        AssignedRoute route = generator.generateFeasibleRoute(batch(8), capacity);

        assertNull(route, "Sin alternativa y con el hub lleno, el lote debe quedar sin ruta, no desbordar el almacén");
    }

    @Test
    void hubFullButDirectAlternativeExists_choosesAlternativeNotOverflow() {
        // Igual que el caso anterior, pero ahora hay un vuelo directo que evita el hub lleno.
        AirportManager airports = new AirportManager(List.of(origin, hub, dest));
        FlightPlan flightPlan = new FlightPlan(List.of(leg1, leg2, direct));
        RouteGenerator generator = new RouteGenerator(flightPlan, airports);

        CapacityContext capacity = CapacityContext.fromBaseline(Map.of(hub, hub.storageCapacity()));

        AssignedRoute route = generator.generateFeasibleRoute(batch(8), capacity);

        assertNotNull(route, "Debe encontrar la alternativa directa en vez de rendirse");
        assertFalse(route.getFlights().contains(leg1),
            "No debe pasar por el hub lleno cuando existe una alternativa que sí respeta capacidad");
    }

    @Test
    void hubNearSoftLimitButRoomLeft_relaxesOnlySoftThreshold() {
        // Hub al 95% (por encima del umbral suave 80%) pero con residual DURO para el lote.
        // El nivel 1 (estricto) lo rechaza por soft; el nivel 2 lo acepta porque el espacio
        // existe de verdad. Rechazarlo no evitaría ocupar almacén: las maletas se quedarían
        // igualmente en el almacén de ORIGEN, pero además sin ruta.
        AirportManager airports = new AirportManager(List.of(origin, hub, dest));
        FlightPlan flightPlan = new FlightPlan(List.of(leg1, leg2)); // solo vía hub
        RouteGenerator generator = new RouteGenerator(flightPlan, airports);

        int used = 95; // capacidad total = 100
        CapacityContext capacity = CapacityContext.fromBaseline(Map.of(hub, used));

        int quantity = 4; // 95 + 4 = 99 <= 100: cabe duro; soft predictivo sí dispara
        assertTrue(capacity.isHubNearLimit(hub, quantity),
            "Soft predictivo debe ver used+qty sobre el umbral");
        AssignedRoute route = generator.generateFeasibleRoute(batch(quantity), capacity);

        assertNotNull(route, "El umbral SUAVE debe poder relajarse cuando de verdad hay residual duro");
        assertTrue(capacity.hasHubCapacity(hub, quantity),
            "La ruta encontrada debe respetar la capacidad DURA del almacén");
    }

    @Test
    void softPredictive_blocksLargeBatchBeforeHubLooksFull() {
        // Hub al 70%: soft antiguo (solo used) dejaría pasar; used+qty=95 >= 80*100 → bloquea.
        AirportManager airports = new AirportManager(List.of(origin, hub, dest));
        FlightPlan flightPlan = new FlightPlan(List.of(leg1, leg2, direct));
        RouteGenerator generator = new RouteGenerator(flightPlan, airports);

        CapacityContext capacity = CapacityContext.fromBaseline(Map.of(hub, 70));
        assertFalse(capacity.isHubNearLimit(hub, 0));
        assertTrue(capacity.isHubNearLimit(hub, 25));

        AssignedRoute route = generator.generateFeasibleRoute(batch(25), capacity);
        assertNotNull(route, "Debe existir alternativa (directo) sin pasar por el hub");
        assertFalse(route.getFlights().contains(leg1),
            "No debe usar el hub cuando used+qty dispara soft y hay directo");
    }

    @Test
    void hubOverHardCapacityByOneUnit_neverOverflowsEvenWithNoAlternative() {
        // Hub a capacidad exacta: ni 1 maleta más cabe, sin alternativa fuera del hub.
        AirportManager airports = new AirportManager(List.of(origin, hub, dest));
        FlightPlan flightPlan = new FlightPlan(List.of(leg1, leg2));
        RouteGenerator generator = new RouteGenerator(flightPlan, airports);

        CapacityContext capacity = CapacityContext.fromBaseline(Map.of(hub, hub.storageCapacity()));

        AssignedRoute route = generator.generateFeasibleRoute(batch(1), capacity);

        assertNull(route, "Ni el fallback más relajado debe permitir exceder hasHubCapacity");
    }

    @Test
    void earliestFeasibleRoute_alsoNeverOverflowsHub() {
        // Mismo invariante para la variante de llegada más temprana (usada por reintentos).
        AirportManager airports = new AirportManager(List.of(origin, hub, dest));
        FlightPlan flightPlan = new FlightPlan(List.of(leg1, leg2));
        RouteGenerator generator = new RouteGenerator(flightPlan, airports);

        CapacityContext capacity = CapacityContext.fromBaseline(Map.of(hub, hub.storageCapacity()));

        AssignedRoute route = generator.generateEarliestFeasibleRoute(batch(8), capacity);

        assertNull(route, "generateEarliestFeasibleRoute tampoco debe forzar un desborde de almacén");
    }
}
