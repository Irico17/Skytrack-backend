package com.equipo2b.scheduler.persistence.repository;

import com.equipo2b.scheduler.persistence.entity.ShipmentBatchEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ShipmentBatchRepository extends JpaRepository<ShipmentBatchEntity, Long> {
    List<ShipmentBatchEntity> findBySimulationId(String simulationId);
    List<ShipmentBatchEntity> findBySimulationIdAndRouted(String simulationId, boolean routed);
    long countBySimulationId(String simulationId);
    long countBySimulationIdAndRouted(String simulationId, boolean routed);
    
    @Query("SELECT sb FROM ShipmentBatchEntity sb WHERE sb.simulationId = :simId AND sb.originId = :airportId")
    List<ShipmentBatchEntity> findBySimulationAndOrigin(@Param("simId") String simId, 
                                                         @Param("airportId") String airportId);
}
