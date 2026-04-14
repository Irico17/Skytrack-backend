package com.equipo2b.scheduler.algorithm;

import com.equipo2b.scheduler.logic.SolutionEvaluator;
import com.equipo2b.scheduler.logic.RouteGenerator;
import com.equipo2b.scheduler.model.*;
import java.util.*;

// Genes: Shipment Routes
// Individual: Solutions

public class GeneticAlgorithm {
    private final ProblemInstance instance;
    private final SolutionEvaluator evaluator;
    private final AirportManager airportManager;

    // Algorithm hyperparameters
    private final int populationSize = 50;
    private final int generations = 100;
    private final double mutationRate = 0.1;

    public GeneticAlgorithm(ProblemInstance instance, AirportManager am) {
        this.instance = instance;
        this.evaluator = new SolutionEvaluator();
        this.airportManager = am;
    }

    public Solution optimize() {
        // Initialize Population (Random but physically feasible routes)
        List<Solution> population = initializePopulation();

        for (int g = 0; g < generations; g++) { // Generations
            // Evaluate fitness (cost) of all solutions
            for (Solution s : population) {
                s.setFitness(evaluator.evaluate(s, airportManager));
            }

            // Sort by fitness (lowest is best)
            population.sort(Comparator.comparingDouble(Solution::getFitness));
            System.out.println("Generation " + g + " - Best Fitness: " + population.get(0).getFitness());

            // Selection & Crossover
            List<Solution> nextGeneration = new ArrayList<>();
            // Keep the best (Elitism)
            nextGeneration.add(new Solution(population.get(0)));

            while (nextGeneration.size() < populationSize) {
                Solution parent1 = tournamentSelection(population);
                Solution parent2 = tournamentSelection(population);
                Solution child = crossover(parent1, parent2);

                if (Math.random() < mutationRate) {
                    mutate(child); // Alters genes!
                }
                nextGeneration.add(child);
            }
            population = nextGeneration;
        }

        population.sort(Comparator.comparingDouble(Solution::getFitness));
        return population.get(0); // return best
    }

    /**
     * Crea el conjunto inicial de soluciones candidatas.
     */
    private List<Solution> initializePopulation() {
        List<Solution> population = new ArrayList<>();

        List<Flight> flightPlan = instance.flightPlan().getFlights();
        RouteGenerator routeGenerator = new RouteGenerator(flightPlan, airportManager);

        for (int i = 0; i < populationSize; i++) {
            Solution newSolution = new Solution();

            for (Shipment shipment : instance.shipments()) {
                ShipmentRoute route = null;

                int attempts = 0;
                // Try to generate a valid route. If it fails, we retry N times before logging an error.
                while (route == null && attempts < 20) {
                    route = routeGenerator.generateFeasibleRoute(shipment);
                    attempts++;
                }

                if (route != null) {
                    newSolution.addRoute(route);
                } else {
                    // (For testing)
                    System.err.println("Could not find a feasible route for shipment: " + shipment.getId());
                }
            }
            population.add(newSolution);
        }

        System.out.println("Success: Initialized population with " + population.size() + " individuals.");
        return population;
    }

    /**
     * Selecciona una solución de la población mediante un torneo.
     * Se eligen K individuos al azar y el que tenga mejor fitness (menor valor) gana.
     */
    private Solution tournamentSelection(List<Solution> population) {
        // Tournament size (K chosen individuals)
        int tournamentSize = 4;
        Random random = new Random();

        Solution bestInTournament = null;

        for (int i = 0; i < tournamentSize; i++) {
            // Chose a random individual from population
            int randomIndex = random.nextInt(population.size());
            Solution participant = population.get(randomIndex);

            // If it is the first in the tournament or better than the current winner, update bestInTournament
            if (bestInTournament == null || participant.getFitness() < bestInTournament.getFitness()) {
                bestInTournament = participant;
            }
        }

        return bestInTournament;
    }

    /**
     * Crea una nueva solución combinando genes (rutas) de sus padres
     * Usa Uniform Crossover
     */
    private Solution crossover(Solution parent1, Solution parent2) {
        Solution child = new Solution();
        Random random = new Random();

        // Iterate through all shipments in the problem instance
        for (Shipment shipment : instance.shipments()) {
            int shipmentId = shipment.getGlobalId();

            // Get the specific route assigned to this shipment in both parents
            ShipmentRoute routeFromP1 = parent1.getRoutes().get(shipmentId);
            ShipmentRoute routeFromP2 = parent2.getRoutes().get(shipmentId);

            // Selection: 50/50 chance to take the route from Parent 1 or Parent 2
            if (random.nextBoolean()) {
                child.addRoute(new ShipmentRoute(routeFromP1));
            } else {
                child.addRoute(new ShipmentRoute(routeFromP2));
            }
        }
        return child;
    }

    /**
     * Altera aleatoriamente una Solución: Vuelve a generar su ruta desde cero.
     */
    private void mutate(Solution solution) {
        Random random = new Random();

        // What percentage of the routes (genes) will be modified
        double geneMutationRate = 0.02;

        List<Shipment> allShipments = instance.shipments();
        RouteGenerator routeGenerator = new RouteGenerator(instance.flightPlan().getFlights(), airportManager);

        for (Shipment shipment : allShipments) {
            if (random.nextDouble() < geneMutationRate) {
                ShipmentRoute newRoute = null;

                int attempts = 0;
                while (newRoute == null && attempts < 4) {
                    newRoute = routeGenerator.generateFeasibleRoute(shipment);
                    attempts++;
                }

                // If a new route is found, replace the original route
                if (newRoute != null) {
                    solution.addRoute(newRoute);
                }
            }
        }
    }
}
