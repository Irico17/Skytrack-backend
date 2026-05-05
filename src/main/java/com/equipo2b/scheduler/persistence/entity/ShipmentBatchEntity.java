package com.equipo2b.scheduler.persistence.entity;

import jakarta.persistence.*;
import java.time.ZonedDateTime;

/**
 * Entidad JPA para persistir lotes de envíos procesados en simulaciones.
 */
@Entity
@Table(name = "shipment_batches")
public class ShipmentBatchEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "simulation_id", columnDefinition = "VARCHAR(36)", nullable = false)
    private String simulationId;

    @Column(name = "batch_id", length = 50, nullable = false)
    private String batchId;

    @Column(name = "client_id", length = 20)
    private String clientId;

    @Column(name = "origin_id", length = 10, nullable = false)
    private String originId;

    @Column(name = "destination_id", length = 10, nullable = false)
    private String destinationId;

    @Column(name = "bag_count", nullable = false)
    private int bagCount;

    @Column(name = "ingress_time", nullable = false)
    private ZonedDateTime ingressTime;

    @Column(name = "deadline", nullable = false)
    private ZonedDateTime deadline;

    @Column(name = "routed", nullable = false)
    private boolean routed = false;

    @Column(name = "created_at")
    private ZonedDateTime createdAt;

    protected ShipmentBatchEntity() {}

    public ShipmentBatchEntity(String simulationId, String batchId, String clientId,
                               String originId, String destinationId, int bagCount,
                               ZonedDateTime ingressTime, ZonedDateTime deadline, boolean routed) {
        this.simulationId = simulationId;
        this.batchId = batchId;
        this.clientId = clientId;
        this.originId = originId;
        this.destinationId = destinationId;
        this.bagCount = bagCount;
        this.ingressTime = ingressTime;
        this.deadline = deadline;
        this.routed = routed;
        this.createdAt = ZonedDateTime.now();
    }

    /** Crea entity desde el objeto de dominio ShipmentBatch */
    public static ShipmentBatchEntity from(String simulationId, 
                                          com.equipo2b.scheduler.model.ShipmentBatch batch,
                                          boolean routed) {
        return new ShipmentBatchEntity(
            simulationId,
            batch.batchId(),
            batch.clientId(),
            batch.origin().id(),
            batch.destination().id(),
            batch.quantity(),
            batch.ingressTime(),
            batch.deadline(),
            routed
        );
    }

    // Getters
    public Long getId() { return id; }
    public String getSimulationId() { return simulationId; }
    public String getBatchId() { return batchId; }
    public String getClientId() { return clientId; }
    public String getOriginId() { return originId; }
    public String getDestinationId() { return destinationId; }
    public int getBagCount() { return bagCount; }
    public ZonedDateTime getIngressTime() { return ingressTime; }
    public ZonedDateTime getDeadline() { return deadline; }
    public boolean isRouted() { return routed; }
    public ZonedDateTime getCreatedAt() { return createdAt; }
}
