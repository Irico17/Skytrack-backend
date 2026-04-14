package com.equipo2b.scheduler.model;
import java.util.ArrayList;
import java.util.List;

/**
 * Contiene todos los vuelos programados para el día.
 */
public class FlightPlan {
    private final ArrayList<Flight> flights;

    public FlightPlan(){
        this.flights = new ArrayList<>();
    }

    public FlightPlan(List<Flight> flights) {
        this.flights = new ArrayList<>();
        for (Flight f : flights) { // Deep copy of each flight
            this.flights.add(new Flight(
                    f.getOrigin(),
                    f.getDestination(),
                    f.getDepartureTime(),
                    f.getArrivalTime(),
                    f.getCapacity()));
        }
    }

    public void addFlight(Flight flight){
        this.flights.add(flight);
    }

    public ArrayList<Flight> getFlights(){
        ArrayList<Flight> newFlights = new ArrayList<Flight>();
        for (Flight f : flights){
            newFlights.add(new Flight(
                    f.getOrigin(),
                    f.getDestination(),
                    f.getDepartureTime(),
                    f.getArrivalTime(),
                    f.getCapacity()));
        }
        return newFlights;
    }

    public int getTotalFlights(){
        return this.flights.size();
    }
}
