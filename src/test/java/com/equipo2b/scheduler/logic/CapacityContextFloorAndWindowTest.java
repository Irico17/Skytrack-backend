package com.equipo2b.scheduler.logic;

import com.equipo2b.scheduler.model.*;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Invariantes del modelo time-phased corregidos tras la revisión:
 *
 * <ol>
 *   <li>El piso constante ({@code baselineFloor}: maletas SIN ruta sentadas en el almacén) y
 *       la timeline de rutas comprometidas son ADITIVOS — ocupan el mismo espacio a la vez.
 *       Antes el chequeo por intervalo usaba {@code max(piso, timeline)}, lo que subestimaba
 *       la ocupación y dejaba pasar rutas que sí desbordaban.</li>
 *   <li>El residual por VENTANA no debe heredar el pesimismo del pico global: un almacén que
 *       se satura a las 20:00 no puede bloquear una estancia de la mañana.</li>
 * </ol>
 */
class CapacityContextFloorAndWindowTest {

    private static final ZonedDateTime T0 =
        ZonedDateTime.of(2026, 1, 1, 8, 0, 0, 0, ZoneOffset.UTC);

    private static Airport airport(String id, int capacity) {
        return new Airport(id, id, "X", ZoneOffset.UTC, capacity, 0.0, 0.0, Continent.EUROPE);
    }

    private static Flight leg(String id, Airport from, Airport to, ZonedDateTime dep, ZonedDateTime arr) {
        return new Flight(id, from, to, dep, arr, 500, FlightType.INTRACONTINENTAL);
    }

    @Test
    void unroutedFloorAndCommittedTimelineAreAdditiveNotAlternative() {
        Airport origin = airport("ORI", 1000);
        Airport hub = airport("HUB", 100);
        Airport dest = airport("DST", 1000);

        // 60 maletas SIN ruta ya sentadas en el hub (piso constante).
        CapacityContext ctx = CapacityContext.fromBaseline(Map.of(hub, 60));

        // Una ruta comprometida deja 30 más en el hub durante su escala.
        Flight in = leg("IN", origin, hub, T0, T0.plusHours(2));
        Flight out = leg("OUT", hub, dest, T0.plusHours(4), T0.plusHours(6));
        ShipmentBatch committed = new ShipmentBatch("C1", "A", "c", origin, dest, 30, T0.minusHours(1));
        ctx.applyRoute(new AssignedRoute(committed, List.of(in, out)));

        // Ocupación real durante la escala: 60 (piso) + 30 (timeline) = 90 de 100.
        // Con max(60,30)=60 el sistema creía tener 40 libres y aceptaba 40 → 130% real.
        ShipmentBatch nuevo = new ShipmentBatch("N1", "A", "c", origin, dest, 40, T0.minusHours(1));
        assertFalse(
            ctx.pathFitsWarehouseHard(nuevo, List.of(in, out), 40),
            "40 maletas más sobre 60 de piso + 30 en timeline desbordarían el hub (130%)");

        // 10 sí caben exactamente (90 + 10 = 100).
        assertTrue(
            ctx.pathFitsWarehouseHard(nuevo, List.of(in, out), 10),
            "El hueco real (100-90=10) sí debe aceptarse");
    }

    @Test
    void intervalResidualIgnoresPeaksOutsideTheStayWindow() {
        Airport origin = airport("ORI", 100);
        Airport dest = airport("DST", 1000);

        CapacityContext ctx = CapacityContext.empty();

        // Una ruta comprometida satura el ORIGEN por la tarde (18:00 → 20:00).
        Flight tarde = leg("TARDE", origin, dest, T0.plusHours(10), T0.plusHours(12));
        ShipmentBatch vespertino = new ShipmentBatch("V1", "A", "c", origin, dest, 95, T0.plusHours(8));
        ctx.applyRoute(new AssignedRoute(vespertino, List.of(tarde)));

        // El pico GLOBAL del origen es 95/100 → residual global 5.
        assertEquals(5, ctx.storageResidual(origin),
            "El residual global refleja el pico de la tarde");

        // Pero por la mañana (08:00 → 09:00) el almacén está vacío: debe haber 100 libres.
        int manana = ctx.storageResidual(origin, T0, T0.plusHours(1));
        assertEquals(100, manana,
            "Una estancia matutina no debe verse penalizada por el pico vespertino, fue: " + manana);
    }

    @Test
    void intervalResidualStillRespectsTheFloor() {
        Airport origin = airport("ORI", 100);

        // 70 maletas sin ruta sentadas permanentemente: el piso aplica a CUALQUIER ventana.
        CapacityContext ctx = CapacityContext.fromBaseline(Map.of(origin, 70));

        assertEquals(30, ctx.storageResidual(origin, T0, T0.plusHours(1)),
            "El piso constante debe descontarse también en el residual por ventana");
    }
}
