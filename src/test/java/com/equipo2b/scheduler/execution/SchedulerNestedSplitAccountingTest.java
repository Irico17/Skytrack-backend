package com.equipo2b.scheduler.execution;

import com.equipo2b.scheduler.model.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regresión del acuerdo de IDs ANIDADOS entre la semilla del GA, el splitting y el carryover:
 * las porciones de un lote viven en el SUBÁRBOL de su id fuente ("B1-S2" partido genera
 * "B1-S2-S1", nunca re-acuña "B1-S1" de otra generación), la contabilidad por maletas
 * ({@code routedQuantity}) suma recursivamente ese subárbol tolerando huecos (un peel que cayó
 * bajo MIN_FILL_BAGS deja índices sin ruta), y {@code freshCarryoverId} jamás entrega un slot
 * cuyo subárbol tenga rutas vivas de un ciclo anterior — reutilizarlo colisionaría los splits
 * futuros del nuevo carryover con esas rutas viejas (sobrescritura silenciosa por addRoute).
 */
class SchedulerNestedSplitAccountingTest {

    private static final ZoneId ZONE = ZoneId.of("America/Lima");

    private final Airport origin = new Airport("SPIM", "Lima", "PE", ZONE, 1000, -12.0, -77.0, Continent.AMERICA);
    private final Airport dest = new Airport("SKBO", "Bogota", "CO", ZONE, 1000, 4.7, -74.1, Continent.AMERICA);

    private final ZonedDateTime t0 = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZONE);

    private final Flight direct = new Flight(
        "DIRECT", origin, dest, t0.plusHours(1), t0.plusHours(3), 500, FlightType.INTRACONTINENTAL);

    private AssignedRoute route(String batchId, int quantity) {
        ShipmentBatch batch = new ShipmentBatch(batchId, "a", "c1", origin, dest, quantity, t0);
        return new AssignedRoute(batch, List.of(direct));
    }

    private static int routedQuantity(Solution solution, String batchId) throws Exception {
        Method m = Scheduler.class.getDeclaredMethod("routedQuantity", Solution.class, String.class);
        m.setAccessible(true);
        return (int) m.invoke(null, solution, batchId);
    }

    private static String freshCarryoverId(Solution solution, String baseId) throws Exception {
        Method m = Scheduler.class.getDeclaredMethod("freshCarryoverId", Solution.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, solution, baseId);
    }

    @Test
    void routedQuantity_sumsNestedSubtreeAcrossHoles() throws Exception {
        Solution solution = new Solution();
        // Generación 1: B1-S1 colocada (30). B1-S2 fue absorbida (sin ruta propia) pero sus
        // porciones anidadas viven: B1-S2-S1 (20) y, con hueco en -S2-S2, B1-S2-S3 (5).
        solution.addRoute(route("B1-S1", 30));
        solution.addRoute(route("B1-S2-S1", 20));
        solution.addRoute(route("B1-S2-S3", 5));

        assertEquals(55, routedQuantity(solution, "B1"),
            "Debe sumar el subárbol completo: hijos directos, nietos y hermanos tras un hueco");
        assertEquals(25, routedQuantity(solution, "B1-S2"),
            "Acotado al subárbol del carryover: solo sus propias porciones, no las de B1-S1");
        assertEquals(0, routedQuantity(solution, "B1-S3"),
            "Un id sin ruta ni descendencia no aporta nada");
    }

    @Test
    void freshCarryoverId_skipsSlotsWithLiveNestedChildren() throws Exception {
        Solution solution = new Solution();
        solution.addRoute(route("B1-S1", 30));
        // B1-S2 sin ruta propia PERO con hija viva de un ciclo anterior: el slot NO está libre.
        solution.addRoute(route("B1-S2-S1", 20));

        String id = freshCarryoverId(solution, "B1");
        assertEquals("B1-S3", id,
            "No debe reusar la base (S1 ocupada) ni B1-S2 (subárbol con rutas vivas)");
    }

    @Test
    void freshCarryoverId_reusesBareBaseOnlyWhenWholeFamilyEmpty() throws Exception {
        Solution empty = new Solution();
        assertEquals("B7", freshCarryoverId(empty, "B7"),
            "Familia completamente vacía: el caso común conserva el id base tal cual");

        Solution withBase = new Solution();
        withBase.addRoute(route("B7", 10));
        assertEquals("B7-S1", freshCarryoverId(withBase, "B7"),
            "Con la base ocupada, el primer slot libre con subárbol vacío");
    }
}
