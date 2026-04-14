package com.equipo2b.scheduler.model;

import java.util.LinkedList;
import java.util.List;

public class ShipmentRoute {
    private final Shipment shipment;
    private final List<ScheduledFlight> steps;

    public ShipmentRoute(Shipment shipment) {
        this.shipment = shipment;
        this.steps = new LinkedList<>(); // For later insertions/modifications
    }

    public void addStep(ScheduledFlight flight) { steps.add(flight); }
    public List<ScheduledFlight> getSteps() { return steps; }
    public Shipment getShipment() { return shipment; }
}