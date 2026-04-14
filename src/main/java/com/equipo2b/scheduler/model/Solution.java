package com.equipo2b.scheduler.model;

import java.util.HashMap;
import java.util.Map;

public class Solution {
    private final Map<Integer, ShipmentRoute> routes; // maps shipments to their planned routes by their global id
    private double fitness;

    public Solution() {
        this.routes = new HashMap<>();
    }

    // Copy constructor – creates a deep copy
    public Solution(Solution other) {
        this.routes = new HashMap<>();
        for (Map.Entry<Integer, ShipmentRoute> entry : other.routes.entrySet()) {
            this.routes.put(entry.getKey(), new ShipmentRoute(entry.getValue()));
        }
        this.fitness = other.fitness;
    }

    public void addRoute(ShipmentRoute route) {
        routes.put(route.getShipment().getGlobalId(), route);
    }

    public Map<Integer, ShipmentRoute> getRoutes() { return routes; }
    public double getFitness() { return fitness; }
    public void setFitness(double f) { this.fitness = f; }
}
