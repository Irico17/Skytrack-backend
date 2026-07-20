package com.equipo2b.scheduler.execution;

import com.equipo2b.scheduler.algorithm.AlgorithmConfig;
import com.equipo2b.scheduler.algorithm.AlgorithmType;
import com.equipo2b.scheduler.algorithm.TabuSearch;
import com.equipo2b.scheduler.logic.SolutionEvaluator;
import com.equipo2b.scheduler.model.*;
import com.equipo2b.scheduler.validation.RouteValidator;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Invariante del fix de evaluación incremental (ver AccumulatedFitnessTracker): el fitness
 * que Scheduler reporta ciclo a ciclo — calculado SIN recorrer la solución acumulada completa
 * — debe coincidir con el que daría un recálculo honesto desde cero sobre exactamente la misma
 * solución acumulada, en cada punto de la simulación.
 *
 * <p>El escenario corre suficientes ciclos como para que los vuelos de los primeros ciclos
 * queden en el pasado respecto a los últimos (dispara settleExpiredFlights/
 * promoteSettledFrenteCaliente), y para que varios ciclos apliquen la división por capacidad
 * (applyCapacityAwareSplitting, incluyendo absorciones completas y reintentos por carryover),
 * ejercitando refreshFrenteCalienteAfterSplitting a fondo.</p>
 */
class SchedulerIncrementalFitnessTest {

    @Test
    void incrementalFitnessMatchesFullRecomputeAcrossManyCycles() {
        ZoneId zone = ZoneId.of("America/Lima");
        Airport origin = new Airport("SPIM", "Lima", "PE", zone, 400, -12.0, -77.0, Continent.AMERICA);
        Airport dest = new Airport("SKBO", "Bogota", "CO", zone, 400, 4.7, -74.1, Continent.AMERICA);
        AirportManager airports = new AirportManager(List.of(origin, dest));

        ZonedDateTime t0 = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, zone);

        // Vuelos directos cada 2h durante 40h — con Sc=2h por ciclo, los primeros vuelos
        // quedan en el pasado a mitad de la simulación (dispara asentamiento del frente
        // caliente) mientras los últimos siguen activos.
        int cycles = 20;
        List<Flight> flights = new ArrayList<>();
        for (int i = 0; i < cycles; i++) {
            ZonedDateTime dep = t0.plusHours(1 + 2L * i);
            flights.add(new Flight(
                "F" + i, origin, dest, dep, dep.plusHours(2),
                20, FlightType.INTRACONTINENTAL));
        }
        FlightPlan flightPlan = new FlightPlan(flights);

        SolutionEvaluator evaluator = new SolutionEvaluator(flightPlan, airports);
        RouteValidator validator = new RouteValidator(airports);

        TabuSearch tabu = new TabuSearch(flightPlan, airports);
        AlgorithmConfig tabuConfig = new AlgorithmConfig();
        tabuConfig.setInt("maxIterations", 50);
        tabuConfig.setInt("tabuTenure", 10);
        tabuConfig.setInt("neighborhoodSize", 10);
        tabu.configure(tabuConfig);

        ShipmentQueue queue = new ShipmentQueue();
        // Lotes suficientes por ventana como para forzar exceso de capacidad en algunos
        // vuelos (20/vuelo) y ejercitar applyCapacityAwareSplitting (peel, absorción completa
        // y reintento por carryover) de forma repetida a lo largo de la simulación.
        int batchSeq = 0;
        for (int cycle = 0; cycle < cycles; cycle++) {
            ZonedDateTime ingress = t0.plusHours(2L * cycle);
            for (int b = 0; b < 3; b++) {
                queue.addShipment(new ShipmentBatch(
                    "B" + (batchSeq++), "a", "c" + batchSeq,
                    origin, dest, 8, ingress.plusMinutes(b)));
            }
        }

        // Sa=60s, K=120 -> Sc = (60*120)/60 = 120 min = 2h por ciclo.
        Scheduler scheduler = new Scheduler(
            tabu, tabu, AlgorithmType.TABU_PURE, false,
            queue, evaluator, validator,
            10, 60, 120,
            flightPlan, true, null
        );

        // Evaluador SEPARADO para el recálculo de referencia: misma matemática
        // (SolutionEvaluator), pero SIN estado compartido con el que usa Scheduler
        // internamente — así el recálculo desde cero no se contamina con la línea base que
        // Scheduler activa/desactiva durante la optimización de cada ciclo.
        SolutionEvaluator referenceEvaluator = new SolutionEvaluator(flightPlan, airports);

        ZonedDateTime currentTime = t0;
        for (int cycle = 0; cycle < cycles; cycle++) {
            Solution accumulated = scheduler.executePlanningCycle(currentTime);

            double incrementalFitness = accumulated.getFitness();
            // Recálculo desde cero de TODOS los términos EXCEPTO el premio por vuelos no
            // usados: ese término se quitó a propósito del fitness acumulado que reporta
            // Scheduler (requiere 2-3 recorridos más de toda la solución para decidir si la
            // red está saturada — exactamente el costo que este fix elimina) — ya se sigue
            // aplicando donde importa de verdad, dentro del evaluador propio de GA/Tabú
            // durante la búsqueda. Ver conversación de diseño.
            double fullRecomputeFitness =
                referenceEvaluator.calculateFlightCapacityPenalties(accumulated)
                + referenceEvaluator.calculateStorageCapacityPenalties(accumulated)
                + referenceEvaluator.calculateSLAPenalties(accumulated)
                + referenceEvaluator.calculateLayoverPenalties(accumulated)
                - referenceEvaluator.calculateTimeSlackRewards(accumulated);

            assertEquals(fullRecomputeFitness, incrementalFitness, 1e-6,
                "Ciclo " + (cycle + 1) + ": el fitness incremental debe coincidir con el "
                    + "recálculo completo desde cero (salvo el premio de vuelos no usados, "
                    + "excluido a propósito) sobre la misma solución acumulada");

            currentTime = currentTime.plusHours(2);
        }
    }
}
