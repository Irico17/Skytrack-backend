package com.equipo2b.scheduler.persistence.repository;

import com.equipo2b.scheduler.persistence.entity.FlightCancellationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FlightCancellationRepository extends JpaRepository<FlightCancellationEntity, Long> {
    List<FlightCancellationEntity> findBySimulationId(String simulationId);
}
