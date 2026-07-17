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
 * Cola de reintento en Scheduler: los lotes sin ruta se reintentan el ciclo siguiente con
 * prioridad, salvo que su SLA haya vencido o no exista camino factible por horario.
 * SLA enunciado: 24h mismo continente / 48h distinto continente.
 */
class SchedulerRetryTest {

    private static final class RecordingAlgorithm implements OptimizationAlgorithm {
        final List<List<ShipmentBatch>> received = new ArrayList<>();

        @Override
        public Solution optimize(List<ShipmentBatch> batches) {
            received.add(List.copyOf(batches));
            return new Solution();
        }

        @Override
        public void configure(AlgorithmConfig config) {
            // no-op
        }
    }

    @Test
    void unroutedBatchIsRetriedNextCycleWithPriorityThenDroppedAfterSlaExpires() {
        ZoneId zone = ZoneId.of("America/Lima");
        Airport lima = new Airport("SPIM", "Lima", "PE", zone, 400, -12.0, -77.0, Continent.AMERICA);
        Airport bogota = new Airport("SKBO", "Bogota", "CO", zone, 400, 4.7, -74.1, Continent.AMERICA);
        AirportManager airports = new AirportManager(List.of(lima, bogota));

        ZonedDateTime t0 = ZonedDateTime.of(2026, 1, 1, 8, 0, 0, 0, zone);
        // Mismo continente -> SLA = 24h (1440 min).
        ShipmentBatch batch = new ShipmentBatch("X1", "a", "c1", lima, bogota, 10, t0);

        ShipmentQueue queue = new ShipmentQueue();
        queue.addShipment(batch);

        Flight direct = new Flight("F1", lima, bogota, t0.plusHours(2), t0.plusHours(5), 50, FlightType.INTRACONTINENTAL);
        FlightPlan flightPlan = new FlightPlan(List.of(direct));
        SolutionEvaluator evaluator = new SolutionEvaluator(flightPlan, airports);
        RouteValidator validator = new RouteValidator(airports);
        RecordingAlgorithm algorithm = new RecordingAlgorithm();
        TabuSearch tabu = new TabuSearch(flightPlan, airports);

        // Sc = 800 min/ciclo: ciclo1 cierra a +800 (aún dentro de 1440) → reintento;
        // ciclo2 cierra a +1600 (ya pasado el SLA) → se descarta.
        Scheduler scheduler = new Scheduler(
            algorithm, tabu, AlgorithmType.TABU_PURE, false,
            queue, evaluator, validator,
            60, 60, 800 // Ta=60s, Sa=60s, K=800 -> Sc=800 min
        );

        scheduler.executePlanningCycle(t0);
        assertEquals(1, algorithm.received.size());
        assertEquals(List.of("X1"), batchIds(algorithm.received.get(0)));

        scheduler.executePlanningCycle(t0.plusMinutes(800));
        assertEquals(2, algorithm.received.size());
        assertEquals(List.of("X1"), batchIds(algorithm.received.get(1)),
            "El lote sin ruta del ciclo 1 debe reintentarse en el ciclo 2");

        Solution result = scheduler.executePlanningCycle(t0.plusMinutes(1600));
        assertEquals(2, algorithm.received.size(),
            "Tras vencer su SLA, el lote no debe volver a pasarse al algoritmo");
        assertNotNull(result);
    }

    /**
     * Con SLA inter=48h, un vuelo de ~21h ya no es estructuralmente imposible.
     * Este caso usa un único vuelo que llega DESPUÉS del deadline de 48h.
     */
    @Test
    void unroutedBatchWithNoFeasiblePathIsNeverRetried() {
        ZoneId zone = ZoneId.of("Asia/Kolkata");
        Airport delhi = new Airport("VIDP", "Delhi", "IN", zone, 480, 28.6, 77.2, Continent.ASIA);
        Airport santiago = new Airport("SCEL", "Santiago", "CL", ZoneId.of("America/Santiago"), 400, -33.4, -70.8, Continent.AMERICA);
        AirportManager airports = new AirportManager(List.of(delhi, santiago));

        ZonedDateTime t0 = ZonedDateTime.of(2028, 11, 1, 5, 13, 0, 0, zone);
        // Distinto continente -> SLA = 48h. Vuelo único llega a +50h → fuera de SLA.
        ShipmentBatch batch = new ShipmentBatch("VIDP-000209178", "a", "c1", delhi, santiago, 10, t0);

        ShipmentQueue queue = new ShipmentQueue();
        queue.addShipment(batch);

        ZonedDateTime flightDep = t0.plusHours(2);
        Flight tooLate = new Flight(
            "VIDP-SCEL-LATE", delhi, santiago,
            flightDep, flightDep.plusHours(48),
            360, FlightType.INTERCONTINENTAL
        );
        FlightPlan flightPlan = new FlightPlan(List.of(tooLate));
        SolutionEvaluator evaluator = new SolutionEvaluator(flightPlan, airports);
        RouteValidator validator = new RouteValidator(airports);
        RecordingAlgorithm algorithm = new RecordingAlgorithm();
        TabuSearch tabu = new TabuSearch(flightPlan, airports);

        Scheduler scheduler = new Scheduler(
            algorithm, tabu, AlgorithmType.TABU_PURE, false,
            queue, evaluator, validator,
            60, 60, 400
        );

        scheduler.executePlanningCycle(t0);
        assertEquals(1, algorithm.received.size());

        scheduler.executePlanningCycle(t0.plusMinutes(400));
        assertEquals(1, algorithm.received.size(),
            "Un lote sin camino factible por horario/SLA no debe reintentarse");
    }

    private static List<String> batchIds(List<ShipmentBatch> batches) {
        return batches.stream().map(ShipmentBatch::batchId).toList();
    }
}
