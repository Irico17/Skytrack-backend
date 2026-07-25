package com.equipo2b.scheduler.logic;

import com.equipo2b.scheduler.model.Airport;
import com.equipo2b.scheduler.model.AssignedRoute;
import com.equipo2b.scheduler.model.Continent;
import com.equipo2b.scheduler.model.Flight;
import com.equipo2b.scheduler.model.FlightType;
import com.equipo2b.scheduler.model.ShipmentBatch;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Invariantes del modelo time-phased: dos estancias en horarios distintos pueden caber;
 * ARRIVAL futuro comprometido reserva el hub en su intervalo.
 */
class CapacityContextTimePhasedTest {

    private static Airport hub(String id, int cap) {
        return new Airport(id, id, "X", ZoneOffset.UTC, cap, 0.0, 0.0, Continent.EUROPE);
    }

    private static Flight leg(String id, Airport from, Airport to, ZonedDateTime dep, ZonedDateTime arr) {
        return new Flight(id, from, to, dep, arr, 200, FlightType.INTRACONTINENTAL);
    }

    @Test
    void sameHubDifferentWindowsBothFitUnderCapacity() {
        Airport a = hub("A", 100);
        Airport h = hub("H", 50);
        Airport b = hub("B", 100);
        ZonedDateTime t0 = ZonedDateTime.of(2026, 1, 1, 8, 0, 0, 0, ZoneOffset.UTC);

        Flight f1 = leg("F1", a, h, t0, t0.plusHours(2));
        Flight f1b = leg("F1b", h, b, t0.plusHours(3), t0.plusHours(5));
        Flight f2 = leg("F2", a, h, t0.plusHours(6), t0.plusHours(8));
        Flight f2b = leg("F2b", h, b, t0.plusHours(9), t0.plusHours(11));

        ShipmentBatch b1 = new ShipmentBatch("B1", "A1", "C1", a, b, 40, t0.minusHours(1));
        ShipmentBatch b2 = new ShipmentBatch("B2", "A1", "C1", a, b, 40, t0.plusHours(5));

        CapacityContext ctx = CapacityContext.empty();
        AssignedRoute r1 = new AssignedRoute(b1, List.of(f1, f1b));
        assertTrue(ctx.pathFitsWarehouseHard(b1, r1.getFlights(), 40));
        ctx.applyRoute(r1);

        AssignedRoute r2 = new AssignedRoute(b2, List.of(f2, f2b));
        assertTrue(ctx.pathFitsWarehouseHard(b2, r2.getFlights(), 40),
            "Misma hub en ventana distinta debe caber (time-phased)");
    }

    @Test
    void committedFutureArrivalBlocksSameLayoverWindow() {
        Airport a = hub("A", 100);
        Airport h = hub("H", 50);
        Airport b = hub("B", 100);
        ZonedDateTime t0 = ZonedDateTime.of(2026, 1, 1, 8, 0, 0, 0, ZoneOffset.UTC);

        Flight priorIn = leg("P1", a, h, t0, t0.plusHours(2));
        Flight priorOut = leg("P1b", h, b, t0.plusHours(4), t0.plusHours(6));
        ShipmentBatch priorBatch = new ShipmentBatch("P", "A1", "C1", a, b, 40, t0.minusHours(1));
        AssignedRoute prior = new AssignedRoute(priorBatch, List.of(priorIn, priorOut));

        // Nuevo lote quiere la misma escala H solapada con 20 → 40+20=60 > 50
        Flight nIn = leg("N1", a, h, t0.plusMinutes(30), t0.plusHours(2).plusMinutes(30));
        Flight nOut = leg("N1b", h, b, t0.plusHours(3).plusMinutes(30), t0.plusHours(5));
        ShipmentBatch neu = new ShipmentBatch("N", "A1", "C1", a, b, 20, t0);
        AssignedRoute neuRoute = new AssignedRoute(neu, List.of(nIn, nOut));

        CapacityContext ctx = CapacityContext.fromSolution(List.of(prior), Map.of());
        assertFalse(ctx.pathFitsWarehouseHard(neu, neuRoute.getFlights(), 20),
            "Inbound comprometido debe reservar el hub en el intervalo de layover");
    }
}
