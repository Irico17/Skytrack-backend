package com.equipo2b.scheduler.model;
import java.util.ArrayList;

/**
 * Contiene todos los vuelos programados para el día.
 */
public class FlightPlan {
    private final ArrayList<Flight> flights;

    public FlightPlan(){
        this.flights = new ArrayList<>();
    }

    public void addFlight(Flight flight){
        this.flights.add(flight);
    }

    public ArrayList<Flight> getFlights(){
        ArrayList<Flight> newFlights = new ArrayList<Flight>();
        for (Flight f : flights){
            newFlights.add(new Flight(
                    f.airportOrigin(),
                    f.airportDestination(),
                    f.departureTime(),
                    f.arrivalTime(),
                    f.capacity()));
        }
        return newFlights;
    }

    public int getTotalFlights(){
        return this.flights.size();
    }
}
