package com.equipo2b.scheduler.model;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Gestiona el conjunto de aeropuertos del sistema.
 * Facilita la búsqueda por ID para validaciones y cálculos de ruta.
 */

public class AirportManager {
    private final Map<String, Airport> airports; // Key = ID (SKBO), Value = Airport (for fast search)

    public AirportManager(List<Airport> airportList) {
        Map<String, Airport> airportMap = new HashMap<>();
        for (Airport a : airportList) {
            airportMap.put(a.getId(), a);
        }

        this.airports = airportMap;
    }

    public AirportManager(){
        this.airports = new HashMap<>();
    }

    public void addAirport(Airport airport) {
        if (airport != null && airport.getId() != null) {
            airports.put(airport.getId(), airport);
        }
    }

    /**
     * Busca un aeropuerto por su código AITA.
     * @return El objeto Airport o null si no existe.
     */
    public Airport getAirport(String id) {
        return airports.get(id);
    }

    /**
     * Verifica si un aeropuerto existe en el sistema.
     */
    public boolean exists(String id) {
        return airports.containsKey(id);
    }

    /**
     * Retorna todos los aeropuertos cargados.
     */
    public Collection<Airport> getAllAirports() {
        return airports.values();
    }

    public int getCount() {
        return airports.size();
    }
}
