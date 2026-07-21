package com.equipo2b.scheduler.logic;

import com.equipo2b.scheduler.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifica que {@link RouteGenerator} elija, entre varios caminos cacheados IGUALMENTE
 * factibles, el que deja hubs/vuelos relativamente más libres (Tarea D: puntuación de
 * congestión en {@code pickCapacityFeasible}), en vez de la elección uniforme al azar de
 * antes. La prueba es ESTADÍSTICA porque la elección conserva una probabilidad de diversidad
 * (25%) por diseño — ver {@code RouteGenerator.CONGESTION_LEAST_LOADED_PROBABILITY}.
 */
class RouteGeneratorCongestionChoiceTest {

    private static final ZoneId ZONE = ZoneId.of("America/Lima");

    private Airport lima;
    private Airport bogota;
    private Airport quito;
    private Airport santiago;
    private FlightPlan flightPlan;
    private RouteGenerator generator;
    private Flight viaBogotaLeg1;
    private Flight viaBogotaLeg2;
    private Flight viaQuitoLeg1;
    private Flight viaQuitoLeg2;

    @BeforeEach
    void setUp() {
        lima = new Airport("SPIM", "Lima", "PE", ZONE, 400, -12.0, -77.0, Continent.AMERICA);
        bogota = new Airport("SKBO", "Bogota", "CO", ZONE, 400, 4.7, -74.1, Continent.AMERICA);
        quito = new Airport("SEQM", "Quito", "EC", ZONE, 400, -0.1, -78.3, Continent.AMERICA);
        santiago = new Airport("SCEL", "Santiago", "CL", ZONE, 400, -33.4, -70.8, Continent.AMERICA);

        AirportManager airports = new AirportManager(List.of(lima, bogota, quito, santiago));
        ZonedDateTime t0 = ZonedDateTime.of(2026, 1, 1, 8, 0, 0, 0, ZONE);

        // Sin vuelo directo: las dos únicas rutas factibles pasan por Bogotá o por Quito.
        // Capacidad de VUELO generosa e IGUAL en ambos caminos para que el score dependa
        // casi enteramente de la ocupación de almacén de los hubs intermedios.
        viaBogotaLeg1 = new Flight("B1", lima, bogota, t0.plusHours(1), t0.plusHours(3), 500, FlightType.INTRACONTINENTAL);
        viaBogotaLeg2 = new Flight("B2", bogota, santiago, t0.plusHours(4), t0.plusHours(7), 500, FlightType.INTRACONTINENTAL);
        viaQuitoLeg1 = new Flight("Q1", lima, quito, t0.plusHours(1), t0.plusHours(3), 500, FlightType.INTRACONTINENTAL);
        viaQuitoLeg2 = new Flight("Q2", quito, santiago, t0.plusHours(4), t0.plusHours(7), 500, FlightType.INTRACONTINENTAL);

        flightPlan = new FlightPlan(List.of(viaBogotaLeg1, viaBogotaLeg2, viaQuitoLeg1, viaQuitoLeg2));
        generator = new RouteGenerator(flightPlan, airports);
        // Suficientes intentos/variantes para que la BFS aleatorizada descubra AMBOS caminos
        // (Bogotá y Quito) y los deje cacheados como candidatos.
        generator.configureSearchEffort(30, 5);
    }

    @Test
    void prefersLessCongestedHubInMajorityOfDraws() {
        ZonedDateTime ingress = ZonedDateTime.of(2026, 1, 1, 8, 0, 0, 0, ZONE);
        int qty = 10;

        // Bogotá al 75% (300/400): por debajo del soft-limit 80%, así que sigue siendo
        // FACTIBLE (300+10=310 < 320), solo más congestionado. Quito al 10% (40/400).
        CapacityContext capacity = CapacityContext.fromBaseline(Map.of(bogota, 300, quito, 40));
        assertFalse(capacity.isHubNearLimit(bogota, qty), "Bogotá debe seguir siendo factible (< 80% tras el lote)");

        int viaQuitoCount = 0;
        int viaBogotaCount = 0;
        int runs = 100;

        for (int i = 0; i < runs; i++) {
            // Mismo batchId/origen/destino/ingressTime -> misma RouteCacheKey: los candidatos
            // cacheados (ambos caminos) se calculan una sola vez; solo la ELECCIÓN entre ellos
            // se repite en cada llamada, que es justo lo que esta prueba mide.
            ShipmentBatch batch = new ShipmentBatch("B1", "a", "c", lima, santiago, qty, ingress);
            AssignedRoute route = generator.generateFeasibleRoute(batch, capacity);
            assertNotNull(route, "Ambos caminos deben ser factibles (por debajo de capacidad dura y soft)");

            boolean viaQuito = route.getFlights().stream().anyMatch(f -> baseId(f).equals("Q1"));
            boolean viaBogota = route.getFlights().stream().anyMatch(f -> baseId(f).equals("B1"));
            assertTrue(viaQuito ^ viaBogota, "La ruta debe pasar por exactamente uno de los dos hubs");

            if (viaQuito) {
                viaQuitoCount++;
            } else {
                viaBogotaCount++;
            }
        }

        // Con CONGESTION_LEAST_LOADED_PROBABILITY=0.75 y Quito claramente menos congestionado,
        // la probabilidad esperada de elegir Quito es ~0.75 + 0.25*0.5 = 0.875. Umbral laxo
        // (65/100) para evitar flakiness manteniendo la señal de "elección MAYORITARIA".
        assertTrue(viaQuitoCount >= 65,
            "Se esperaba que el hub libre (Quito) fuera elegido en la mayoría de las corridas, "
                + "pero solo ocurrió " + viaQuitoCount + "/" + runs + " veces (Bogotá: " + viaBogotaCount + ")");

        // El hub congestionado debe seguir apareciendo alguna vez (diversidad conservada, no
        // determinismo total) gracias al 25% de elección aleatoria entre factibles.
        assertTrue(viaBogotaCount >= 1,
            "El hub congestionado (Bogotá) debe seguir pudiendo elegirse ocasionalmente "
                + "(diversidad para el GA/Tabú), pero nunca ocurrió en " + runs + " corridas");
    }

    private static String baseId(Flight flight) {
        return flight.flightId().replaceAll("-D\\d+$", "");
    }
}
