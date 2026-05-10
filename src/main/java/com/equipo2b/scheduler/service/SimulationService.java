package com.equipo2b.scheduler.service;

import com.equipo2b.scheduler.api.dto.*;
import com.equipo2b.scheduler.api.websocket.SimulationWebSocketHandler;
import com.equipo2b.scheduler.execution.*;
import com.equipo2b.scheduler.model.*;
import com.equipo2b.scheduler.monitoring.*;
import com.equipo2b.scheduler.persistence.repository.SimulationRepository;
import com.equipo2b.scheduler.persistence.service.SolutionPersistenceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
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

    @Autowired
    private SimulationResultExporter resultExporter;

    @Autowired(required = false)
    private SimulationWebSocketHandler webSocketHandler;

    // Componentes en memoria — se recrean al iniciar cada simulación
    private SimulationController activeController;
    private FlightPlan currentFlightPlan;
    private AirportManager currentAirportManager;
    private ShipmentQueue currentQueue;
    private CapacityMonitor currentCapacityMonitor;
    private StorageInventoryService currentStorageInventoryService;
    private TrafficLightIndicator trafficLight;
    private ScenarioType currentScenario;
    private ZonedDateTime currentStartDate;

    /**
     * Inicia una nueva simulación del escenario indicado.
     *
     * @param scenarioName "DAY_TO_DAY", "PERIOD_SIMULATION" o "COLLAPSE_SIMULATION"
     * @param startDateStr Fecha de inicio del rango de datos (yyyy-MM-dd). Null = todos los datos.
     *                     Para PERIOD_SIMULATION filtra [startDate, startDate+5días].
     * @return simulationId único de la simulación iniciada
     */
    public synchronized String startSimulation(String scenarioName, String startDateStr) {
        // Detener simulación activa si existe
        if (activeController != null && activeController.isRunning()) {
            activeController.stopSimulation();
        }

        ScenarioType scenario = ScenarioType.valueOf(scenarioName);
        currentScenario = scenario;

        // Parsear fecha de inicio
        currentStartDate = parseStartDate(startDateStr);

        try {
            System.out.println("🚀 Iniciando simulación: " + scenario.getDescription());
            if (currentStartDate != null) {
                System.out.println("   Ventana: " + currentStartDate.toLocalDate()
                    + " → " + currentStartDate.plusDays(5).toLocalDate());
            }

            // 1. Cargar datos base
            List<Airport> airports = dataService.loadAirports();
            currentAirportManager = dataService.createAirportManager(airports);
            ClientRegistry clientRegistry = dataService.createClientRegistry(airports);
            currentFlightPlan = dataService.loadFlightPlan(currentAirportManager);

            // 2. Cargar envíos — filtrar por rango si es simulación de 5 días
            List<ShipmentBatch> batches;
            if (scenario == ScenarioType.PERIOD_SIMULATION && currentStartDate != null) {
                ZonedDateTime endDate = currentStartDate.plusDays(5);
                batches = dataService.loadShipmentsInRange(
                    currentAirportManager, clientRegistry, currentStartDate, endDate
                );
            } else {
                batches = dataService.loadAllShipments(currentAirportManager, clientRegistry);
            }

            // 3. Construir cola
            currentQueue = dataService.buildQueue(batches);

            // 4. Crear monitores
            currentCapacityMonitor = new CapacityMonitor(currentFlightPlan, currentAirportManager);
            currentStorageInventoryService = new StorageInventoryService(currentAirportManager);
            trafficLight = new TrafficLightIndicator();

            // 5. Crear controlador
            activeController = new SimulationController(
                currentFlightPlan, currentAirportManager, clientRegistry
            );
            activeController.setStartDate(currentStartDate);

            // 6. Escuchar eventos de la simulación (this implementa SimulationListener)
            //    ANTES de startSimulation() para no perder ciclos
            activeController.setListener(this);

            // 7. Registrar WebSocket
            String simId = activeController.getSimulationId();
            if (webSocketHandler != null) {
                webSocketHandler.setActiveSimId(simId);
            }

            activeController.startSimulation(scenario, batches);

            System.out.println("✓ Simulación iniciada: " + simId);
            return simId;

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

    /** Retorna true si hay simulación activa con ese ID. */
    public boolean hasActiveSimulation(String simId) {
        return activeController != null
            && simId.equals(activeController.getSimulationId());
    }

    // ===== MÉTODOS DE SimulationListener =====

    @Override
    public void onCycleCompleted(SimulationStatus status, Solution solution) {
        // Calcular semáforos
        SemaphoreDTO semaphores = SemaphoreDTO.unknown();
        if (currentCapacityMonitor != null && !solution.getRoutes().isEmpty()) {
            TrafficLightReport report = trafficLight.generateReport(solution, currentCapacityMonitor);
            semaphores = new SemaphoreDTO(
                report.flightColor().name(),
                report.storageColor().name(),
                report.slaColor().name(),
                report.flightOccupancy(),
                report.storageOccupancy(),
                report.slaCompliance()
            );
        }

        // Calcular días transcurridos
        double daysElapsed = activeController.getDaysElapsed();

        // Calcular batch summary
        long onTime  = solution.getRoutes().values().stream().filter(AssignedRoute::meetsSLA).count();
        long delayed = solution.getRoutes().values().stream().filter(r -> !r.meetsSLA()).count();
        int  unrouted = Math.max(0, (activeController.getCurrentBatches() != null
                        ? activeController.getCurrentBatches().size() : 0)
                        - solution.getRoutes().size());

        // Construir vuelos activos para animación (deduplicado por flightId)
        java.util.Map<String, CycleUpdateDTO.ActiveFlightDTO> flightMap = new java.util.LinkedHashMap<>();
        for (AssignedRoute route : solution.getRoutes().values()) {
            for (com.equipo2b.scheduler.model.Flight flight : route.getFlights()) {
                String fid = flight.flightId();
                CycleUpdateDTO.ActiveFlightDTO existing = flightMap.get(fid);
                if (existing != null) {
                    flightMap.put(fid, new CycleUpdateDTO.ActiveFlightDTO(
                        existing.flightId(), existing.originId(), existing.destinationId(),
                        existing.departureTime(), existing.arrivalTime(),
                        existing.bagsCount() + route.getBatch().quantity(),
                        existing.meetsSla() && route.meetsSLA()
                    ));
                } else {
                    flightMap.put(fid, new CycleUpdateDTO.ActiveFlightDTO(
                        fid,
                        flight.origin().id(),
                        flight.destination().id(),
                        flight.departureTime().toString(),
                        flight.arrivalTime().toString(),
                        route.getBatch().quantity(),
                        route.meetsSLA()
                    ));
                }
            }
        }
        java.util.List<CycleUpdateDTO.ActiveFlightDTO> activeFlights =
            new java.util.ArrayList<>(flightMap.values());

        // Calcular capacidad actual de cada aeropuerto (reusable para todos los modos)
        java.util.List<CycleUpdateDTO.AirportCapacityDTO> airportCapacities =
            buildAirportCapacities(solution, status.simulatedTime());

        CycleUpdateDTO update = new CycleUpdateDTO(
            "CYCLE_UPDATE",
            activeController.getSimulationId(),
            status.currentCycle(),
            status.simulatedTime() != null ? status.simulatedTime().toString() : null,
            daysElapsed,
            false,  // simulationComplete
            status.currentFitness(),
            status.batchesProcessed(),
            status.batchesFailed(),
            solution.getRoutes().size(),
            solution.getTotalBags(),
            semaphores,
            new CycleUpdateDTO.BatchSummaryDTO((int) onTime, (int) delayed, unrouted),
            buildOperationalMetrics(solution, status.simulatedTime(), airportCapacities),
            activeFlights,
            airportCapacities
        );

        // Propagar al WebSocket
        if (webSocketHandler != null) {
            webSocketHandler.onCycleCompleted(status, solution, update);
        }
    }

    @Override
    public void onStorageUpdated(SimulationStatus status, Solution solution) {
        if (webSocketHandler == null) return;

        java.util.List<CycleUpdateDTO.AirportCapacityDTO> airportCapacities =
            buildAirportCapacities(solution, status.simulatedTime());

        StorageUpdateDTO update = new StorageUpdateDTO(
            "STORAGE_UPDATE",
            activeController.getSimulationId(),
            status.currentCycle(),
            status.simulatedTime() != null ? status.simulatedTime().toString() : null,
            activeController.getDaysElapsed(),
            airportCapacities,
            buildOperationalMetrics(solution, status.simulatedTime(), airportCapacities)
        );

        webSocketHandler.onStorageUpdated(update);
    }

    private java.util.List<CycleUpdateDTO.AirportCapacityDTO> buildAirportCapacities(
            Solution solution, ZonedDateTime simulatedTime) {
        java.util.List<CycleUpdateDTO.AirportCapacityDTO> airportCapacities = new java.util.ArrayList<>();
        if (currentStorageInventoryService == null || currentAirportManager == null) {
            return airportCapacities;
        }

        java.util.Map<com.equipo2b.scheduler.model.Airport, Integer> currentBags =
            currentStorageInventoryService.calculateCurrentBags(solution, simulatedTime);

        for (var entry : currentBags.entrySet()) {
            com.equipo2b.scheduler.model.Airport ap = entry.getKey();
            int bags = entry.getValue();
            double ratio = ap.storageCapacity() > 0 ? (double) bags / ap.storageCapacity() : 0.0;
            airportCapacities.add(new CycleUpdateDTO.AirportCapacityDTO(
                ap.id(), bags, ap.storageCapacity(), ratio
            ));
        }

        return airportCapacities;
    }

    private CycleUpdateDTO.OperationalMetricsDTO buildOperationalMetrics(
            Solution solution,
            ZonedDateTime simulatedTime,
            java.util.List<CycleUpdateDTO.AirportCapacityDTO> airportCapacities) {
        if (solution == null || simulatedTime == null || solution.getRoutes().isEmpty()) {
            return new CycleUpdateDTO.OperationalMetricsDTO(0, 0, 0, 0, 0, 0, 0, 0, null, 0.0);
        }

        int totalAssignedBags = solution.getTotalBags();
        int inFlightBags = 0;
        int deliveredBags = 0;
        int notDepartedBags = 0;
        java.util.Set<String> activeLoadedFlightIds = new java.util.HashSet<>();

        for (AssignedRoute route : solution.getRoutes().values()) {
            int quantity = route.getBatch().quantity();
            java.util.List<com.equipo2b.scheduler.model.Flight> flights = route.getFlights();
            if (flights.isEmpty()) continue;

            ZonedDateTime firstDeparture = flights.get(0).departureTime();
            if (simulatedTime.isBefore(firstDeparture)) {
                notDepartedBags += quantity;
            }

            boolean currentlyFlying = flights.stream().anyMatch(f ->
                !simulatedTime.isBefore(f.departureTime()) && simulatedTime.isBefore(f.arrivalTime())
            );
            if (currentlyFlying) {
                inFlightBags += quantity;
                flights.stream()
                    .filter(f -> !simulatedTime.isBefore(f.departureTime()) && simulatedTime.isBefore(f.arrivalTime()))
                    .map(com.equipo2b.scheduler.model.Flight::flightId)
                    .forEach(activeLoadedFlightIds::add);
            }

            if (!simulatedTime.isBefore(route.getFinalArrivalTime().plusMinutes(30))) {
                deliveredBags += quantity;
            }
        }

        int storedBags = airportCapacities.stream()
            .mapToInt(CycleUpdateDTO.AirportCapacityDTO::currentBags)
            .sum();
        int pendingDeliveryBags = Math.max(0, totalAssignedBags - deliveredBags);
        int overloadedAirports = (int) airportCapacities.stream()
            .filter(c -> c.occupancyRatio() >= 1.0)
            .count();
        CycleUpdateDTO.AirportCapacityDTO peak = airportCapacities.stream()
            .max(java.util.Comparator.comparingDouble(CycleUpdateDTO.AirportCapacityDTO::occupancyRatio))
            .orElse(null);

        return new CycleUpdateDTO.OperationalMetricsDTO(
            totalAssignedBags,
            inFlightBags,
            storedBags,
            deliveredBags,
            pendingDeliveryBags,
            notDepartedBags,
            activeLoadedFlightIds.size(),
            overloadedAirports,
            peak != null ? peak.airportId() : null,
            peak != null ? peak.occupancyRatio() : 0.0
        );
    }

    @Override
    public void onSimulationFinished(SimulationStatus status) {
        // Notificar WebSocket que terminó
        if (webSocketHandler != null) {
            webSocketHandler.onSimulationFinished(status);
        }

        if (activeController == null) return;
        String simId = activeController.getSimulationId();
        Solution solution = activeController.getCurrentSolution();
        List<ShipmentBatch> batches = activeController.getCurrentBatches();

        // Para PERIOD_SIMULATION: exportar a JSON, NO a BD
        if (currentScenario == ScenarioType.PERIOD_SIMULATION) {
            try {
                resultExporter.exportResults(
                    simId, currentScenario, currentStartDate,
                    solution,
                    batches != null ? batches.size() : 0,
                    status.currentCycle()
                );
                System.out.println("✓ Resultados de simulación 5 días exportados a archivo JSON");
            } catch (Exception e) {
                System.err.println("❌ Error exportando resultados: " + e.getMessage());
            }
        } else if (currentScenario == ScenarioType.DAY_TO_DAY) {
            // DAY_TO_DAY: persistir en BD
            try {
                persistenceService.persistSolution(simId, solution, batches);
                System.out.println("✓ Persistencia en BD completada: " + simId);
            } catch (Exception e) {
                System.err.println("❌ Error persistiendo en BD: " + e.getMessage());
                e.printStackTrace();
            }
        }
        // COLLAPSE_SIMULATION: no persistir (descartamos)
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
    }

    private ZonedDateTime parseStartDate(String startDateStr) {
        if (startDateStr == null || startDateStr.isBlank()) return null;
        try {
            LocalDate date = LocalDate.parse(startDateStr, DateTimeFormatter.ISO_LOCAL_DATE);
            return date.atStartOfDay(ZoneOffset.UTC);
        } catch (Exception e) {
            System.err.println("⚠️ No se pudo parsear startDate '" + startDateStr + "': " + e.getMessage());
            return null;
        }
    }
}
