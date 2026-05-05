package com.equipo2b.scheduler.persistence.entity;

import jakarta.persistence.*;
import java.time.ZonedDateTime;

/**
 * Entidad JPA para persistir rutas asignadas a lotes en simulaciones.
 */
@Entity
@Table(name = "assigned_routes")
public class AssignedRouteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "simulation_id", columnDefinition = "VARCHAR(36)", nullable = false)
    private String simulationId;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "arrival_time", nullable = false)
    private ZonedDateTime arrivalTime;

    @Column(name = "total_duration_minutes")
    private int totalDurationMinutes;

    @Column(name = "stop_count")
    private int stopCount;

    @Column(name = "meets_sla", nullable = false)
    private boolean meetsSla;

    @Column(name = "slack_hours")
    private double slackHours;

    @Column(name = "total_cost")
    private double totalCost;

    @Column(name = "created_at")
    private ZonedDateTime createdAt;

    protected AssignedRouteEntity() {}

    public AssignedRouteEntity(String simulationId, Long batchId, ZonedDateTime arrivalTime,
                               int totalDurationMinutes, int stopCount, boolean meetsSla,
                               double slackHours, double totalCost) {
        this.simulationId = simulationId;
        this.batchId = batchId;
        this.arrivalTime = arrivalTime;
        this.totalDurationMinutes = totalDurationMinutes;
        this.stopCount = stopCount;
        this.meetsSla = meetsSla;
        this.slackHours = slackHours;
        this.totalCost = totalCost;
        this.createdAt = ZonedDateTime.now();
    }

    /** Crea entity desde el objeto de dominio AssignedRoute */
    public static AssignedRouteEntity from(String simulationId, Long batchId,
                                          com.equipo2b.scheduler.model.AssignedRoute route) {
        int stopCount = route.getFlights().size() - 1; // Número de escalas
        long durationMinutes = java.time.Duration.between(
            route.getFlights().get(0).departureTime(),
            route.getFinalArrivalTime()
        ).toMinutes();
        
        double slackHours = route.getSLASlack().toHours();
        
        return new AssignedRouteEntity(
            simulationId,
            batchId,
            route.getFinalArrivalTime(),
            (int) durationMinutes,
            stopCount,
            route.meetsSLA(),
            slackHours,
            0.0  // totalCost puede calcularse si hay lógica de costos
        );
    }

    // Getters
    public Long getId() { return id; }
    public String getSimulationId() { return simulationId; }
    public Long getBatchId() { return batchId; }
    public ZonedDateTime getArrivalTime() { return arrivalTime; }
    public int getTotalDurationMinutes() { return totalDurationMinutes; }
    public int getStopCount() { return stopCount; }
    public boolean isMeetsSla() { return meetsSla; }
    public double getSlackHours() { return slackHours; }
    public double getTotalCost() { return totalCost; }
    public ZonedDateTime getCreatedAt() { return createdAt; }
}
