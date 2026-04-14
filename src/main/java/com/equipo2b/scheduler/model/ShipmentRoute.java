package com.equipo2b.scheduler.model;

import java.util.ArrayList;
import java.util.List;

public class ShipmentRoute {
    private final Shipment shipment;
    private final List<ScheduledFlight> steps;

    public ShipmentRoute(Shipment shipment) {
        this.shipment = shipment;
        this.steps = new ArrayList<>();
    }

    public ShipmentRoute(ShipmentRoute other) {
        this.shipment = other.shipment;
        this.steps = new ArrayList<>();
        for (ScheduledFlight flight : other.steps) {
            this.steps.add(new ScheduledFlight(flight));
        }
    }

    public void addStep(ScheduledFlight flight) { steps.add(flight); }
    public List<ScheduledFlight> getSteps() { return steps; }
    public Shipment getShipment() { return shipment; }
}