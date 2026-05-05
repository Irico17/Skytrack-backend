# Entidades JPA Faltantes para Persistencia Completa

## 📋 Resumen

Este documento contiene las entidades JPA que faltan para persistir lotes de envíos y rutas asignadas en la base de datos.

---

## 1. ShipmentBatchEntity

```java
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
```

---

## 2. AssignedRouteEntity

```java
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
            0.0  // totalCost puede calcularse si tienes lógica de costos
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
```

---

## 3. RouteFlightSegmentEntity

```java
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
```

---

## 4. Actualizar SimulationEntity

Agregar campos para estadísticas de lotes:

```java
// Agregar estos campos a SimulationEntity existente:

@Column(name = "algorithm_type", length = 20)
private String algorithmType;  // GATS o TABU_PURE

@Column(name = "total_batches")
private int totalBatches;

@Column(name = "routed_batches")
private int routedBatches;

@Column(name = "unroutable_batches")
private int unroutableBatches;

// Agregar getters y setters
public String getAlgorithmType() { return algorithmType; }
public void setAlgorithmType(String algorithmType) { this.algorithmType = algorithmType; }
public int getTotalBatches() { return totalBatches; }
public void setTotalBatches(int totalBatches) { this.totalBatches = totalBatches; }
public int getRoutedBatches() { return routedBatches; }
public void setRoutedBatches(int routedBatches) { this.routedBatches = routedBatches; }
public int getUnroutableBatches() { return unroutableBatches; }
public void setUnroutableBatches(int unroutableBatches) { this.unroutableBatches = unroutableBatches; }
```

---

## 5. Repositorios JPA

```java
package com.equipo2b.scheduler.persistence.repository;

import com.equipo2b.scheduler.persistence.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface ShipmentBatchRepository extends JpaRepository<ShipmentBatchEntity, Long> {
    List<ShipmentBatchEntity> findBySimulationId(String simulationId);
    List<ShipmentBatchEntity> findBySimulationIdAndRouted(String simulationId, boolean routed);
    long countBySimulationId(String simulationId);
    long countBySimulationIdAndRouted(String simulationId, boolean routed);
    
    @Query("SELECT sb FROM ShipmentBatchEntity sb WHERE sb.simulationId = :simId AND sb.originId = :airportId")
    List<ShipmentBatchEntity> findBySimulationAndOrigin(@Param("simId") String simId, 
                                                         @Param("airportId") String airportId);
}

public interface AssignedRouteRepository extends JpaRepository<AssignedRouteEntity, Long> {
    List<AssignedRouteEntity> findBySimulationId(String simulationId);
    Optional<AssignedRouteEntity> findByBatchId(Long batchId);
    List<AssignedRouteEntity> findBySimulationIdAndMeetsSla(String simulationId, boolean meetsSla);
    
    @Query("SELECT AVG(ar.slackHours) FROM AssignedRouteEntity ar WHERE ar.simulationId = :simId")
    Double getAverageSlackHours(@Param("simId") String simId);
    
    @Query("SELECT ar FROM AssignedRouteEntity ar WHERE ar.simulationId = :simId ORDER BY ar.stopCount DESC")
    List<AssignedRouteEntity> findRoutesWithMostStops(@Param("simId") String simId, Pageable pageable);
}

public interface RouteFlightSegmentRepository extends JpaRepository<RouteFlightSegmentEntity, Long> {
    List<RouteFlightSegmentEntity> findByRouteIdOrderBySegmentOrder(Long routeId);
    
    @Query("SELECT rfs.flightId, COUNT(rfs) as usage FROM RouteFlightSegmentEntity rfs " +
           "JOIN AssignedRouteEntity ar ON rfs.routeId = ar.id " +
           "WHERE ar.simulationId = :simId " +
           "GROUP BY rfs.flightId ORDER BY usage DESC")
    List<Object[]> findMostUsedFlights(@Param("simId") String simId, Pageable pageable);
}
```

---

## 6. Servicio de Persistencia

```java
package com.equipo2b.scheduler.persistence.service;

import com.equipo2b.scheduler.model.*;
import com.equipo2b.scheduler.persistence.entity.*;
import com.equipo2b.scheduler.persistence.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

@Service
public class SolutionPersistenceService {
    
    private final ShipmentBatchRepository batchRepository;
    private final AssignedRouteRepository routeRepository;
    private final RouteFlightSegmentRepository segmentRepository;
    
    public SolutionPersistenceService(ShipmentBatchRepository batchRepository,
                                     AssignedRouteRepository routeRepository,
                                     RouteFlightSegmentRepository segmentRepository) {
        this.batchRepository = batchRepository;
        this.routeRepository = routeRepository;
        this.segmentRepository = segmentRepository;
    }
    
    /**
     * Persiste una solución completa con todos sus lotes y rutas.
     */
    @Transactional
    public void persistSolution(String simulationId, Solution solution, 
                               List<ShipmentBatch> allBatches) {
        // 1. Persistir todos los lotes
        for (ShipmentBatch batch : allBatches) {
            boolean hasRoute = solution.getRoute(batch.batchId()) != null;
            ShipmentBatchEntity batchEntity = ShipmentBatchEntity.from(
                simulationId, batch, hasRoute
            );
            batchRepository.save(batchEntity);
        }
        
        // 1.5. Construir mapa para lookup O(1)
        java.util.Map<String, Long> batchIdToEntityId = batchRepository
            .findBySimulationId(simulationId)
            .stream()
            .collect(java.util.stream.Collectors.toMap(
                ShipmentBatchEntity::getBatchId, 
                ShipmentBatchEntity::getId
            ));
        
        // 2. Persistir rutas asignadas y sus segmentos
        for (AssignedRoute route : solution.getRoutes().values()) {
            Long batchEntityId = batchIdToEntityId.get(route.getBatch().batchId());
            
            // Crear ruta asignada
            AssignedRouteEntity routeEntity = AssignedRouteEntity.from(
                simulationId, batchEntityId, route
            );
            routeEntity = routeRepository.save(routeEntity);
            
            // Crear segmentos de vuelo
            List<Flight> flights = route.getFlights();
            for (int i = 0; i < flights.size(); i++) {
                Flight flight = flights.get(i);
                
                // Calcular tiempo de escala
                int layoverMinutes = 0;
                if (i < flights.size() - 1) {
                    Flight nextFlight = flights.get(i + 1);
                    layoverMinutes = (int) Duration.between(
                        flight.arrivalTime(),
                        nextFlight.departureTime()
                    ).toMinutes();
                }
                
                RouteFlightSegmentEntity segmentEntity = RouteFlightSegmentEntity.from(
                    routeEntity.getId(), flight, i + 1, layoverMinutes
                );
                segmentRepository.save(segmentEntity);
            }
        }
    }
}
```

---

## 7. Uso en SimulationService

```java
@Service
public class SimulationService {
    
    private final SolutionPersistenceService persistenceService;
    private final SimulationRepository simulationRepository;
    
    // ... otros campos ...
    
    public String startSimulation(String scenarioName, String algorithmType) {
        // ... código existente ...
        
        // Ejecutar simulación
        Solution solution = scheduler.run(startTime, maxCycles);
        
        // Persistir solución completa
        persistenceService.persistSolution(simulationId, solution, allBatches);
        
        // Actualizar estadísticas de simulación
        SimulationEntity simEntity = simulationRepository.findById(simulationId).orElseThrow();
        simEntity.setTotalBatches(allBatches.size());
        simEntity.setRoutedBatches(solution.getRoutes().size());
        simEntity.setUnroutableBatches(solution.getUnroutableBatches().size());
        simEntity.setAlgorithmType(algorithmType);
        simulationRepository.save(simEntity);
        
        return simulationId;
    }
}
```

---

**Fecha**: 2026-04-21  
**Versión**: 1.0  
**Estado**: Listo para implementar
