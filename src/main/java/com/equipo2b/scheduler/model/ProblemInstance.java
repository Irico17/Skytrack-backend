package com.equipo2b.scheduler.model;

import java.util.ArrayList;

/*
* Representa una instancia del problema de rutas
* Los datos que contiene no cambian
*/
public record ProblemInstance(
        ArrayList<Airport> airports,
        FlightPlan flightPlan,
        ArrayList<Shipment> shipments
) { }
