package com.equipo2b.scheduler.service;

import com.equipo2b.scheduler.api.dto.*;
import com.equipo2b.scheduler.api.websocket.SimulationWebSocketHandler;
import com.equipo2b.scheduler.execution.*;
import com.equipo2b.scheduler.model.*;
import com.equipo2b.scheduler.monitoring.*;
import com.equipo2b.scheduler.persistence.entity.SimulationEntity;
import com.equipo2b.scheduler.persistence.repository.SimulationRepository;
import com.equipo2b.scheduler.persistence.service.SolutionPersistenceService;
import com.equipo2b.scheduler.util.SimulationTimeParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

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
    private ZonedDateTime currentStartedAt;
    private ZonedDateTime currentFinishedAt;

    public record StartSimulationResult(String simulationId, boolean joinedExisting, ActiveSimulationDTO activeSimulation) {}

    public static class ActiveSimulationConflictException extends RuntimeException {
        private final ActiveSimulationDTO activeSimulation;

        public ActiveSimulationConflictException(String message, ActiveSimulationDTO activeSimulation) {
            super(message);
            this.activeSimulation = activeSimulation;
        }

        public ActiveSimulationDTO activeSimulation() {
            return activeSimulation;
        }
    }

    /**
     * Inicia una nueva simulación del escenario indicado.
     *
     * @param scenarioName "DAY_TO_DAY", "PERIOD_SIMULATION" o "COLLAPSE_SIMULATION"
     * @param startDateStr Instante de inicio ISO-8601 con zona horaria. Los formatos
    *                     legacy sin zona se interpretan como UTC. Null = todos los datos.
    *                     Para PERIOD_SIMULATION filtra [inicio, inicio+5días].
     * @return simulationId único de la simulación iniciada
     */
    public synchronized String startSimulation(String scenarioName, String startDateStr) {
        return startOrJoinSimulation(scenarioName, startDateStr, true).simulationId();
    }

    public synchronized StartSimulationResult startOrJoinSimulation(
            String scenarioName,
            String startDateStr,
            boolean replace) {
        ScenarioType scenario = ScenarioType.valueOf(scenarioName);
        ZonedDateTime requestedStartDate = parseStartDate(startDateStr);

        if (activeController != null && activeController.isRunning()) {
            boolean sameScenario = currentScenario == scenario;
            boolean sameStartDate = Objects.equals(currentStartDate, requestedStartDate);
            if (sameScenario && sameStartDate) {
                ActiveSimulationDTO active = buildActiveSimulationDTO();
                return new StartSimulationResult(activeController.getSimulationId(), true, active);
            }
            if (!replace) {
                ActiveSimulationDTO active = buildActiveSimulationDTO();
                throw new ActiveSimulationConflictException(
                    "Ya existe una simulacion activa con otro escenario o fecha de inicio",
                    active
                );
            }
            activeController.stopSimulation();
        }

        return startNewSimulation(scenario, requestedStartDate);
    }

    private StartSimulationResult startNewSimulation(ScenarioType scenario, ZonedDateTime requestedStartDate) {
        currentScenario = scenario;

        currentStartDate = requestedStartDate;
        currentStartedAt = ZonedDateTime.now(ZoneOffset.UTC);
        currentFinishedAt = null;

        long startupT0 = System.currentTimeMillis();
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
            System.out.printf("⏱️ [arranque] datos base listos en %d ms%n", System.currentTimeMillis() - startupT0);

            // 2. Cargar envíos — filtrar por ventana real si aplica
            List<ShipmentBatch> batches;
            if (scenario == ScenarioType.DAY_TO_DAY) {
                batches = new ArrayList<>();
            } else if ((scenario == ScenarioType.PERIOD_SIMULATION && currentStartDate != null)
                    || scenario == ScenarioType.COLLAPSE_SIMULATION) {
                // 5 días y colapso: carga incremental por bloques de fecha real (ver
                // SimulationController.setChunkLoader) en vez de precargar toda la ventana
                // de una sola vez — el ciclo 1 arranca tras cargar solo el primer bloque
                // (~1 día) y el resto se pide durante la simulación.
                batches = new ArrayList<>();
            } else {
                batches = dataService.loadAllShipments(currentAirportManager, clientRegistry);
            }

            System.out.printf("⏱️ [arranque] %,d lotes cargados en %d ms (total)%n",
                batches.size(), System.currentTimeMillis() - startupT0);

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

            if (scenario == ScenarioType.COLLAPSE_SIMULATION || scenario == ScenarioType.PERIOD_SIMULATION) {
                // Carga incremental: cada bloque usa loadShipmentsInRange sobre ventanas
                // chicas; el uploader posiciona la lectura con búsqueda binaria por fecha,
                // así cada bloque cuesta solo lo que pesa su ventana (no todo el dataset).
                AirportManager loaderAirportManager = currentAirportManager;
                ClientRegistry loaderClientRegistry = clientRegistry;
                activeController.setChunkLoader((start, end) -> {
                    try {
                        return dataService.loadShipmentsInRange(loaderAirportManager, loaderClientRegistry, start, end);
                    } catch (IOException e) {
                        System.err.println("⚠️ Error cargando bloque [" + start + " → " + end + "): " + e.getMessage());
                        return new ArrayList<>();
                    }
                });
            }

            // 6. Escuchar eventos de la simulación (this implementa SimulationListener)
            //    ANTES de startSimulation() para no perder ciclos
            activeController.setListener(this);

            // 7. Registrar WebSocket
            String simId = activeController.getSimulationId();
            if (webSocketHandler != null) {
                webSocketHandler.setActiveSimId(simId);
            }

            activeController.startSimulation(scenario, batches);

            if (webSocketHandler != null) {
                webSocketHandler.onSimulationStarted(buildActiveSimulationDTO());
            }

            System.out.printf("⏱️ [arranque] motor iniciado en %d ms; primer ciclo en curso%n",
                System.currentTimeMillis() - startupT0);
            System.out.println("✓ Simulación iniciada: " + simId);
            return new StartSimulationResult(simId, false, buildActiveSimulationDTO());

        } catch (Exception e) {
            throw new RuntimeException("Error iniciando simulación: " + e.getMessage(), e);
        }
    }

    public ActiveSimulationDTO getActiveSimulation() {
        if (activeController == null || !activeController.isRunning()) {
            return null;
        }
        return buildActiveSimulationDTO();
    }

    public SimulationSnapshotDTO getSnapshot(String simId) {
        validateSimId(simId);
        return new SimulationSnapshotDTO(buildActiveSimulationDTO(), getStatus(simId));
    }

    public OperationalStateDTO getOperationalState(String simId, int limit) {
        validateSimId(simId);
        int safeLimit = Math.max(1, Math.min(limit, 200));
        SimulationStatus status = activeController.getStatus();
        ZonedDateTime simulatedTime = status.simulatedTime();
        Solution solution = getCurrentSolution();

        return new OperationalStateDTO(
            simId,
            formatDate(simulatedTime),
            buildTransportUnits(solution, simulatedTime, safeLimit),
            buildWarehouses(solution, simulatedTime),
            buildOperationalShipments(solution, simulatedTime, safeLimit)
        );
    }

    public BagTraceabilityDTO getBagTraceability(
            String simId,
            int page,
            int size,
            String query,
            String state,
            String clientId,
            String batchId) {
        validateSimId(simId);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        SimulationStatus status = activeController.getStatus();
        return BagTraceabilityReadModel.build(
            simId,
            status.simulatedTime(),
            getCurrentSolution(),
            getCurrentBatchesSnapshot(),
            new BagTraceabilityReadModel.Query(safePage, safeSize, query, state, clientId, batchId)
        );
    }

    /** Para la simulación activa. */
    public void stopSimulation(String simId) {
        validateSimId(simId);
        ScenarioType scenarioBeforeStop = currentScenario;
        SimulationController controllerBeforeStop = activeController;
        activeController.stopSimulation();
        currentFinishedAt = ZonedDateTime.now(ZoneOffset.UTC);

        // ESC-02: al cerrar una operación día a día (detención manual) exportamos el
        // reporte JSON para que el frontend pueda mostrarlo (/results). El cierre natural
        // por fin de datos no aplica a DAY_TO_DAY, por eso se exporta aquí.
        if (scenarioBeforeStop == ScenarioType.DAY_TO_DAY && controllerBeforeStop != null) {
            try {
                resultExporter.exportResults(
                    controllerBeforeStop.getSimulationId(),
                    ScenarioType.DAY_TO_DAY,
                    currentStartDate,
                    controllerBeforeStop.getCurrentSolution(),
                    controllerBeforeStop.getCurrentBatches() != null ? controllerBeforeStop.getCurrentBatches().size() : 0,
                    controllerBeforeStop.getStatus().currentCycle(),
                    currentAirportManager,
                    controllerBeforeStop.getCurrentBatches()
                );
                System.out.println("✓ Reporte de operación día a día exportado a JSON");
            } catch (Exception e) {
                System.err.println("❌ Error exportando reporte día a día: " + e.getMessage());
            }

            // Persistencia en BD también en el CIERRE MANUAL: día a día siempre termina con
            // stop del usuario (no hay fin natural), así que sin esto la solución nunca
            // llegaba a MySQL. Async y de baja prioridad: no retrasa el reporte en la UI.
            persistDayToDayAsync(
                controllerBeforeStop.getSimulationId(),
                controllerBeforeStop.getStatus(),
                controllerBeforeStop.getCurrentSolution(),
                controllerBeforeStop.getCurrentBatches()
            );
        }
    }

    /** Persiste la solución día a día en BD en un hilo daemon de baja prioridad. */
    private void persistDayToDayAsync(
            String simId,
            SimulationStatus status,
            Solution solution,
            List<ShipmentBatch> batches) {
        if (solution == null) return;
        SimulationEntity simulationEntity = buildSimulationEntity(simId, status, solution, batches, "PERSISTING");
        Thread persister = new Thread(() -> {
            try {
                simulationRepository.save(simulationEntity);
                persistenceService.persistSolution(simId, solution, batches);
                simulationEntity.setStatus("COMPLETED");
                simulationEntity.setFinishedAt(LocalDateTime.now());
                simulationRepository.save(simulationEntity);
                System.out.println("✓ Persistencia en BD completada (async): " + simId);
            } catch (Exception e) {
                System.err.println("❌ Error persistiendo en BD: " + e.getMessage());
                e.printStackTrace();
            }
        }, "d2d-persister");
        persister.setDaemon(true);
        persister.setPriority(Thread.MIN_PRIORITY);
        persister.start();
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
        int pending = activeController != null ? activeController.getPendingCount() : 0;

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

    /** Registra un lote manual en la simulación DAY_TO_DAY activa. */
    public ShipmentDTO addShipment(String simId, ShipmentRequestDTO request) {
        validateSimId(simId);
        if (currentScenario != ScenarioType.DAY_TO_DAY) {
            throw new IllegalArgumentException("Solo DAY_TO_DAY acepta carga manual de maletas");
        }
        if (request == null) {
            throw new IllegalArgumentException("Request requerido");
        }
        if (request.originId() == null || request.originId().isBlank()) {
            throw new IllegalArgumentException("originId es requerido");
        }
        if (request.destinationId() == null || request.destinationId().isBlank()) {
            throw new IllegalArgumentException("destinationId es requerido");
        }
        if (request.originId().equals(request.destinationId())) {
            throw new IllegalArgumentException("originId y destinationId deben ser diferentes");
        }
        if (request.quantity() <= 0) {
            throw new IllegalArgumentException("quantity debe ser mayor que 0");
        }

        Airport origin = currentAirportManager.getAirport(request.originId());
        Airport destination = currentAirportManager.getAirport(request.destinationId());
        if (origin == null) {
            throw new IllegalArgumentException("Aeropuerto origen no existe: " + request.originId());
        }
        if (destination == null) {
            throw new IllegalArgumentException("Aeropuerto destino no existe: " + request.destinationId());
        }

        ZonedDateTime ingressTime = parseIngressTime(request.ingressTime());
        String batchId = "UI-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String clientId = request.clientId() != null && !request.clientId().isBlank()
            ? request.clientId()
            : "UI";

        ShipmentBatch batch = new ShipmentBatch(
            batchId,
            origin.id() + "-" + batchId,
            clientId,
            origin,
            destination,
            request.quantity(),
            ingressTime
        );

        activeController.addShipment(batch);
        return DTOMapper.toShipmentDTO(batch, "PENDING");
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

    /**
     * Huso horario del aeropuerto indicado, o null si no existe/no hay datos cargados.
     * Usado por la carga de archivo de envíos día a día: la fecha/hora de cada línea
     * (aaaammdd-hh-mm) está expresada en la hora LOCAL del aeropuerto de origen.
     */
    public java.time.ZoneId getAirportZoneId(String airportId) {
        if (currentAirportManager == null || airportId == null) {
            return null;
        }
        Airport airport = currentAirportManager.getAirport(airportId);
        return airport != null ? airport.zoneId() : null;
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
        List<ShipmentBatch> currentBatchesSnapshot = getCurrentBatchesSnapshot();
        // "Sin ruta" por ids-base (sin sufijo -S de sub-lotes), no "released - rutas":
        // las entradas del mapa de solución se inflan con los sub-lotes de la división
        // por capacidad y la resta enmascaraba lotes realmente sin ruta.
        java.util.Set<String> routedBaseIds = new java.util.HashSet<>();
        for (String key : solution.getRoutes().keySet()) {
            routedBaseIds.add(key.replaceAll("(-S\\d+)+$", ""));
        }
        int unrouted = status.simulatedTime() != null
            ? Math.toIntExact(currentBatchesSnapshot.stream()
                .filter(batch -> !batch.ingressTime().isAfter(status.simulatedTime()))
                .filter(batch -> !routedBaseIds.contains(batch.batchId()))
                .count())
            : 0;

        // Construir vuelos activos para animación (deduplicado por flightId).
        // Solo interesan los vuelos recientes/próximos al reloj simulado — el mapa en vivo
        // no necesita vuelos que ya aterrizaron hace días. Sin este corte, en colapso (sin
        // límite de duración) esta lista crece sin fin con TODA la historia acumulada de
        // rutas, agrandando el payload de cada CYCLE_UPDATE por WebSocket ciclo tras ciclo
        // hasta que el front deja de verse fluido (en 5 días no se nota porque la duración
        // total está acotada a 5 días).
        ZonedDateTime activeFlightsCutoff = status.simulatedTime() != null
            ? status.simulatedTime().minusDays(3)
            : null;
        java.util.Map<String, CycleUpdateDTO.ActiveFlightDTO> flightMap = new java.util.LinkedHashMap<>();
        for (AssignedRoute route : solution.getRoutes().values()) {
            for (com.equipo2b.scheduler.model.Flight flight : route.getFlights()) {
                if (activeFlightsCutoff != null && flight.arrivalTime().isBefore(activeFlightsCutoff)) {
                    continue;
                }
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
            currentStorageInventoryService.calculateCurrentBags(solution, simulatedTime, getCurrentBatchesSnapshot());

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

    private List<ShipmentBatch> getCurrentBatchesSnapshot() {
        if (activeController == null) {
            return List.of();
        }
        synchronized (activeController) {
            List<ShipmentBatch> batches = activeController.getCurrentBatches();
            return batches == null ? List.of() : new ArrayList<>(batches);
        }
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

            if (!simulatedTime.isBefore(route.getFinalArrivalTime())) {
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
        if (activeController == null) return;
        currentFinishedAt = ZonedDateTime.now(ZoneOffset.UTC);
        String simId = activeController.getSimulationId();
        Solution solution = activeController.getCurrentSolution();
        List<ShipmentBatch> batches = activeController.getCurrentBatches();

        // Para PERIOD_SIMULATION y COLLAPSE_SIMULATION: exportar a JSON, NO a BD
        if (currentScenario == ScenarioType.PERIOD_SIMULATION || currentScenario == ScenarioType.COLLAPSE_SIMULATION) {
            try {
                resultExporter.exportResults(
                    simId, currentScenario, currentStartDate,
                    solution,
                    batches != null ? batches.size() : 0,
                    status.currentCycle(),
                    currentAirportManager,
                    batches,
                    activeController.getCollapseInfo()
                );
                System.out.println("✓ Resultados de simulación exportados a archivo JSON");
            } catch (Exception e) {
                System.err.println("❌ Error exportando resultados: " + e.getMessage());
            }
        } else if (currentScenario == ScenarioType.DAY_TO_DAY) {
            // DAY_TO_DAY: persistir en BD async (el reporte del frontend usa el último ciclo
            // + solución REST, no la BD) — notificar primero, persistir después.
            persistDayToDayAsync(simId, status, solution, batches);
        }

        // Notificar WebSocket que terminó después de dejar el archivo disponible para /results.
        if (webSocketHandler != null) {
            webSocketHandler.onSimulationFinished(status);
        }
    }

    @Override
    public void onSimulationError(SimulationStatus status, String errorMessage) {
        if (webSocketHandler != null) {
            webSocketHandler.onSimulationError(status, errorMessage);
        }
        currentFinishedAt = ZonedDateTime.now(ZoneOffset.UTC);
    }

    private SimulationEntity buildSimulationEntity(
            String simId,
            SimulationStatus status,
            Solution solution,
            List<ShipmentBatch> batches,
            String persistenceStatus) {
        int totalBatches = batches != null ? batches.size() : 0;
        int routedBatches = solution != null ? solution.getRoutes().size() : 0;
        int unroutableBatches = Math.max(0, totalBatches - routedBatches);
        long slaOk = solution != null
            ? solution.getRoutes().values().stream().filter(AssignedRoute::meetsSLA).count()
            : 0;
        double slaCompliance = routedBatches > 0 ? (slaOk * 100.0 / routedBatches) : 0.0;

        SimulationEntity entity = simulationRepository.findById(simId)
            .orElseGet(() -> new SimulationEntity(simId, currentScenario.name()));
        entity.setStatus(persistenceStatus);
        entity.setCurrentCycle(status.currentCycle());
        entity.setFinalFitness(solution != null ? solution.getFitness() : 0.0);
        entity.setSlaCompliance(slaCompliance);
        entity.setCollapseLevel(status.collapseLevel().name());
        entity.setAlgorithmType(activeController != null ? activeController.getAlgorithmType() : "UNKNOWN");
        entity.setTotalBatches(totalBatches);
        entity.setRoutedBatches(routedBatches);
        entity.setUnroutableBatches(unroutableBatches);
        return entity;
    }

    // ===== INTERNAL =====

    private Solution getCurrentSolution() {
        if (activeController == null) return null;
        return activeController.getCurrentSolution();
    }

    private ActiveSimulationDTO buildActiveSimulationDTO() {
        if (activeController == null || currentScenario == null) {
            return null;
        }
        SimulationStatus status = activeController.getStatus();
        String simulationId = activeController.getSimulationId();
        ZonedDateTime simulatedTime = status.simulatedTime();
        int connectedClients = webSocketHandler != null
            ? webSocketHandler.getConnectedClients(simulationId)
            : 0;

        return new ActiveSimulationDTO(
            simulationId,
            currentScenario.name(),
            currentScenario.getDescription(),
            statusLabel(status),
            formatDate(currentStartDate),
            formatDate(simulatedTime),
            status.currentCycle(),
            activeController.getDaysElapsed(),
            currentScenario.getK(),
            // Ta y Sa en SEGUNDOS (antes minutos); Sc sigue en minutos simulados.
            currentScenario.getTaSeconds(),
            currentScenario.getSaSeconds(),
            currentScenario.getSc(),
            connectedClients,
            formatDate(currentStartedAt),
            formatDate(currentFinishedAt),
            status.isRunning()
        );
    }

    private List<OperationalStateDTO.TransportUnitItemDTO> buildTransportUnits(
            Solution solution,
            ZonedDateTime simulatedTime,
            int limit) {
        if (solution == null || simulatedTime == null) return List.of();

        Map<String, TransportAccumulator> byFlight = new LinkedHashMap<>();
        for (AssignedRoute route : solution.getRoutes().values()) {
            for (Flight flight : route.getFlights()) {
                TransportAccumulator acc = byFlight.computeIfAbsent(flight.flightId(), id -> new TransportAccumulator(flight));
                acc.bags += route.getBatch().quantity();
                acc.meetsSla = acc.meetsSla && route.meetsSLA();
            }
        }

        return byFlight.values().stream()
            .sorted(Comparator
                .comparing((TransportAccumulator acc) -> acc.flight.departureTime())
                .thenComparing(acc -> acc.flight.flightId()))
            .limit(limit)
            .map(acc -> new OperationalStateDTO.TransportUnitItemDTO(
                acc.flight.flightId(),
                acc.flight.origin().id(),
                acc.flight.destination().id(),
                formatDate(acc.flight.departureTime()),
                formatDate(acc.flight.arrivalTime()),
                acc.flight.capacity(),
                acc.bags,
                acc.flight.capacity() > 0 ? (double) acc.bags / acc.flight.capacity() : 0.0,
                acc.bags == 0,
                acc.meetsSla
            ))
            .toList();
    }

    private List<OperationalStateDTO.WarehouseItemDTO> buildWarehouses(Solution solution, ZonedDateTime simulatedTime) {
        List<CycleUpdateDTO.AirportCapacityDTO> capacities = buildAirportCapacities(solution, simulatedTime);
        Map<String, CycleUpdateDTO.AirportCapacityDTO> byAirport = new HashMap<>();
        for (CycleUpdateDTO.AirportCapacityDTO capacity : capacities) {
            byAirport.put(capacity.airportId(), capacity);
        }

        if (currentAirportManager == null) return List.of();
        return currentAirportManager.getAllAirports().stream()
            .sorted(Comparator.comparing(Airport::id))
            .map(airport -> {
                CycleUpdateDTO.AirportCapacityDTO capacity = byAirport.get(airport.id());
                int currentBags = capacity != null ? capacity.currentBags() : 0;
                double ratio = capacity != null ? capacity.occupancyRatio() : 0.0;
                String semaphore = ratio >= 0.9 ? "RED" : ratio >= 0.7 ? "AMBER" : "GREEN";
                return new OperationalStateDTO.WarehouseItemDTO(
                    airport.id(),
                    airport.city(),
                    airport.country(),
                    currentBags,
                    airport.storageCapacity(),
                    ratio,
                    semaphore
                );
            })
            .toList();
    }

    private List<OperationalStateDTO.ShipmentOperationalItemDTO> buildOperationalShipments(
            Solution solution,
            ZonedDateTime simulatedTime,
            int limit) {
        if (simulatedTime == null) return List.of();
        List<OperationalStateDTO.ShipmentOperationalItemDTO> items = new ArrayList<>();
        if (solution != null) {
            for (AssignedRoute route : solution.getRoutes().values()) {
                ShipmentBatch batch = route.getBatch();
                List<Flight> flights = route.getFlights();
                String state = shipmentState(route, simulatedTime);
                String currentFlightId = currentFlightId(route, simulatedTime);
                double progress = shipmentProgress(batch.ingressTime(), route.getFinalArrivalTime(), simulatedTime);
                items.add(new OperationalStateDTO.ShipmentOperationalItemDTO(
                    batch.batchId(),
                    batch.clientId(),
                    batch.origin().id(),
                    batch.destination().id(),
                    batch.quantity(),
                    state,
                    currentFlightId,
                    route.meetsSLA(),
                    progress,
                    batch.batchId() + "-B0001",
                    batch.batchId() + "-B" + String.format("%04d", batch.quantity())
                ));
            }
        }

        return items.stream()
            .sorted(Comparator.comparing(OperationalStateDTO.ShipmentOperationalItemDTO::state)
                .thenComparing(OperationalStateDTO.ShipmentOperationalItemDTO::batchId))
            .limit(limit)
            .toList();
    }

    private String shipmentState(AssignedRoute route, ZonedDateTime simulatedTime) {
        if (!simulatedTime.isBefore(route.getFinalArrivalTime())) return "DELIVERED";
        for (Flight flight : route.getFlights()) {
            if (!simulatedTime.isBefore(flight.departureTime()) && simulatedTime.isBefore(flight.arrivalTime())) {
                return "IN_FLIGHT";
            }
        }
        Flight first = route.getFlights().isEmpty() ? null : route.getFlights().get(0);
        if (first != null && simulatedTime.isBefore(first.departureTime())) return "PLANNED";
        return "IN_WAREHOUSE_TRANSIT";
    }

    private String currentFlightId(AssignedRoute route, ZonedDateTime simulatedTime) {
        for (Flight flight : route.getFlights()) {
            if (!simulatedTime.isBefore(flight.departureTime()) && simulatedTime.isBefore(flight.arrivalTime())) {
                return flight.flightId();
            }
            if (simulatedTime.isBefore(flight.departureTime())) {
                return flight.flightId();
            }
        }
        return null;
    }

    private double shipmentProgress(ZonedDateTime start, ZonedDateTime end, ZonedDateTime now) {
        long totalMs = java.time.Duration.between(start, end).toMillis();
        if (totalMs <= 0) return 1.0;
        long elapsedMs = java.time.Duration.between(start, now).toMillis();
        return Math.max(0.0, Math.min(1.0, (double) elapsedMs / totalMs));
    }

    private static final class TransportAccumulator {
        private final Flight flight;
        private int bags;
        private boolean meetsSla = true;

        private TransportAccumulator(Flight flight) {
            this.flight = flight;
        }
    }

    private String statusLabel(SimulationStatus status) {
        if (!status.isRunning()) return "STOPPED";
        if (status.isPaused()) return "PAUSED";
        return "RUNNING";
    }

    private String formatDate(ZonedDateTime value) {
        return value != null ? value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) : null;
    }

    private void validateSimId(String simId) {
        if (activeController == null || !simId.equals(activeController.getSimulationId())) {
            throw new IllegalArgumentException("Simulación no encontrada: " + simId);
        }
    }

    private ZonedDateTime parseStartDate(String startDateStr) {
        try {
            return SimulationTimeParser.parseToUtc(startDateStr);
        } catch (Exception e) {
            System.err.println("⚠️ No se pudo parsear startDate/startDateTime '" + startDateStr + "': " + e.getMessage());
            throw e;
        }
    }

    private ZonedDateTime parseIngressTime(String ingressTime) {
        if (ingressTime != null && !ingressTime.isBlank()) {
            try {
                return ZonedDateTime.parse(ingressTime);
            } catch (Exception e) {
                throw new IllegalArgumentException("ingressTime debe ser ISO-8601 con zona horaria");
            }
        }
        ZonedDateTime simulated = activeController != null ? activeController.getSimulatedTime() : null;
        return simulated != null ? simulated : ZonedDateTime.now(ZoneOffset.UTC);
    }
}
