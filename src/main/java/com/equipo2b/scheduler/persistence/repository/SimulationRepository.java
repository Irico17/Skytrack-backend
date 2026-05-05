package com.equipo2b.scheduler.persistence.repository;

import com.equipo2b.scheduler.persistence.entity.SimulationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SimulationRepository extends JpaRepository<SimulationEntity, String> {}
