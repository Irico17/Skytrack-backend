package com.equipo2b.scheduler.persistence.repository;

import com.equipo2b.scheduler.persistence.entity.AssignedRouteEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface AssignedRouteRepository extends JpaRepository<AssignedRouteEntity, Long> {
    List<AssignedRouteEntity> findBySimulationId(String simulationId);
    Optional<AssignedRouteEntity> findByBatchId(Long batchId);
    List<AssignedRouteEntity> findBySimulationIdAndMeetsSla(String simulationId, boolean meetsSla);
    
    @Query("SELECT AVG(ar.slackHours) FROM AssignedRouteEntity ar WHERE ar.simulationId = :simId")
    Double getAverageSlackHours(@Param("simId") String simId);
    
    @Query("SELECT ar FROM AssignedRouteEntity ar WHERE ar.simulationId = :simId ORDER BY ar.stopCount DESC")
    List<AssignedRouteEntity> findRoutesWithMostStops(@Param("simId") String simId, Pageable pageable);
}
