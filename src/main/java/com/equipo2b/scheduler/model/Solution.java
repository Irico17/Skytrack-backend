package com.equipo2b.scheduler.model;

import java.util.HashMap;
import java.util.Map;

public class Solution {
    private final Map<Integer, ShipmentRoute> routes; // maps shipments to their planned routes
    private double fitness;

    public Solution() {
        this.routes = new HashMap<>();
    }

    public void addRoute(ShipmentRoute route) {
        routes.put(route.getShipment().getId(), route);
    }

    public Map<Integer, ShipmentRoute> getRoutes() { return routes; }
    public double getFitness() { return fitness; }
    public void setFitness(double f) { this.fitness = f; }
}
