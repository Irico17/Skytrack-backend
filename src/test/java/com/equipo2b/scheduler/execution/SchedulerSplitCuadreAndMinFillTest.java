package com.equipo2b.scheduler.execution;

import com.equipo2b.scheduler.algorithm.AlgorithmConfig;
import com.equipo2b.scheduler.algorithm.AlgorithmType;
import com.equipo2b.scheduler.algorithm.OptimizationAlgorithm;
import com.equipo2b.scheduler.algorithm.TabuSearch;
import com.equipo2b.scheduler.logic.SolutionEvaluator;
import com.equipo2b.scheduler.model.*;
import com.equipo2b.scheduler.validation.RouteValidator;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dos verificaciones sobre el MISMO ciclo con splitting real (Scheduler.applyCapacityAwareSplitting):
 * <ul>
 *   <li><b>Tarea C</b> (cuadre por ciclo): el log de invariante de registerUnroutedForRetry debe
 *       balancear (enrutadas + reintento + SLA vencido + estructurales = maletas consumidas)
 *       SIN disparar "⚠ DESCUADRE".</li>
 *   <li><b>Tarea E</b> (mínimo de sub-lote con excepción de último recurso): un remanente de
 *       overflow de solo 2 maletas (menor que MIN_FILL_BAGS=3) debe colocarse igual en vez de
 *       perderse — "mejor ubicar 2 maletas que perderlas".</li>
 * </ul>
 *
 * <p>Usa un {@link OptimizationAlgorithm} de prueba que asigna el lote COMPLETO a un vuelo cuya
 * capacidad declarada es menor (sobre-reserva deliberada e inmediata, sin depender de que GA/Tabú
 * lo produzca de forma natural — {@link AssignedRoute} no valida capacidad de vuelo, solo
 * legalidad estructural de la ruta), para que applyCapacityAwareSplitting tenga overflow real y
 * determinista que corregir en un solo ciclo.</p>
 */
class SchedulerSplitCuadreAndMinFillTest {

    private static final class OverAssignAlgorithm implements OptimizationAlgorithm {
        private final Flight overCapacityFlight;

        OverAssignAlgorithm(Flight overCapacityFlight) {
            this.overCapacityFlight = overCapacityFlight;
        }

        @Override
        public Solution optimize(List<ShipmentBatch> batches) {
            Solution solution = new Solution();
            for (ShipmentBatch batch : batches) {
                solution.addRoute(new AssignedRoute(batch, List.of(overCapacityFlight)));
            }
            return solution;
        }

        @Override
        public void configure(AlgorithmConfig config) {
            // no-op
        }
    }

    @Test
    void tinyOverflowRemainderIsPlacedAndCycleBalancesWithoutDescuadre() {
        ZoneId zone = ZoneId.of("America/Lima");
        Airport lima = new Airport("SPIM", "Lima", "PE", zone, 400, -12.0, -77.0, Continent.AMERICA);
        Airport bogota = new Airport("SKBO", "Bogota", "CO", zone, 400, 4.7, -74.1, Continent.AMERICA);
        AirportManager airports = new AirportManager(List.of(lima, bogota));

        ZonedDateTime t0 = ZonedDateTime.of(2026, 1, 1, 8, 0, 0, 0, zone);

        // F1: capacidad 9, recibirá las 11 maletas completas del lote (sobre-reserva de 2).
        Flight f1Base = new Flight("F1", lima, bogota, t0.plusHours(2), t0.plusHours(5), 9, FlightType.INTRACONTINENTAL);
        // F2: alternativa directa con espacio libre EXACTO para el remanente (2) — menor que
        // MIN_FILL_BAGS=3, así que solo cabe gracias a la excepción de último recurso.
        Flight f2Base = new Flight("F2", lima, bogota, t0.plusHours(3), t0.plusHours(6), 2, FlightType.INTRACONTINENTAL);
        FlightPlan flightPlan = new FlightPlan(List.of(f1Base, f2Base));

        // FlightPlan proyecta cada vuelo base con sufijo "-D<n>" (incluso para el mismo día,
        // "-D0") al consultarlo por ventana — ver FlightPlan.getFlightsFromAirport/
        // projectFlightsForWholeDays. placeBagsInLeftover consulta el FlightPlan (no el objeto
        // Flight crudo), así que el algoritmo de prueba debe usar el MISMO objeto proyectado
        // que verá applyCapacityAwareSplitting — si usara el Flight base "F1" directamente,
        // usedByFlight (indexado por el id proyectado) nunca vería la ocupación real de F1.
        List<Flight> projected = flightPlan.getFlightsFromAirport(lima, t0, t0.plusMinutes(800));
        Flight f1 = projected.stream().filter(f -> f.flightId().startsWith("F1")).findFirst().orElseThrow();
        Flight f2 = projected.stream().filter(f -> f.flightId().startsWith("F2")).findFirst().orElseThrow();

        ShipmentBatch batch = new ShipmentBatch("B1", "a", "c1", lima, bogota, 11, t0);
        ShipmentQueue queue = new ShipmentQueue();
        queue.addShipment(batch);

        SolutionEvaluator evaluator = new SolutionEvaluator(flightPlan, airports);
        RouteValidator validator = new RouteValidator(airports);
        TabuSearch tabu = new TabuSearch(flightPlan, airports);
        OverAssignAlgorithm algorithm = new OverAssignAlgorithm(f1);

        Scheduler scheduler = new Scheduler(
            algorithm, tabu, AlgorithmType.TABU_PURE, false,
            queue, evaluator, validator,
            60, 60, 800,
            flightPlan, true, null
        );

        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Solution result;
        try {
            System.setOut(new PrintStream(captured));
            result = scheduler.executePlanningCycle(t0);
        } finally {
            System.setOut(originalOut);
        }
        String log = captured.toString();
        // Reimprimir lo capturado para que siga visible en la salida de la corrida de tests.
        originalOut.print(log);

        // --- Tarea E: el remanente de 2 maletas (overflow de F1) se coloca en F2 pese a ser
        //     menor que MIN_FILL_BAGS=3, gracias a la excepción de último recurso. ---
        AssignedRoute reduced = result.getRoute("B1");
        assertNotNull(reduced, "La porción que se queda en el vuelo original debe conservar el id base");
        assertEquals(9, reduced.getBatch().quantity(), "F1 debe quedar exactamente en su capacidad (9)");

        AssignedRoute lastResortSubLot = result.getRoute("B1-S1");
        assertNotNull(lastResortSubLot,
            "El remanente de 2 maletas debe colocarse en F2 pese a ser menor que MIN_FILL_BAGS=3");
        assertEquals(2, lastResortSubLot.getBatch().quantity());
        assertEquals(f2.flightId(), lastResortSubLot.getFlights().get(0).flightId(),
            "Debe usar el vuelo alternativo proyectado (F2-D<n>), no F1 (sin espacio real)");

        // Conservación: 9 + 2 = 11, el total original.
        assertEquals(11, reduced.getBatch().quantity() + lastResortSubLot.getBatch().quantity());

        // --- Tarea C: cuadre por ciclo, sin descuadre. ---
        assertTrue(log.contains("Cuadre ciclo:"), "Debe loguearse la línea de invariante de cuadre");
        assertFalse(log.contains("DESCUADRE"),
            "El cuadre debe balancear (11 enrutadas, 0 a reintento/SLA/estructural) sin advertencia: \n" + log);

        assertEquals(0, scheduler.getLastCycleBagsUnrouted(),
            "Con el remanente colocado vía último recurso, el lote queda 100% enrutado este ciclo");
    }
}
