package com.equipo2b.scheduler.persistence.service;

import com.equipo2b.scheduler.model.*;
import com.equipo2b.scheduler.persistence.entity.*;
import com.equipo2b.scheduler.persistence.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
        if (solution == null || allBatches == null || allBatches.isEmpty()) {
            return;
        }

        // 1. Persistir todos los lotes
        List<ShipmentBatchEntity> batchEntities = new ArrayList<>();
        for (ShipmentBatch batch : allBatches) {
            boolean hasRoute = solution.getRoute(batch.batchId()) != null;
            ShipmentBatchEntity batchEntity = ShipmentBatchEntity.from(
                simulationId, batch, hasRoute
            );
            batchEntities.add(batchEntity);
        }
        
        List<ShipmentBatchEntity> savedBatchEntities = batchRepository.saveAll(batchEntities);

        // 1.5. Construir mapa para lookup O(1) de batchId a entity ID
        Map<String, Long> batchIdToEntityId = savedBatchEntities
            .stream()
            .collect(Collectors.toMap(
                ShipmentBatchEntity::getBatchId, 
                ShipmentBatchEntity::getId
            ));
        
        // 2. Persistir rutas asignadas y sus segmentos
        List<RouteFlightSegmentEntity> segmentEntities = new ArrayList<>();
        for (AssignedRoute route : solution.getRoutes().values()) {
            // Buscar el ID del entity persistido en O(1)
            Long batchEntityId = batchIdToEntityId.get(route.getBatch().batchId());
            if (batchEntityId == null) {
                continue; // Seguridad por si hay rutas huérfanas
            }
            
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
                segmentEntities.add(segmentEntity);
            }
        }

        segmentRepository.saveAll(segmentEntities);
    }
}
