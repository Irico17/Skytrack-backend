package com.equipo2b.scheduler.persistence.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Entidad JPA para registrar cancelaciones de vuelo durante simulaciones.
 */
@Entity
@Table(name = "flight_cancellations")
public class FlightCancellationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "simulation_id", columnDefinition = "VARCHAR(36)")
    private String simulationId;

    @Column(name = "flight_id", length = 50)
    private String flightId;

    @Column(name = "cancelled_day")
    private LocalDate cancelledDay;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "affected_batches")
    private int affectedBatches;

    @Column(name = "replanned_batches")
    private int replannedBatches;

    protected FlightCancellationEntity() {}

    public FlightCancellationEntity(String simulationId, String flightId, LocalDate day,
                                    int affectedBatches, int replannedBatches) {
        this.simulationId = simulationId;
        this.flightId = flightId;
        this.cancelledDay = day;
        this.cancelledAt = LocalDateTime.now();
        this.affectedBatches = affectedBatches;
        this.replannedBatches = replannedBatches;
    }

    public Long getId() { return id; }
    public String getSimulationId() { return simulationId; }
    public String getFlightId() { return flightId; }
    public LocalDate getCancelledDay() { return cancelledDay; }
    public LocalDateTime getCancelledAt() { return cancelledAt; }
    public int getAffectedBatches() { return affectedBatches; }
    public int getReplannedBatches() { return replannedBatches; }
}
