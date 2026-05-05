package com.equipo2b.scheduler.service;

import com.equipo2b.scheduler.api.dto.*;
import com.equipo2b.scheduler.api.websocket.SimulationWebSocketHandler;
import com.equipo2b.scheduler.execution.*;
import com.equipo2b.scheduler.model.*;
import com.equipo2b.scheduler.monitoring.*;
import com.equipo2b.scheduler.validation.RouteValidator;
import com.equipo2b.scheduler.persistence.service.SolutionPersistenceService;
import com.equipo2b.scheduler.persistence.repository.SimulationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Servicio principal que orquesta simulaciones logísticas.
 * Conecta DataLoadingService con SimulationController y expone el estado
 * para los REST controllers y el WebSocket.
 */
@Service
public class SimulationService implements SimulationController.SimulationListener {

    @Autowired
    private DataLoadingService dataService;

    @Autowired
    private SolutionPersistenceService persistenceService;

    @Autowired
    private SimulationRepository simulationRepository;

    @Autowired(required = false)
    private SimulationWebSocketHandler webSocketHandler;

    // Componentes en memoria — se recrean al iniciar cada simulación
    private SimulationController activeController;
    private FlightPlan currentFlightPlan;
    private AirportManager currentAirportManager;
    private ShipmentQueue currentQueue;
    private CapacityMonitor currentCapacityMonitor;
    private TrafficLightIndicator trafficLight;
    private ScenarioType currentScenario;

    /**
     * Inicia una nueva simulación del escenario indicado.
     *
     * @param scenarioName "DAY_TO_DAY", "PERIOD_SIMULATION" o "COLLAPSE_SIMULATION"
     * @return simulationId único de la simulación iniciada
     */
    public synchronized String startSimulation(String scenarioName) {
        // Detener simulación activa si existe
        if (activeController != null && activeController.isRunning()) {
            activeController.stopSimulation();
        }

        ScenarioType scenario = ScenarioType.valueOf(scenarioName);
        currentScenario = scenario;

        try {
            System.out.println("🚀 Iniciando simulación: " + scenario.getDescription());

            // 1. Cargar datos base
            List<Airport> airports = dataService.loadAirports();
            currentAirportManager = dataService.createAirportManager(airports);
            ClientRegistry clientRegistry = dataService.createClientRegistry(airports);
            currentFlightPlan = dataService.loadFlightPlan(currentAirportManager);
            List<ShipmentBatch> historicalBatches = dataService.loadAllShipments(currentAirportManager, clientRegistry);

            // 2. Construir cola de envíos (SimulationController maneja la generación futura)
            currentQueue = dataService.buildQueue(historicalBatches);

            // 3. Crear monitores
            currentCapacityMonitor = new CapacityMonitor(currentFlightPlan, currentAirportManager);
            trafficLight = new TrafficLightIndicator();

            // 4. Crear controlador y iniciar simulación
            // startSimulation() internamente llama prepareData() para el escenario correcto
            activeController = new SimulationController(
                currentFlightPlan, currentAirportManager, clientRegistry
            );

            activeController.startSimulation(scenario, historicalBatches);

            // Escuchar eventos de la simulación
            activeController.setListener(this);

            // Registrar WebSocket
            String simId = activeController.getSimulationId();
            if (webSocketHandler != null) {
                webSocketHandler.setActiveSimId(simId);
            }


            System.out.println("✓ Simulación iniciada: " + activeController.getSimulationId());
            return activeController.getSimulationId();

        } catch (Exception e) {
            throw new RuntimeException("Error iniciando simulación: " + e.getMessage(), e);
        }
    }

    /** Para la simulación activa. */
    public void stopSimulation(String simId) {
        validateSimId(simId);
        activeController.stopSimulation();
    }

    /** Pausa la simulación activa. */
    public void pauseSimulation(String simId) {
        validateSimId(simId);
        activeController.pauseSimulation();
    }

    /** Reanuda la simulación activa. */
    public void resumeSimulation(String simId) {
        validateSimId(simId);
        activeController.resumeSimulation();
    }

    /** Retorna el estado actual como DTO. */
    public SimulationStatusDTO getStatus(String simId) {
        validateSimId(simId);
        SimulationStatus status = activeController.getStatus();
        int pending = currentQueue != null ? currentQueue.getPendingCount() : 0;

        TrafficLightReport trafficReport = null;
        if (currentCapacityMonitor != null) {
            Solution sol = getCurrentSolution();
            if (sol != null && !sol.getRoutes().isEmpty()) {
                trafficReport = trafficLight.generateReport(sol, currentCapacityMonitor);
            }
        }

        return DTOMapper.toStatusDTO(simId, status, pending,
            currentScenario != null ? currentScenario.getDescription() : "N/A",
            trafficReport);
    }

    /** Retorna la solución actual serializada como DTO. */
    public SolutionDTO getSolution(String simId) {
        validateSimId(simId);
        Solution solution = getCurrentSolution();
        return DTOMapper.toSolutionDTO(solution);
    }

    /** Retorna las métricas de semáforo. */
    public SemaphoreDTO getSemaphores(String simId) {
        validateSimId(simId);
        if (currentCapacityMonitor == null) return SemaphoreDTO.unknown();

        Solution sol = getCurrentSolution();
        if (sol == null || sol.getRoutes().isEmpty()) return SemaphoreDTO.unknown();

        TrafficLightReport report = trafficLight.generateReport(sol, currentCapacityMonitor);
        return new SemaphoreDTO(
            report.flightColor().name(),
            report.storageColor().name(),
            report.slaColor().name(),
            report.flightOccupancy(),
            report.storageOccupancy(),
            report.slaCompliance()
        );
    }

    /** Registra un listener para notificaciones en tiempo real (WebSocket). */
    public void setSimulationListener(SimulationController.SimulationListener listener) {
        if (activeController != null) {
            activeController.setListener(listener);
        }
    }

    /** Retorna el controlador activo (para CancellationService). */
    public SimulationController getActiveController() {
        return activeController;
    }

    /** Retorna el FlightPlan activo (para CancellationService). */
    public FlightPlan getCurrentFlightPlan() {
        return currentFlightPlan;
    }

    /** Retorna true si hay simulación activa. */
    public boolean hasActiveSimulation(String simId) {
        return activeController != null
            && simId.equals(activeController.getSimulationId());
    }

    // ===== INTERNAL =====

    private Solution getCurrentSolution() {
        if (activeController == null) return null;
        return activeController.getCurrentSolution();
    }

    private void validateSimId(String simId) {
        if (activeController == null || !simId.equals(activeController.getSimulationId())) {
            throw new IllegalArgumentException("Simulación no encontrada: " + simId);
        }
    // ===== MÉTODOS DE SimulationListener =====
    
    @Override
    public void onCycleCompleted(SimulationStatus status, Solution solution) {
        if (webSocketHandler != null) {
            webSocketHandler.onCycleCompleted(status, solution);
        }
    }

    @Override
    public void onSimulationFinished(SimulationStatus status) {
        if (webSocketHandler != null) {
            webSocketHandler.onSimulationFinished(status);
        }
        
        // Ejecutar persistencia
        if (activeController != null) {
            String simId = activeController.getSimulationId();
            Solution solution = activeController.getCurrentSolution();
            List<ShipmentBatch> batches = activeController.getCurrentBatches();
            String algoType = activeController.getAlgorithmType();
            
            try {
                persistenceService.persistSolution(simId, solution, batches);
                
                // Actualizar estadísticas de simulación
                var simOpt = simulationRepository.findById(simId);
                if (simOpt.isPresent()) {
                    var simEntity = simOpt.get();
                    simEntity.setTotalBatches(batches.size());
                    simEntity.setRoutedBatches(solution.getRoutes().size());
                    simEntity.setUnroutableBatches(solution.getUnroutableBatches().size());
                    simEntity.setAlgorithmType(algoType);
                    simulationRepository.save(simEntity);
                    System.out.println("✓ Persistencia de simulación completada: " + simId);
                }
            } catch(Exception e) {
                System.err.println("❌ Error persistiendo la simulación: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
}
