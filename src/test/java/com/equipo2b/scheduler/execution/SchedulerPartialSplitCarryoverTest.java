package com.equipo2b.scheduler.execution;

import com.equipo2b.scheduler.algorithm.AlgorithmConfig;
import com.equipo2b.scheduler.algorithm.AlgorithmType;
import com.equipo2b.scheduler.algorithm.OptimizationAlgorithm;
import com.equipo2b.scheduler.algorithm.TabuSearch;
import com.equipo2b.scheduler.logic.SolutionEvaluator;
import com.equipo2b.scheduler.model.*;
import com.equipo2b.scheduler.validation.RouteValidator;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contabilidad POR MALETAS en Scheduler.registerUnroutedForRetry (tarea A): cuando un lote solo
 * coloca una PARTE vía sub-lote ("B1-S1" con 30 de 100), el FALTANTE debe entrar a
 * carryoverBatches como lote reducido — no evaporarse porque el lote "ya tiene" un sub-lote.
 *
 * <p>Usa un {@link OptimizationAlgorithm} de prueba que devuelve directamente un sub-lote
 * parcial (en vez de depender de que GA/Tabú + splitting real produzcan ese resultado), para
 * aislar exactamente la lógica de registerUnroutedForRetry sin ruido de heurística.
 * {@code partialFillEnabled=false} (constructor simple, sin FlightPlan): applyCapacityAwareSplitting
 * nunca corre en este test, así que el único mecanismo bajo prueba es el de esta tarea.</p>
 */
class SchedulerPartialSplitCarryoverTest {

    /**
     * Primera llamada: enruta SOLO 30 de las 100 maletas del lote recibido, vía un sub-lote
     * "&lt;id&gt;-S1" — simula lo que produciría GATS + splitting en producción sin depender de
     * que ese mecanismo se dispare de verdad. Llamadas siguientes: enruta el lote recibido
     * COMPLETO bajo su propio id (tal cual llega).
     */
    private static final class PartialThenFullAlgorithm implements OptimizationAlgorithm {
        final List<List<ShipmentBatch>> received = new ArrayList<>();
        private final Flight flight;
        private int calls = 0;

        PartialThenFullAlgorithm(Flight flight) {
            this.flight = flight;
        }

        @Override
        public Solution optimize(List<ShipmentBatch> batches) {
            received.add(List.copyOf(batches));
            calls++;
            Solution solution = new Solution();
            if (calls == 1) {
                ShipmentBatch batch = batches.get(0);
                ShipmentBatch partial = new ShipmentBatch(
                    batch.batchId() + "-S1", batch.airportBatchId() + "-S1", batch.clientId(),
                    batch.origin(), batch.destination(), 30, batch.ingressTime());
                solution.addRoute(new AssignedRoute(partial, List.of(flight)));
            } else {
                for (ShipmentBatch batch : batches) {
                    solution.addRoute(new AssignedRoute(batch, List.of(flight)));
                }
            }
            return solution;
        }

        @Override
        public void configure(AlgorithmConfig config) {
            // no-op
        }
    }

    @Test
    void missingBagsFromPartialSplitEnterCarryoverWithFreshId() {
        ZoneId zone = ZoneId.of("America/Lima");
        Airport lima = new Airport("SPIM", "Lima", "PE", zone, 400, -12.0, -77.0, Continent.AMERICA);
        Airport bogota = new Airport("SKBO", "Bogota", "CO", zone, 400, 4.7, -74.1, Continent.AMERICA);
        AirportManager airports = new AirportManager(List.of(lima, bogota));

        ZonedDateTime t0 = ZonedDateTime.of(2026, 1, 1, 8, 0, 0, 0, zone);
        // Mismo continente -> SLA = 24h. Cantidad grande para que sobrevivan 70 tras colocar 30.
        ShipmentBatch batch = new ShipmentBatch("B1", "a", "c1", lima, bogota, 100, t0);

        ShipmentQueue queue = new ShipmentQueue();
        queue.addShipment(batch);

        Flight direct = new Flight("F1", lima, bogota, t0.plusHours(2), t0.plusHours(5), 200, FlightType.INTRACONTINENTAL);
        FlightPlan flightPlan = new FlightPlan(List.of(direct));
        SolutionEvaluator evaluator = new SolutionEvaluator(flightPlan, airports);
        RouteValidator validator = new RouteValidator(airports);
        // TabuSearch real solo para hasFeasiblePathIgnoringCapacity (chequeo estructural) —
        // el ruteo real lo hace PartialThenFullAlgorithm, no este TabuSearch.
        TabuSearch tabu = new TabuSearch(flightPlan, airports);
        PartialThenFullAlgorithm algorithm = new PartialThenFullAlgorithm(direct);

        // Sc = 800 min/ciclo: SLA (24h=1440min) sigue vigente después de un ciclo.
        Scheduler scheduler = new Scheduler(
            algorithm, tabu, AlgorithmType.TABU_PURE, false,
            queue, evaluator, validator,
            60, 60, 800
        );

        // --- Ciclo 1: solo se coloca "B1-S1" con 30 de 100. ---
        Solution afterCycle1 = scheduler.executePlanningCycle(t0);

        assertNotNull(afterCycle1.getRoute("B1-S1"), "El sub-lote parcial debe existir");
        assertEquals(30, afterCycle1.getRoute("B1-S1").getBatch().quantity());
        assertNull(afterCycle1.getRoute("B1"), "El id BASE nunca se usó — solo se creó el sub-lote");

        // Bajo el bug (isRouted todo/nada), esto habría sido 0: "B1-S1" existía -> lote entero
        // se daba por enrutado -> las 70 maletas restantes nunca se contaban ni reintentaban.
        assertEquals(100, scheduler.getLastCycleBagsTotal());
        assertEquals(70, scheduler.getLastCycleBagsUnrouted(),
            "Las 70 maletas que NUNCA obtuvieron ruta deben contarse como sin ruta, no darse "
                + "por enrutadas solo porque el lote ya tiene un sub-lote parcial");
        assertEquals(1, scheduler.getLastCycleBatchesUnrouted());

        // --- Ciclo 2: el faltante (70) debe reintentarse con un id FRESCO (no "B1": ese id ya
        //     está libre pero routedQuantity("B1") se contaminaría con "B1-S1" de un lote
        //     hermano en ciclos futuros si se reutilizara sin cuidado; no "B1-S1": esa ruta ya
        //     existe y reutilizarla la sobrescribiría). Debe ser "B1-S2".
        scheduler.executePlanningCycle(t0.plusMinutes(800));

        assertEquals(2, algorithm.received.size());
        List<ShipmentBatch> secondCycleBatches = algorithm.received.get(1);
        assertEquals(1, secondCycleBatches.size());
        assertEquals("B1-S2", secondCycleBatches.get(0).batchId(),
            "El lote de carryover debe colgar de la misma base ('B1') con un sufijo LIBRE, "
                + "nunca reutilizar 'B1-S1' (ya ocupado) ni la base 'a secas' (contaminaría la "
                + "contabilidad de un lote hermano existente)");
        assertEquals(70, secondCycleBatches.get(0).quantity());

        Solution afterCycle2 = scheduler.getCurrentSolution();
        assertNotNull(afterCycle2.getRoute("B1-S2"));
        assertEquals(70, afterCycle2.getRoute("B1-S2").getBatch().quantity());

        // Conservación: las 100 maletas originales están, entre los dos sub-lotes, ni una de
        // más ni una de menos.
        int total = afterCycle2.getRoute("B1-S1").getBatch().quantity()
            + afterCycle2.getRoute("B1-S2").getBatch().quantity();
        assertEquals(100, total, "Ni pérdida ni duplicación de maletas entre los dos ciclos");

        assertEquals(0, scheduler.getLastCycleBagsUnrouted(), "Ciclo 2 debe quedar completo");
    }
}
