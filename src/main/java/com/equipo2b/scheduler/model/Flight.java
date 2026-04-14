package com.equipo2b.scheduler.model;

import java.time.LocalTime;
import java.util.Objects;

public class Flight {
    private final String airportOrigin;
    private final String airportDestination;
    private final LocalTime departureTime;
    private final LocalTime arrivalTime;
    private final int capacity;

    public Flight(String airportOrigin, String airportDestination, LocalTime departureTime, LocalTime arrivalTime, int capacity) {
        this.airportOrigin = airportOrigin;
        this.airportDestination = airportDestination;
        this.departureTime = departureTime; // In local time of airport
        this.arrivalTime = arrivalTime; // In local time of airport
        this.capacity = capacity;
    }

    public String getOrigin() { return airportOrigin; }
    public String getDestination() { return airportDestination; }
    public LocalTime getDepartureTime() { return departureTime; }
    public LocalTime getArrivalTime() { return arrivalTime; }
    public int getCapacity() { return capacity; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Flight flight = (Flight) o;
        return capacity == flight.capacity &&
                Objects.equals(airportOrigin, flight.airportOrigin) &&
                Objects.equals(airportDestination, flight.airportDestination) &&
                Objects.equals(departureTime, flight.departureTime) &&
                Objects.equals(arrivalTime, flight.arrivalTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(airportOrigin, airportDestination, departureTime, arrivalTime, capacity);
    }

    @Override
    public String toString() {
        return "Flight[" +
                "airportOrigin=" + airportOrigin +
                ", airportDestination=" + airportDestination +
                ", departureTime=" + departureTime +
                ", arrivalTime=" + arrivalTime +
                ", capacity=" + capacity +
                ']';
    }
}
