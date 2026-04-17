package com.equipo2b.scheduler.model;

import java.time.ZonedDateTime;
import java.util.*;

/**
 * Gestiona el plan maestro de vuelos.
 * Cargado una vez al inicio, mantenido en memoria durante toda la ejecución.
 * 
 * Proporciona:
 * - Almacenamiento de todos los vuelos programados
 * - Índice por aeropuerto origen para búsqueda eficiente O(1)
 * - Consulta de vuelos por aeropuerto y ventana temporal
 * 
 * **Validates: Requirements 28.5, 19.3**
 */
public class FlightPlan {
    private final List<Flight> allFlights;
    private final Map<String, List<Flight>> flightsByOrigin;  // Índice para búsqueda rápida por Airport ID

    /**
     * Constructor vacío que inicializa estructuras de datos.
     */
    public FlightPlan() {
        this.allFlights = new ArrayList<>();
        this.flightsByOrigin = new HashMap<>();
    }

    /**
     * Constructor que inicializa con una lista de vuelos.
     * Crea índice por aeropuerto origen para búsqueda eficiente.
     * 
     * @param flights Lista de vuelos a cargar
     */
    public FlightPlan(List<Flight> flights) {
        this.allFlights = new ArrayList<>(flights);
        this.flightsByOrigin = indexFlightsByOrigin(flights);
    }

    /**
     * Agrega un vuelo al plan y actualiza el índice por origen.
     * 
     * @param flight Vuelo a agregar
     */
    public void addFlight(Flight flight) {
        allFlights.add(flight);
        flightsByOrigin.computeIfAbsent(flight.origin().id(), k -> new ArrayList<>())
                       .add(flight);
    }

    /**
     * Crea índice de vuelos por aeropuerto origen.
     * Permite búsqueda O(1) de vuelos desde un aeropuerto específico.
     * 
     * @param flights Lista de vuelos a indexar
     * @return Mapa de Airport ID a lista de vuelos
     */
    private Map<String, List<Flight>> indexFlightsByOrigin(List<Flight> flights) {
        Map<String, List<Flight>> index = new HashMap<>();
        for (Flight flight : flights) {
            index.computeIfAbsent(flight.origin().id(), k -> new ArrayList<>())
                 .add(flight);
        }
        return index;
    }

    /**
     * Obtiene vuelos desde un aeropuerto en una ventana temporal.
     * Usado para búsqueda de rutas y replanificación de emergencia.
     * 
     * IMPORTANTE: Los vuelos tienen fechas base (2026-01-01), pero representan
     * un horario recurrente diario. Este método ajusta las fechas de los vuelos
     * para que coincidan con la ventana temporal solicitada.
     * 
     * @param origin Aeropuerto origen
     * @param start Inicio de ventana temporal (inclusive)
     * @param end Fin de ventana temporal (inclusive)
     * @return Lista de vuelos que salen del aeropuerto en la ventana temporal
     */
    public List<Flight> getFlightsFromAirport(Airport origin, 
                                              ZonedDateTime start, 
                                              ZonedDateTime end) {
        List<Flight> baseFlights = flightsByOrigin.getOrDefault(origin.id(), Collections.emptyList());
        List<Flight> adjustedFlights = new ArrayList<>();
        
        // Calcular cuántos días necesitamos proyectar los vuelos
        long daysDiff = java.time.temporal.ChronoUnit.DAYS.between(
            baseFlights.isEmpty() ? start : baseFlights.get(0).departureTime().toLocalDate(),
            start.toLocalDate()
        );
        
        // Para cada vuelo base, crear instancias ajustadas que caigan en la ventana temporal
        for (Flight baseFlight : baseFlights) {
            // Proyectar el vuelo al día de inicio y días siguientes si es necesario
            for (long dayOffset = daysDiff; dayOffset <= daysDiff + 7; dayOffset++) {
                ZonedDateTime adjustedDeparture = baseFlight.departureTime().plusDays(dayOffset);
                ZonedDateTime adjustedArrival = baseFlight.arrivalTime().plusDays(dayOffset);
                
                // Verificar si este vuelo ajustado cae en la ventana temporal
                if (!adjustedDeparture.isBefore(start) && !adjustedDeparture.isAfter(end)) {
                    // Crear un nuevo vuelo con las fechas ajustadas
                    Flight adjustedFlight = new Flight(
                        baseFlight.flightId() + "-D" + dayOffset,
                        baseFlight.origin(),
                        baseFlight.destination(),
                        adjustedDeparture,
                        adjustedArrival,
                        baseFlight.capacity(),
                        baseFlight.type()
                    );
                    adjustedFlights.add(adjustedFlight);
                }
            }
        }
        
        return adjustedFlights;
    }

    /**
     * Obtiene todos los vuelos del plan.
     * 
     * @return Lista inmutable de todos los vuelos
     */
    public List<Flight> getAllFlights() {
        return Collections.unmodifiableList(allFlights);
    }

    /**
     * Obtiene el número total de vuelos en el plan.
     * 
     * @return Cantidad de vuelos
     */
    public int getTotalFlights() {
        return allFlights.size();
    }
}
