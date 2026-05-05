package com.equipo2b.scheduler.persistence.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entidad JPA para persistir el historial de simulaciones.
 */
@Entity
@Table(name = "simulations")
public class SimulationEntity {

    @Id
    @Column(columnDefinition = "VARCHAR(36)")
    private String id;

    @Column(length = 30)
    private String scenario;

    @Column(length = 20)
    private String status;  // RUNNING, PAUSED, STOPPED, COMPLETED

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "current_cycle")
    private int currentCycle;

    @Column(name = "final_fitness")
    private double finalFitness;

    @Column(name = "sla_compliance")
    private double slaCompliance;

    @Column(name = "collapse_level", length = 20)
    private String collapseLevel;

    protected SimulationEntity() {}

    public SimulationEntity(String id, String scenario) {
        this.id = id;
        this.scenario = scenario;
        this.status = "RUNNING";
        this.startedAt = LocalDateTime.now();
    }

    // Getters & setters para actualización
    public String getId() { return id; }
    public String getScenario() { return scenario; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime t) { this.finishedAt = t; }
    public int getCurrentCycle() { return currentCycle; }
    public void setCurrentCycle(int c) { this.currentCycle = c; }
    public double getFinalFitness() { return finalFitness; }
    public void setFinalFitness(double f) { this.finalFitness = f; }
    public double getSlaCompliance() { return slaCompliance; }
    public void setSlaCompliance(double s) { this.slaCompliance = s; }
    public String getCollapseLevel() { return collapseLevel; }
    public void setCollapseLevel(String c) { this.collapseLevel = c; }
}
