package com.equipo2b.scheduler.persistence.repository;

import com.equipo2b.scheduler.persistence.entity.RouteFlightSegmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface RouteFlightSegmentRepository extends JpaRepository<RouteFlightSegmentEntity, Long> {
    List<RouteFlightSegmentEntity> findByRouteIdOrderBySegmentOrder(Long routeId);
    
    @Query("SELECT rfs.flightId, COUNT(rfs) as usage FROM RouteFlightSegmentEntity rfs " +
           "JOIN AssignedRouteEntity ar ON rfs.routeId = ar.id " +
           "WHERE ar.simulationId = :simId " +
           "GROUP BY rfs.flightId ORDER BY usage DESC")
    List<Object[]> findMostUsedFlights(@Param("simId") String simId, Pageable pageable);
}
