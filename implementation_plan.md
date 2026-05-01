# Plan Detallado: Backend Spring Boot — Archivo por Archivo

## Resumen de Fases

| Fase | Descripción | Archivos | Esfuerzo |
|------|------------|----------|----------|
| 1 | Setup Spring Boot + MySQL en `build.gradle.kts` | 3 archivos | 30min |
| 2 | Fixes al dominio existente | 5 archivos modificados, 1 eliminado | 2h |
| 3 | DTOs para serialización JSON | 8 archivos nuevos | 1.5h |
| 4 | Services (lógica de negocio) | 4 archivos nuevos | 2h |
| 5 | REST Controllers | 3 archivos nuevos | 2h |
| 6 | WebSocket para tiempo real | 3 archivos nuevos | 1.5h |
| 7 | Persistencia JPA (entities + repos) | 8 archivos nuevos | 2h |
| 8 | Config y Application | 4 archivos nuevos/mod | 30min |

---

## FASE 1: Setup Spring Boot + MySQL

### [MODIFY] `build.gradle.kts`
Cambios exactos:
- Agregar plugin `org.springframework.boot` v3.3.x
- Agregar plugin `io.spring.dependency-management`
- Agregar dependencias:
  - `spring-boot-starter-web`
  - `spring-boot-starter-websocket`
  - `spring-boot-starter-data-jpa`
  - `mysql-connector-j` (runtime)
  - `com.h2database:h2` (runtime, para dev local sin MySQL)
  - `jackson-datatype-jsr310` (serialización de ZonedDateTime)
- Quitar bloque `application { mainClass }` (Spring Boot lo reemplaza)

### [NEW] `src/main/resources/application.properties`
```properties
server.port=8080
spring.datasource.url=jdbc:mysql://localhost:3306/scheduling
spring.datasource.username=root
spring.datasource.password=
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=false
# H2 fallback para desarrollo sin MySQL:
# spring.datasource.url=jdbc:h2:mem:scheduling
```

### [NEW] `SchedulingApplication.java` (en paquete raíz `com.equipo2b.scheduler`)
- Clase `@SpringBootApplication` con `main()`
- Reemplaza el `Main.java` actual como punto de entrada

---

## FASE 2: Fixes al Dominio Existente

### Fix 2.1 — Soporte para vuelos cancelados

#### [MODIFY] [FlightPlan.java](file:///c:/Users/Irico/Documents/DP1/scheduling-core/src/main/java/com/equipo2b/scheduler/model/FlightPlan.java)
**Líneas afectadas**: 17-27, 80-117

Cambios:
1. Agregar campo `private final Set<String> cancelledFlightIds = ConcurrentHashMap.newKeySet()`
2. Agregar método `cancelFlight(String flightId)` → agrega al set
3. Agregar método `isCancelled(String flightId)` → consulta el set
4. En `getFlightsFromAirport()` línea 100-112: agregar filtro **después** de crear el `adjustedFlight`:
   ```java
   // Antes de adjustedFlights.add(adjustedFlight):
   if (cancelledFlightIds.contains(adjustedFlight.flightId())) {
       continue;  // Skip vuelos cancelados
   }
   ```
5. Agregar método `getCancelledFlightIds()` → retorna copia del set

**Efecto**: Cuando el frontend cancela un vuelo, se agrega al set. A partir de ese momento, `RouteGenerator` nunca lo verá al buscar rutas porque `getFlightsFromAirport()` lo filtra.

### Fix 2.2 — Concurrencia en SimulationController

#### [MODIFY] [SimulationController.java](file:///c:/Users/Irico/Documents/DP1/scheduling-core/src/main/java/com/equipo2b/scheduler/execution/SimulationController.java)
**Líneas afectadas**: 40, 297-313, 329-376

Cambios:
1. Línea 40: `private volatile Solution currentSolution` → agregar `volatile`
2. Agregar interfaz interna `SimulationListener`:
   ```java
   public interface SimulationListener {
       void onCycleCompleted(SimulationStatus status, Solution solution);
       void onSimulationFinished(SimulationStatus status);
   }
   ```
3. Agregar campo `private SimulationListener listener`
4. Agregar método `setListener(SimulationListener listener)`
5. En `runSimulation()` línea ~343: después de `currentSolution = scheduler.executePlanningCycle(...)`, llamar `listener.onCycleCompleted(getStatus(), currentSolution)` si listener != null
6. En el bloque `finally` línea ~374: llamar `listener.onSimulationFinished(getStatus())` si listener != null
7. Agregar método `getSimulationId()` que retorna un UUID generado al iniciar

**Efecto**: El WebSocket handler puede registrarse como listener y emitir estado automáticamente cada ciclo.

### Fix 2.3 — CollapseDetector con métricas reales

#### [MODIFY] [CollapseDetector.java](file:///c:/Users/Irico/Documents/DP1/scheduling-core/src/main/java/com/equipo2b/scheduler/monitoring/CollapseDetector.java)
**Líneas afectadas**: 78-99, 107-128

Cambios:
1. Agregar campo `private CapacityMonitor capacityMonitor` (opcional, nullable)
2. Agregar constructor `CollapseDetector(CapacityMonitor monitor)`
3. Reescribir `calculateSystemOccupancy()`:
   - Si `capacityMonitor != null`: usar `capacityMonitor.calculateAverageFlightOccupancy(solution)` × 100
   - Si `capacityMonitor == null`: mantener lógica actual como fallback
4. También usar el fitness positivo como indicador directo de colapso en `evaluateCollapse()`:
   ```java
   if (solution.getFitness() > 0) {
       return new CollapseStatus(CollapseLevel.COLLAPSED, ...);
   }
   ```

### Fix 2.4 — Determinismo en ShipmentGenerator

#### [MODIFY] [ShipmentGenerator.java](file:///c:/Users/Irico/Documents/DP1/scheduling-core/src/main/java/com/equipo2b/scheduler/util/ShipmentGenerator.java)
**Líneas afectadas**: 37, 42-44, 254, 264-276

Cambios:
1. Agregar campo `private final Random random`
2. Modificar constructor: `this.random = new Random()` (default) + nuevo constructor `ShipmentGenerator(long seed)` → `this.random = new Random(seed)`
3. Línea 254: `Math.random() * 3` → `random.nextDouble() * 3`
4. Línea 264-276: `Math.random()` → `random.nextDouble()` (dos ocurrencias en `selectHourByPattern`)

### Fix 2.5 — Eliminar código muerto

#### [DELETE] [ValidationMode.java](file:///c:/Users/Irico/Documents/DP1/scheduling-core/src/main/java/com/equipo2b/scheduler/model/ValidationMode.java)
- Ya no se referencia en ningún código de producción

---

## FASE 3: DTOs para Serialización JSON

Todos en paquete `com.equipo2b.scheduler.api.dto`

### [NEW] `SimulationRequestDTO.java`
```java
public record SimulationRequestDTO(
    String scenario  // "DAY_TO_DAY", "PERIOD", "COLLAPSE"
) {}
```

### [NEW] `SimulationStatusDTO.java`
```java
public record SimulationStatusDTO(
    String simulationId,
    String status,           // RUNNING, PAUSED, STOPPED, COMPLETED
    int currentCycle,
    String simulatedTime,    // ISO-8601
    int batchesProcessed,
    int batchesFailed,
    int batchesPending,
    double currentFitness,
    String collapseLevel,    // NORMAL, WARNING, CRITICAL, COLLAPSED
    SemaphoreDTO semaphores
) {}
```

### [NEW] `SemaphoreDTO.java`
```java
public record SemaphoreDTO(
    String flights,   // GREEN, AMBER, RED
    String storage,
    String sla,
    double flightOccupancy,
    double storageOccupancy,
    double slaCompliance
) {}
```

### [NEW] `SolutionDTO.java`
```java
public record SolutionDTO(
    int totalRoutes,
    int totalBags,
    double fitness,
    List<RouteDTO> routes
) {}
```

### [NEW] `RouteDTO.java`
```java
public record RouteDTO(
    String batchId,
    String clientId,
    String originId,
    String destinationId,
    int quantity,
    boolean meetsSLA,
    String slaSlack,         // "PT2H30M" (ISO duration)
    List<FlightSegmentDTO> flights
) {}
```

### [NEW] `FlightSegmentDTO.java`
```java
public record FlightSegmentDTO(
    String flightId,
    String originId,
    String destinationId,
    String departureTime,
    String arrivalTime,
    int capacity
) {}
```

### [NEW] `CancelFlightRequestDTO.java`
```java
public record CancelFlightRequestDTO(
    String flightId,
    String day  // "2026-09-27" → se usa para construir el ID con sufijo -D
) {}
```

### [NEW] `ReplanResultDTO.java`
```java
public record ReplanResultDTO(
    int affectedBatches,
    int replannedBatches,
    int unreplannableBatches,
    double newFitness,
    List<String> unreplannableBatchIds
) {}
```

### [NEW] `DTOMapper.java` (en mismo paquete dto)
Clase utilitaria con métodos estáticos:
- `toStatusDTO(String simId, SimulationStatus, ShipmentQueue, TrafficLightReport)` → `SimulationStatusDTO`
- `toSolutionDTO(Solution)` → `SolutionDTO`
- `toRouteDTO(AssignedRoute)` → `RouteDTO`
- `toReplanResultDTO(ReplanResult)` → `ReplanResultDTO`

---

## FASE 4: Services

Todos en paquete `com.equipo2b.scheduler.service`

### [NEW] `DataLoadingService.java`
Responsabilidad: Cargar datos desde archivos `.txt` a objetos en memoria.

```java
@Service
public class DataLoadingService {
    // Rutas a los archivos (configurables via application.properties)
    @Value("${data.airports.path}") String airportsPath;
    @Value("${data.flights.path}") String flightsPath;
    @Value("${data.shipments.dir}") String shipmentsDir;

    // Usa los Uploaders existentes internamente
    private final AirportUploader airportUploader = new AirportUploader();
    private final FlightPlanUploader flightUploader = new FlightPlanUploader();
    private final ShipmentUploader shipmentUploader = new ShipmentUploader();

    public List<Airport> loadAirports();
    public FlightPlan loadFlightPlan(List<Airport> airports);
    public List<ShipmentBatch> loadShipments(List<Airport> airports, String date);
    public AirportManager createAirportManager(List<Airport> airports);
}
```

### [NEW] `SimulationService.java`
Responsabilidad: Orquestar simulaciones, conectar con `SimulationController`.

```java
@Service
public class SimulationService {
    private final DataLoadingService dataService;
    private SimulationController activeSimulation;  // Solo 1 activa
    private String activeSimulationId;

    // Estado compartido
    private FlightPlan flightPlan;
    private AirportManager airportManager;
    private ShipmentQueue shipmentQueue;
    private CapacityMonitor capacityMonitor;
    private TrafficLightIndicator indicator;

    public String startSimulation(ScenarioType scenario);
    public void stopSimulation(String id);
    public void pauseSimulation(String id);
    public void resumeSimulation(String id);
    public SimulationStatusDTO getStatus(String id);
    public SolutionDTO getSolution(String id);
    public void setSimulationListener(SimulationController.SimulationListener listener);
}
```

**Flujo de `startSimulation()`**:
1. Llamar `dataService.loadAirports()` → aeropuertos
2. Llamar `dataService.loadFlightPlan(airports)` → flightPlan
3. Llamar `dataService.loadShipments(airports, date)` → envíos
4. Si escenario es PERIOD o COLLAPSE: `ShipmentGenerator.generateFutureShipments()`
5. Crear `ShipmentQueue` y poblar
6. Crear `SimulationController(flightPlan, airportManager, clientRegistry)`
7. Llamar `simulationController.startSimulation(scenario, batches)`
8. Retornar `simulationId`

### [NEW] `CancellationService.java`
```java
@Service
public class CancellationService {
    private final SimulationService simulationService;

    public ReplanResultDTO cancelFlight(String simulationId, String flightId, String day);
}
```

**Flujo de `cancelFlight()`**:
1. Obtener `SimulationController` activo desde `SimulationService`
2. Construir el flight ID completo con sufijo `-D{offset}` del día
3. Llamar `flightPlan.cancelFlight(fullFlightId)` → lo agrega al set
4. Llamar `simulationController.registerCancellation(fullFlightId)`
5. Convertir `ReplanResult` a `ReplanResultDTO` y retornar

### [NEW] `MetricsService.java`
```java
@Service
public class MetricsService {
    private final SimulationService simulationService;

    public SemaphoreDTO getSemaphores(String simulationId);
    // Usa TrafficLightIndicator + CapacityMonitor internamente
}
```

---

## FASE 5: REST Controllers

Todos en paquete `com.equipo2b.scheduler.api`

### [NEW] `SimulationRestController.java`
```java
@RestController
@RequestMapping("/api/simulations")
@CrossOrigin(origins = "*")
public class SimulationRestController {
    private final SimulationService simulationService;

    @PostMapping("/start")
    ResponseEntity<Map<String,String>> start(@RequestBody SimulationRequestDTO req);
    // → { "simulationId": "uuid" }

    @PostMapping("/{id}/stop")
    ResponseEntity<Void> stop(@PathVariable String id);

    @PostMapping("/{id}/pause")
    ResponseEntity<Void> pause(@PathVariable String id);

    @PostMapping("/{id}/resume")
    ResponseEntity<Void> resume(@PathVariable String id);

    @GetMapping("/{id}/status")
    ResponseEntity<SimulationStatusDTO> getStatus(@PathVariable String id);

    @GetMapping("/{id}/solution")
    ResponseEntity<SolutionDTO> getSolution(@PathVariable String id);

    @GetMapping("/{id}/metrics")
    ResponseEntity<SemaphoreDTO> getMetrics(@PathVariable String id);
}
```

### [NEW] `CancellationRestController.java`
```java
@RestController
@RequestMapping("/api/simulations")
@CrossOrigin(origins = "*")
public class CancellationRestController {
    private final CancellationService cancellationService;

    @PostMapping("/{simId}/flights/{flightId}/cancel")
    ResponseEntity<ReplanResultDTO> cancel(
        @PathVariable String simId,
        @PathVariable String flightId,
        @RequestBody CancelFlightRequestDTO req
    );
}
```

### [NEW] `DataRestController.java`
```java
@RestController
@RequestMapping("/api/data")
@CrossOrigin(origins = "*")
public class DataRestController {
    private final DataLoadingService dataService;

    @GetMapping("/airports")
    ResponseEntity<List<AirportDTO>> getAirports();

    @GetMapping("/flights")
    ResponseEntity<Map<String,Object>> getFlights();
    // → { totalFlights: 2866, flights: [...primeros 100...] }
}
```

---

## FASE 6: WebSocket

En paquete `com.equipo2b.scheduler.api.websocket`

### [NEW] `WebSocketConfig.java`
```java
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(simulationHandler(), "/ws/simulation")
                .setAllowedOrigins("*");
    }

    @Bean
    public SimulationWebSocketHandler simulationHandler() {
        return new SimulationWebSocketHandler();
    }
}
```

### [NEW] `SimulationWebSocketHandler.java`
```java
public class SimulationWebSocketHandler extends TextWebSocketHandler
    implements SimulationController.SimulationListener {

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final ObjectMapper mapper;  // Jackson

    @Override // WebSocket
    public void afterConnectionEstablished(WebSocketSession session);

    @Override // WebSocket
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status);

    @Override // SimulationListener
    public void onCycleCompleted(SimulationStatus status, Solution solution);
    // → Serializa a JSON y envía a todas las sessions

    @Override // SimulationListener
    public void onSimulationFinished(SimulationStatus status);

    private void broadcast(String json);
}
```

### [NEW] `WebSocketAutoRegistrar.java`
```java
@Component
public class WebSocketAutoRegistrar {
    // Inyecta SimulationService y WebSocketHandler
    // Registra el handler como listener cuando se inicia simulación
    @PostConstruct
    public void init() {
        simulationService.setSimulationListener(webSocketHandler);
    }
}
```

---

## FASE 7: Persistencia JPA

En paquete `com.equipo2b.scheduler.persistence`

### [NEW] `entity/AirportEntity.java`
JPA entity mapeada a tabla `airports`:
- `@Id String id`, `String city`, `String country`, `String timezone`, `int storageCapacity`, `double latitude`, `double longitude`, `String continent`
- Método `toDomainModel()` → convierte a `Airport` (record de dominio)
- Método estático `fromDomainModel(Airport)` → crea entity

### [NEW] `entity/FlightEntity.java`
JPA entity mapeada a tabla `flights`:
- `@Id String flightId`, `@ManyToOne AirportEntity origin/destination`, `ZonedDateTime departure/arrival`, `int capacity`, `String flightType`
- `toDomainModel(AirportEntity origin, AirportEntity dest)` → `Flight`

### [NEW] `entity/SimulationEntity.java`
JPA entity mapeada a tabla `simulations`:
- `@Id UUID id`, `String scenario`, `String status`, `LocalDateTime startedAt/finishedAt`, `int currentCycle`, `double finalFitness`, `double slaCompliance`, `String collapseLevel`

### [NEW] `entity/FlightCancellationEntity.java`
JPA entity mapeada a tabla `flight_cancellations`:
- `@Id @GeneratedValue Long id`, `@ManyToOne SimulationEntity simulation`, `String flightId`, `LocalDate cancelledDay`, `int affectedBatches`, `int replannedBatches`

### [NEW] `repository/AirportRepository.java`
```java
public interface AirportRepository extends JpaRepository<AirportEntity, String> {}
```

### [NEW] `repository/FlightRepository.java`
```java
public interface FlightRepository extends JpaRepository<FlightEntity, String> {
    List<FlightEntity> findByOriginId(String originId);
}
```

### [NEW] `repository/SimulationRepository.java`
```java
public interface SimulationRepository extends JpaRepository<SimulationEntity, UUID> {}
```

### [NEW] `DataImportService.java`
```java
@Service
public class DataImportService {
    // Usa los Uploaders existentes para leer archivos
    // Guarda en BD via repositories
    public int importAirports(String filePath);
    public int importFlights(String filePath);
}
```

---

## FASE 8: Configuración

### [NEW] `config/WebConfig.java`
```java
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins("*").allowedMethods("*");
    }
}
```

### [NEW] `config/JacksonConfig.java`
```java
@Configuration
public class JacksonConfig {
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
```

### [MODIFY] `Main.java`
- Mantener como está (para ejecución standalone)
- Agregar nota de que el nuevo entry point es `SchedulingApplication`

---

## Resumen: Inventario Completo de Archivos

### Archivos NUEVOS (30)
| # | Archivo | Paquete |
|---|---------|---------|
| 1 | `SchedulingApplication.java` | `scheduler` |
| 2 | `application.properties` | `resources` |
| 3 | `SimulationRequestDTO.java` | `api.dto` |
| 4 | `SimulationStatusDTO.java` | `api.dto` |
| 5 | `SemaphoreDTO.java` | `api.dto` |
| 6 | `SolutionDTO.java` | `api.dto` |
| 7 | `RouteDTO.java` | `api.dto` |
| 8 | `FlightSegmentDTO.java` | `api.dto` |
| 9 | `CancelFlightRequestDTO.java` | `api.dto` |
| 10 | `ReplanResultDTO.java` | `api.dto` |
| 11 | `DTOMapper.java` | `api.dto` |
| 12 | `SimulationRestController.java` | `api` |
| 13 | `CancellationRestController.java` | `api` |
| 14 | `DataRestController.java` | `api` |
| 15 | `WebSocketConfig.java` | `api.websocket` |
| 16 | `SimulationWebSocketHandler.java` | `api.websocket` |
| 17 | `WebSocketAutoRegistrar.java` | `api.websocket` |
| 18 | `DataLoadingService.java` | `service` |
| 19 | `SimulationService.java` | `service` |
| 20 | `CancellationService.java` | `service` |
| 21 | `MetricsService.java` | `service` |
| 22 | `AirportEntity.java` | `persistence.entity` |
| 23 | `FlightEntity.java` | `persistence.entity` |
| 24 | `SimulationEntity.java` | `persistence.entity` |
| 25 | `FlightCancellationEntity.java` | `persistence.entity` |
| 26 | `AirportRepository.java` | `persistence.repository` |
| 27 | `FlightRepository.java` | `persistence.repository` |
| 28 | `SimulationRepository.java` | `persistence.repository` |
| 29 | `DataImportService.java` | `persistence` |
| 30 | `WebConfig.java` + `JacksonConfig.java` | `config` |

### Archivos MODIFICADOS (5)
| # | Archivo | Qué cambia |
|---|---------|-----------|
| 1 | `build.gradle.kts` | + Spring Boot plugins + dependencias |
| 2 | `FlightPlan.java` | + Set cancelledFlightIds + filtro en getFlightsFromAirport |
| 3 | `SimulationController.java` | + volatile + SimulationListener + callbacks |
| 4 | `CollapseDetector.java` | + CapacityMonitor inyectable + fitness>0 check |
| 5 | `ShipmentGenerator.java` | Math.random() → Random con semilla |

### Archivos ELIMINADOS (1)
| # | Archivo | Razón |
|---|---------|-------|
| 1 | `ValidationMode.java` | Código muerto, ya no se referencia |

---

## Orden de Ejecución Recomendado

```
Fase 1 (setup) → Fase 2 (fixes) → Fase 8 (config) → Fase 3 (DTOs) →
Fase 4 (services) → Fase 5 (controllers) → Fase 6 (websocket) → Fase 7 (JPA)
```

> [!TIP]
> Después de Fase 5 ya tendrás un backend funcional que responde a REST. Las fases 6 y 7 son mejoras.
