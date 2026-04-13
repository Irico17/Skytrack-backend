package com.equipo2b.scheduler.model;

import java.time.LocalTime;

/**
* Representa un vuelo individual extraído de un plan de vuelos
*/

public record Flight(
        String airportOrigin,
        String airportDestination,
        LocalTime departureTime,
        LocalTime arrivalTime,
        int capacity
) { }
