package com.equipo2b.scheduler.persistence.repository;

import com.equipo2b.scheduler.persistence.entity.AirportEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AirportRepository extends JpaRepository<AirportEntity, String> {}
