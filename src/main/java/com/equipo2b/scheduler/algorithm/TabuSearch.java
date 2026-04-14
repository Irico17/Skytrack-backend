package com.equipo2b.scheduler.algorithm;

import com.equipo2b.scheduler.logic.*;
import com.equipo2b.scheduler.model.*;
import java.util.*;

public class TabuSearch {
    private final ProblemInstance instance;
    private final SolutionEvaluator evaluator;
    private final RouteGenerator routeGenerator;
    private final AirportManager airportManager;

    // Algorithm Hyperparameters
    private final int maxIterations = 200;
    private final int tabuTenure = 15; // Iterations in which a movement is prohibited
    private final int neighborhoodSize = 20;

    public TabuSearch(ProblemInstance instance, AirportManager am) {
        this.instance = instance;
        this.airportManager = am;
        this.evaluator = new SolutionEvaluator();
        this.routeGenerator = new RouteGenerator(instance.flightPlan().getFlights(), am);
    }

    public Solution optimize() {
        // Generate an initial solution
        Solution currentSolution = routeGenerator.generateNewSolution(instance.shipments());
        currentSolution.setFitness(evaluator.evaluate(currentSolution, airportManager));

        Solution bestSolution = new Solution(currentSolution);

        // Tabu List: Contains the id of the shipment which route that has been modified.
        // During tabuTenure, the shipment cannot be modified
        Queue<Integer> tabuList = new LinkedList<>();
        Set<Integer> tabuSet = new HashSet<>(); // shipment id set

        for (int i = 0; i < maxIterations; i++) {
            Solution bestNeighbor = null;
            int bestMoveShipmentId = -1;

            for (int n = 0; n < neighborhoodSize; n++) {  // Explore the neighborhood
                Move move = generateMove(currentSolution); // "Moves" change a single route
                Solution neighbor = move.solution(); // New solution
                neighbor.setFitness(evaluator.evaluate(neighbor, airportManager));

                // Selection: Best neighbor yet and (is not Tabu or better than best solution)
                boolean isTabu = tabuSet.contains(move.shipmentId());
                boolean satisfiesAspiration = neighbor.getFitness() < bestSolution.getFitness();

                if (bestNeighbor == null || neighbor.getFitness() < bestNeighbor.getFitness()) {
                    if (!isTabu || satisfiesAspiration) {
                        bestNeighbor = neighbor;
                        bestMoveShipmentId = move.shipmentId();
                    }
                }
            }

            // Move to best neighbor solution
            if (bestNeighbor != null) {
                currentSolution = bestNeighbor;

                // Update best solution
                if (currentSolution.getFitness() < bestSolution.getFitness()) {
                    bestSolution = new Solution(currentSolution);
                    System.out.println("Iteración " + i + " | Nuevo Récord: " + bestSolution.getFitness());
                }

                updateTabuList(tabuList, tabuSet, bestMoveShipmentId);
            }
        }

        return bestSolution;
    }

    private record Move(Solution solution, int shipmentId) {}

    private Move generateMove(Solution current) {
        Solution neighbor = new Solution(current);

        // Randomly choose a shipment (update to use maps for next iteration) !
        List<Shipment> shipments = instance.shipments();
        Shipment randomShipment = shipments.get(new Random().nextInt(shipments.size()));

        // Generate a new route for the shipment (Note. implement better route generation)
        neighbor.addRoute(routeGenerator.generateFeasibleRoute(randomShipment));

        return new Move(neighbor, randomShipment.getGlobalId());
    }

    private void updateTabuList(Queue<Integer> list, Set<Integer> set, int shipmentId) {
        list.add(shipmentId);
        set.add(shipmentId);

        if (list.size() > tabuTenure) {
            int removed = list.poll();
            set.remove(removed);
        }
    }
}