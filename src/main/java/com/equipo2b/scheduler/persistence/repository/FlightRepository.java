package com.equipo2b.scheduler.persistence.repository;

import com.equipo2b.scheduler.persistence.entity.FlightEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FlightRepository extends JpaRepository<FlightEntity, String> {
    List<FlightEntity> findByOriginId(String originId);
}
