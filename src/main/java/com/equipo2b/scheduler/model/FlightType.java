package com.equipo2b.scheduler.model;

/**
 * Representa el tipo de vuelo según los continentes de origen y destino.
 * - INTRACONTINENTAL: Vuelo entre aeropuertos del mismo continente (duración: 12 horas, capacidad: 150-250)
 * - INTERCONTINENTAL: Vuelo entre aeropuertos de diferentes continentes (duración: 24 horas, capacidad: 150-400)
 */
public enum FlightType {
    INTRACONTINENTAL,
    INTERCONTINENTAL
}
