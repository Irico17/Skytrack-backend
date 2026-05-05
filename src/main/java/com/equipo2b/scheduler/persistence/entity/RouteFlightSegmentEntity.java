package com.equipo2b.scheduler.persistence.entity;

import jakarta.persistence.*;
import java.time.ZonedDateTime;

/**
 * Entidad JPA para persistir segmentos de vuelo que componen una ruta.
 */
@Entity
@Table(name = "route_flight_segments")
public class RouteFlightSegmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "route_id", nullable = false)
    private Long routeId;

    @Column(name = "flight_id", length = 50, nullable = false)
    private String flightId;

    @Column(name = "segment_order", nullable = false)
    private int segmentOrder;

    @Column(name = "departure_time", nullable = false)
    private ZonedDateTime departureTime;

    @Column(name = "arrival_time", nullable = false)
    private ZonedDateTime arrivalTime;

    @Column(name = "layover_minutes")
    private int layoverMinutes;

    protected RouteFlightSegmentEntity() {}

    public RouteFlightSegmentEntity(Long routeId, String flightId, int segmentOrder,
                                    ZonedDateTime departureTime, ZonedDateTime arrivalTime,
                                    int layoverMinutes) {
        this.routeId = routeId;
        this.flightId = flightId;
        this.segmentOrder = segmentOrder;
        this.departureTime = departureTime;
        this.arrivalTime = arrivalTime;
        this.layoverMinutes = layoverMinutes;
    }

    /** Crea entity desde un vuelo en una ruta */
    public static RouteFlightSegmentEntity from(Long routeId, 
                                               com.equipo2b.scheduler.model.Flight flight,
                                               int segmentOrder,
                                               int layoverMinutes) {
        return new RouteFlightSegmentEntity(
            routeId,
            flight.flightId(),
            segmentOrder,
            flight.departureTime(),
            flight.arrivalTime(),
            layoverMinutes
        );
    }

    // Getters
    public Long getId() { return id; }
    public Long getRouteId() { return routeId; }
    public String getFlightId() { return flightId; }
    public int getSegmentOrder() { return segmentOrder; }
    public ZonedDateTime getDepartureTime() { return departureTime; }
    public ZonedDateTime getArrivalTime() { return arrivalTime; }
    public int getLayoverMinutes() { return layoverMinutes; }
}
