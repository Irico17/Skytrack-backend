package com.equipo2b.scheduler.model;

import java.util.ArrayList;

/*
* Representa una instancia del problema de rutas
* Los datos que contiene no cambian
*/
public record ProblemInstance(
        FlightPlan flightPlan,
        ArrayList<Shipment> shipments
) { }
