package com.equipo2b.scheduler.persistence.entity;

import jakarta.persistence.*;
import java.time.ZonedDateTime;

/**
 * Entidad JPA para persistir el plan de vuelos base en MySQL.
 */
@Entity
@Table(name = "flights")
public class FlightEntity {

    @Id
    @Column(name = "flight_id", length = 50)
    private String flightId;

    @Column(name = "origin_id", length = 10)
    private String originId;

    @Column(name = "destination_id", length = 10)
    private String destinationId;

    @Column(name = "departure_time")
    private ZonedDateTime departureTime;

    @Column(name = "arrival_time")
    private ZonedDateTime arrivalTime;

    @Column
    private int capacity;

    @Column(name = "flight_type", length = 20)
    private String flightType;

    protected FlightEntity() {}

    public FlightEntity(String flightId, String originId, String destinationId,
                        ZonedDateTime departureTime, ZonedDateTime arrivalTime,
                        int capacity, String flightType) {
        this.flightId = flightId;
        this.originId = originId;
        this.destinationId = destinationId;
        this.departureTime = departureTime;
        this.arrivalTime = arrivalTime;
        this.capacity = capacity;
        this.flightType = flightType;
    }

    /** Crea entity desde el objeto de dominio Flight */
    public static FlightEntity from(com.equipo2b.scheduler.model.Flight flight) {
        return new FlightEntity(
            flight.flightId(),
            flight.origin().id(),
            flight.destination().id(),
            flight.departureTime(),
            flight.arrivalTime(),
            flight.capacity(),
            flight.type().name()
        );
    }

    // Getters
    public String getFlightId() { return flightId; }
    public String getOriginId() { return originId; }
    public String getDestinationId() { return destinationId; }
    public ZonedDateTime getDepartureTime() { return departureTime; }
    public ZonedDateTime getArrivalTime() { return arrivalTime; }
    public int getCapacity() { return capacity; }
    public String getFlightType() { return flightType; }
}
