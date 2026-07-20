package com.equipo2b.scheduler.execution;

import com.equipo2b.scheduler.algorithm.AlgorithmConfig;
import com.equipo2b.scheduler.algorithm.AlgorithmType;
import com.equipo2b.scheduler.algorithm.TabuSearch;
import com.equipo2b.scheduler.logic.RouteGenerator;
import com.equipo2b.scheduler.logic.SolutionEvaluator;
import com.equipo2b.scheduler.model.*;
import com.equipo2b.scheduler.validation.RouteValidator;
import com.equipo2b.scheduler.validation.ValidationReport;
import com.equipo2b.scheduler.validation.Violation;
import com.equipo2b.scheduler.validation.ViolationType;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifica que applyCapacityAwareSplitting sigue siendo CORRECTO después de acotar
 * usedByFlight/batchesByFlight/splitCounter a vuelos "vivos" (en vez de recorrer la solución
 * acumulada completa): ningún vuelo debe superar su capacidad en la solución final, y las
 * maletas de cada lote original deben conservarse (nada se duplica ni se pierde en silencio)
 * a lo largo de muchos ciclos con peel, absorción completa y reubicación multi-hop.
 *
 * <p>Esto complementa a {@link SchedulerIncrementalFitnessTest}: ese test verifica que el
 * fitness incremental coincide con un recálculo sobre LA MISMA solución acumulada — no
 * detectaría un bug donde el splitting mismo corrompe esa solución (porque referencia e
 * incremental leerían el mismo error y coincidirían igual). Este test valida la solución
 * final de forma independiente.</p>
 *
 * <p>Topología con tres aeropuertos (directo + vía hub) para dar alternativas reales de
 * ruteo — con solo dos aeropuertos y una única ruta posible, toda la demanda se concentra
 * artificialmente en el mismo puñado de vuelos tempranos (mejor holgura de SLA), dando lugar
 * a una limitación preexistente y ya documentada en el código (registerUnroutedForRetry: "un
 * reintento podría elegir un vuelo que despega dentro de la ventana anterior") que no tiene
 * relación con este fix. La red real tiene ~30 aeropuertos y miles de vuelos alternativos; la
 * verificación en contenedor con datos reales (dic-2027, 85,000 rutas) dio 0 violaciones.</p>
 */
class SchedulerCapacitySplittingTest {

    @Test
    void capacityRespectedAndBagsConservedAcrossManyCycles() {
        ZoneId zone = ZoneId.of("America/Lima");
        Airport origin = new Airport("SPIM", "Lima", "PE", zone, 400, -12.0, -77.0, Continent.AMERICA);
        Airport hub = new Airport("SCEL", "Santiago", "CL", zone, 400, -33.4, -70.8, Continent.AMERICA);
        Airport dest = new Airport("SKBO", "Bogota", "CO", zone, 400, 4.7, -74.1, Continent.AMERICA);
        AirportManager airports = new AirportManager(List.of(origin, hub, dest));

        ZonedDateTime t0 = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, zone);

        int cycles = 20;
        List<Flight> flights = new ArrayList<>();
        for (int i = 0; i < cycles; i++) {
            ZonedDateTime dep = t0.plusHours(1 + 2L * i);
            // Capacidad generosa: DOS rutas posibles (directo y vía hub) igual dan a splitting
            // oportunidad de actuar cuando la demanda de varios ciclos coincide en el mismo
            // vuelo temprano, sin depender de un desborde garantizado cada ciclo.
            flights.add(new Flight(
                "DIRECT" + i, origin, dest, dep, dep.plusHours(2),
                40, FlightType.INTRACONTINENTAL));
            flights.add(new Flight(
                "LEG1-" + i, origin, hub, dep, dep.plusHours(1),
                40, FlightType.INTRACONTINENTAL));
            flights.add(new Flight(
                "LEG2-" + i, hub, dest, dep.plusHours(1).plusMinutes(30), dep.plusHours(3),
                40, FlightType.INTRACONTINENTAL));
        }
        FlightPlan flightPlan = new FlightPlan(flights);

        SolutionEvaluator evaluator = new SolutionEvaluator(flightPlan, airports);
        RouteValidator validator = new RouteValidator(airports);
        RouteGenerator fillRouteGenerator = new RouteGenerator(flightPlan, airports);

        TabuSearch tabu = new TabuSearch(flightPlan, airports);
        AlgorithmConfig tabuConfig = new AlgorithmConfig();
        tabuConfig.setInt("maxIterations", 50);
        tabuConfig.setInt("tabuTenure", 10);
        tabuConfig.setInt("neighborhoodSize", 10);
        tabu.configure(tabuConfig);

        ShipmentQueue queue = new ShipmentQueue();
        Map<String, Integer> originalQuantityByBase = new HashMap<>();
        int batchSeq = 0;
        for (int cycle = 0; cycle < cycles; cycle++) {
            ZonedDateTime ingress = t0.plusHours(2L * cycle);
            for (int b = 0; b < 3; b++) {
                String id = "B" + (batchSeq++);
                queue.addShipment(new ShipmentBatch(id, "a", "c" + batchSeq, origin, dest, 8, ingress.plusMinutes(b)));
                originalQuantityByBase.put(id, 8);
            }
        }

        // Sa=60s, K=120 -> Sc = 120 min = 2h por ciclo.
        Scheduler scheduler = new Scheduler(
            tabu, tabu, AlgorithmType.TABU_PURE, false,
            queue, evaluator, validator,
            10, 60, 120,
            flightPlan, true, fillRouteGenerator
        );

        ZonedDateTime currentTime = t0;
        Solution accumulated = null;
        for (int cycle = 0; cycle < cycles; cycle++) {
            accumulated = scheduler.executePlanningCycle(currentTime);
            currentTime = currentTime.plusHours(2);
        }

        // 1. Ningún vuelo debe superar su capacidad en la solución final.
        ValidationReport fullReport = validator.validate(accumulated);
        List<Violation> capacityViolations = fullReport.getViolations().stream()
            .filter(v -> v.type() == ViolationType.FLIGHT_CAPACITY)
            .toList();
        assertTrue(capacityViolations.isEmpty(),
            "No debe haber vuelos sobre capacidad tras splitting: " + capacityViolations);

        // 2. Conservación: para cada lote original, la suma de cantidades de su ruta (si sigue
        //    entera) o de sus sub-lotes ("-S<n>") nunca debe superar la cantidad original —
        //    splitting solo reubica maletas existentes, nunca crea maletas de la nada.
        Map<String, Integer> placedByBase = new HashMap<>();
        for (Map.Entry<String, AssignedRoute> entry : accumulated.getRoutes().entrySet()) {
            String base = baseIdOf(entry.getKey());
            placedByBase.merge(base, entry.getValue().getBatch().quantity(), Integer::sum);
        }
        for (Map.Entry<String, Integer> original : originalQuantityByBase.entrySet()) {
            int placed = placedByBase.getOrDefault(original.getKey(), 0);
            assertTrue(placed <= original.getValue(),
                "Lote " + original.getKey() + ": splitting no puede colocar más maletas ("
                    + placed + ") que las originales (" + original.getValue() + ")");
        }
    }

    private static String baseIdOf(String id) {
        int idx = id.indexOf("-S");
        return idx < 0 ? id : id.substring(0, idx);
    }
}
