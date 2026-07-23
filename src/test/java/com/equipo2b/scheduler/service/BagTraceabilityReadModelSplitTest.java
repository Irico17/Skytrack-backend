package com.equipo2b.scheduler.service;

import com.equipo2b.scheduler.api.dto.BagTraceabilityDTO;
import com.equipo2b.scheduler.model.*;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regresión de un caso REAL encontrado probando Día a Día en vivo: un lote de 500 maletas
 * que el generador de rutas parte (semilla GA / findMaxRoutableQuantity) en sub-lotes con ids
 * ANIDADOS ("UI-xxx-S1-S1", "UI-xxx-S1-S2-S1", ...) — nunca reemplazando la clave original.
 * Antes del fix, el lote original seguía apareciendo en la trazabilidad con su cantidad
 * COMPLETA sin descontar lo ya reubicado: 500 (original, sin ruta) + 340 + 90 (sub-lotes con
 * ruta) = 930 "maletas" mostradas para 500 reales, y el envío parecía "sin ruta" en el
 * inspector aunque el 86% ya viajaba.
 */
class BagTraceabilityReadModelSplitTest {

    private static final ZoneId ZONE = ZoneId.of("America/Lima");
    private final Airport origin = new Airport("SPIM", "Lima", "PE", ZONE, 1000, -12.0, -77.0, Continent.AMERICA);
    private final Airport dest = new Airport("SKBO", "Bogota", "CO", ZONE, 1000, 4.7, -74.1, Continent.AMERICA);
    private final ZonedDateTime t0 = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZONE);
    private final Flight flight = new Flight(
        "FL1", origin, dest, t0.plusHours(1), t0.plusHours(3), 500, FlightType.INTRACONTINENTAL);

    private ShipmentBatch batch(String id, int quantity) {
        return new ShipmentBatch(id, "a", "0007729", origin, dest, quantity, t0);
    }

    private AssignedRoute route(String id, int quantity) {
        return new AssignedRoute(batch(id, quantity), List.of(flight));
    }

    @Test
    void originalBatch_doesNotDoubleCountBagsAlreadyRoutedUnderNestedSplits() {
        // Registro original: 500 maletas, SIN ruta propia (solo sus descendientes la tienen).
        ShipmentBatch registered = batch("UI-83D9C182", 500);

        Solution solution = new Solution();
        solution.addRoute(route("UI-83D9C182-S1-S1", 340));
        solution.addRoute(route("UI-83D9C182-S1-S2-S1", 90));

        BagTraceabilityReadModel.Query totalsQuery = new BagTraceabilityReadModel.Query(
            0, 1, null, null, null, null);
        BagTraceabilityDTO totals = BagTraceabilityReadModel.build(
            "sim1", t0, solution, List.of(registered), totalsQuery);

        // Total real: 500 maletas físicas, nunca 930 (500 + 340 + 90 sin descontar).
        assertEquals(500, totals.summary().totalBags(),
            "El resumen debe reflejar las 500 maletas reales, no 930 por doble conteo");

        // Filtrado por estado (en vez de paginar 500 filas contra un tope de 200/página):
        // solo el remanente del id original queda PENDING_ROUTE, los dos sub-lotes ya tienen
        // ruta — aísla exactamente lo que este fix corrige sin depender del orden de
        // iteración de un HashMap.
        BagTraceabilityReadModel.Query pendingQuery = new BagTraceabilityReadModel.Query(
            0, 200, null, "PENDING_ROUTE", null, null);
        BagTraceabilityDTO pending = BagTraceabilityReadModel.build(
            "sim1", t0, solution, List.of(registered), pendingQuery);

        assertEquals(70, pending.totalItems(),
            "El id original solo debe aportar el REMANENTE (500-340-90=70), no las 500 completas");
        assertTrue(pending.bags().stream().allMatch(b -> b.batchId().equals("UI-83D9C182")),
            "Las filas pendientes deben pertenecer al id original reducido, no a los sub-lotes ya ruteados");
    }

    @Test
    void wholeBatchAbsorbedBySplits_originalIdProducesNoRows() {
        ShipmentBatch registered = batch("B1", 100);
        Solution solution = new Solution();
        solution.addRoute(route("B1-S1", 60));
        solution.addRoute(route("B1-S2", 40));

        BagTraceabilityReadModel.Query query = new BagTraceabilityReadModel.Query(
            0, 200, "B1", null, null, null);
        BagTraceabilityDTO dto = BagTraceabilityReadModel.build(
            "sim1", t0, solution, List.of(registered), query);

        assertEquals(100, dto.summary().totalBags());
        boolean anyForOriginalId = dto.bags().stream().anyMatch(b -> b.batchId().equals("B1"));
        assertFalse(anyForOriginalId,
            "Si TODO el lote ya vive en sub-lotes con ruta, el id original no debe generar filas fantasma");
    }
}
