# Plan de Organización y Próximos Pasos

## ✅ Organización Completada

### Archivos Movidos

**Documentos obsoletos** → `archivos-no-usados/`:
- ALGORITMOS.md
- CAMBIOS_ORGANIZACION.md
- ESTRUCTURA_PROYECTO.md
- EXPLICACION_DETALLADA_PARA_ENTENDER.md
- SCHEDULER.md

**Resultados de experimentos** → `experimentos-historicos/`:
- experiment_results_2026-09-25_20260429_135750/
- experiment_results_2026-09-25_20260429_153728/
- experiment_results_2026-09-25_20260429_165211/
- experiment_results_2026-12-03_20260429_192251/
- experiment_results_2027-01-08_20260429_183332/
- experiment_results_2027-03-12_20260429_172851/

### Documentación Consolidada

**README.md** (Raíz del proyecto):
- Visión general del sistema
- Guía de inicio rápido
- Estructura del proyecto
- Configuración de parámetros
- Resultados de experimentación

**documentos/ARQUITECTURA_SISTEMA_COMPLETO.md**:
- Arquitectura técnica detallada
- Modelo de dominio completo
- Algoritmos de optimización
- Sistema de evaluación
- Flujo de ejecución
- Gestión de capacidad
- Experimentación y resultados
- Consideraciones técnicas

**documentos/ARQUITECTURA_SIMULACION_TIEMPO_REAL.md**:
- Explicación de simulación acelerada
- Parámetros Ta, Sa, K, Sc
- Tipos de simulación
- Consumo de datos
- Tracking de capacidad
- Configuraciones recomendadas

---

## 📁 Estructura Actual del Proyecto

```
scheduling-core/
├── src/
│   ├── main/java/com/equipo2b/scheduler/
│   │   ├── algorithm/          # Algoritmos (GA, Tabu)
│   │   ├── execution/          # Scheduler, Factory
│   │   ├── logic/              # RouteGenerator, Evaluator, Validator
│   │   ├── model/              # Entidades de dominio
│   │   ├── monitoring/         # CapacityMonitor, TrafficLight
│   │   ├── upload/             # Cargadores de datos
│   │   └── *.java              # Programas de análisis
│   └── test/                   # Tests (por revisar)
├── data/                       # Datos de entrada
│   ├── c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt
│   ├── planes_vuelo.txt
│   └── _envios_preliminar_/
├── documentos/                 # Documentación técnica
│   ├── ARQUITECTURA_SISTEMA_COMPLETO.md
│   ├── ARQUITECTURA_SIMULACION_TIEMPO_REAL.md
│   ├── ANALISIS_COLAPSO_SISTEMA.md
│   └── ... (otros documentos de análisis)
├── experimentos-historicos/   # Resultados de experimentos
├── archivos-no-usados/        # Archivos obsoletos
├── InformacionCaso/           # Documentación del problema
├── README.md                  # Documentación principal
├── PLAN_ORGANIZACION_Y_PROXIMOS_PASOS.md  # Este archivo
├── build.gradle.kts
└── settings.gradle.kts
```

---

## 🎯 Próximos Pasos: Integración con Frontend

### Fase 1: Diseño de API REST

**Objetivo**: Exponer funcionalidad del backend mediante API REST.

**Tecnologías Recomendadas**:
- **Framework**: Spring Boot 3.x
  - Spring Web (REST controllers)
  - Spring WebSocket (actualizaciones en tiempo real)
  - Spring Data JPA (persistencia)
  - Spring Validation (validación de DTOs)

**Endpoints Principales**:

```
POST   /api/simulation/start
GET    /api/simulation/{id}/status
GET    /api/simulation/{id}/results
DELETE /api/simulation/{id}/cancel

POST   /api/shipments
GET    /api/shipments/{id}
GET    /api/shipments/by-date/{date}

GET    /api/flights
GET    /api/flights/{id}
GET    /api/flights/by-airport/{airportId}

GET    /api/airports
GET    /api/airports/{id}
GET    /api/airports/{id}/capacity

GET    /api/routes/{batchId}
GET    /api/routes/by-flight/{flightId}

WS     /ws/simulation/{id}  (WebSocket para updates)
```

**DTOs a Crear**:
```java
// Request DTOs
SimulationStartRequest {
    SimulationType type;  // ONE_DAY, PERIOD, UNTIL_COLLAPSE
    LocalDate startDate;
    LocalDate endDate;    // opcional
    AlgorithmType algorithm;  // GATS, TABU
}

ShipmentCreateRequest {
    String clientId;
    String originAirportId;
    String destinationAirportId;
    int quantity;
    ZonedDateTime ingressTime;
}

// Response DTOs
SimulationStatusResponse {
    String simulationId;
    SimulationStatus status;  // RUNNING, COMPLETED, COLLAPSED, FAILED
    int currentExecution;
    int totalExecutions;
    ZonedDateTime currentDataTime;
    long elapsedRealTimeSeconds;
    double currentFitness;
    int violations;
}

SimulationResultsResponse {
    String simulationId;
    double finalFitness;
    int totalRoutes;
    int totalPackages;
    int assignedPackages;
    double slaCompliance;
    double avgFlightOccupancy;
    int violations;
    ZonedDateTime collapseDate;  // null si no colapsó
    List<ExecutionSummary> executionHistory;
}

RouteResponse {
    String batchId;
    String clientId;
    int quantity;
    AirportDTO origin;
    AirportDTO destination;
    List<FlightDTO> flights;
    ZonedDateTime departureTime;
    ZonedDateTime arrivalTime;
    boolean meetsSLA;
    Duration travelTime;
}
```

### Fase 2: Capa de Servicio

**Servicios a Crear**:

```java
@Service
public class SimulationService {
    // Gestiona ejecución de simulaciones
    SimulationStatusResponse startSimulation(SimulationStartRequest request);
    SimulationStatusResponse getStatus(String simulationId);
    SimulationResultsResponse getResults(String simulationId);
    void cancelSimulation(String simulationId);
}

@Service
public class ShipmentService {
    // Gestiona envíos de maletas
    ShipmentResponse createShipment(ShipmentCreateRequest request);
    ShipmentResponse getShipment(String batchId);
    List<ShipmentResponse> getShipmentsByDate(LocalDate date);
}

@Service
public class RouteService {
    // Consulta de rutas
    RouteResponse getRoute(String batchId);
    List<RouteResponse> getRoutesByFlight(String flightId);
}

@Service
public class CapacityService {
    // Monitoreo de capacidad
    CapacityReportResponse getCapacityReport(String simulationId);
    FlightOccupancyResponse getFlightOccupancy(String flightId);
    AirportOccupancyResponse getAirportOccupancy(String airportId);
}
```

### Fase 3: Persistencia

**Base de Datos Recomendada**: PostgreSQL

**Entidades a Persistir**:
```
simulations
├── id (UUID)
├── type (enum)
├── start_date
├── end_date
├── algorithm
├── status
├── created_at
├── started_at
├── completed_at
└── final_fitness

simulation_executions
├── id
├── simulation_id (FK)
├── execution_number
├── data_time
├── fitness
├── violations
├── execution_time_ms
└── created_at

routes
├── id
├── simulation_id (FK)
├── batch_id
├── client_id
├── origin_airport_id
├── destination_airport_id
├── quantity
├── ingress_time
├── meets_sla
└── created_at

route_flights
├── id
├── route_id (FK)
├── flight_id
├── sequence_order
└── created_at

shipments
├── id
├── batch_id
├── client_id
├── origin_airport_id
├── destination_airport_id
├── quantity
├── ingress_time
├── status
└── created_at
```

**Repositorios**:
```java
@Repository
public interface SimulationRepository extends JpaRepository<Simulation, UUID> {
    List<Simulation> findByStatus(SimulationStatus status);
    List<Simulation> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);
}

@Repository
public interface RouteRepository extends JpaRepository<Route, Long> {
    List<Route> findBySimulationId(UUID simulationId);
    Optional<Route> findByBatchId(String batchId);
    List<Route> findByClientId(String clientId);
}

@Repository
public interface ShipmentRepository extends JpaRepository<Shipment, Long> {
    List<Shipment> findByIngressTimeBetween(ZonedDateTime start, ZonedDateTime end);
    List<Shipment> findByClientId(String clientId);
    List<Shipment> findByStatus(ShipmentStatus status);
}
```

### Fase 4: WebSocket para Tiempo Real

**Configuración**:
```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }
    
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").withSockJS();
    }
}
```

**Mensajes a Enviar**:
```java
@Service
public class SimulationWebSocketService {
    
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    
    public void sendExecutionUpdate(String simulationId, ExecutionUpdate update) {
        messagingTemplate.convertAndSend(
            "/topic/simulation/" + simulationId,
            update
        );
    }
    
    public void sendCollapseAlert(String simulationId, CollapseAlert alert) {
        messagingTemplate.convertAndSend(
            "/topic/simulation/" + simulationId + "/collapse",
            alert
        );
    }
}
```

### Fase 5: Frontend

**Tecnologías Recomendadas**:
- **Framework**: React 18+ o Angular 17+
- **Mapa**: Leaflet o Mapbox GL JS
- **Gráficos**: Chart.js o D3.js
- **Estado**: Redux (React) o NgRx (Angular)
- **WebSocket**: SockJS + STOMP

**Componentes Principales**:

```
frontend/
├── src/
│   ├── components/
│   │   ├── Map/
│   │   │   ├── InteractiveMap.tsx
│   │   │   ├── AirportMarker.tsx
│   │   │   ├── FlightPath.tsx
│   │   │   └── RouteAnimation.tsx
│   │   ├── Dashboard/
│   │   │   ├── MetricsDashboard.tsx
│   │   │   ├── CapacityGauge.tsx
│   │   │   ├── TrafficLightIndicator.tsx
│   │   │   └── FitnessChart.tsx
│   │   ├── Simulation/
│   │   │   ├── SimulationControl.tsx
│   │   │   ├── SimulationConfig.tsx
│   │   │   └── SimulationProgress.tsx
│   │   └── Shipments/
│   │       ├── ShipmentForm.tsx
│   │       ├── ShipmentList.tsx
│   │       └── ShipmentDetails.tsx
│   ├── services/
│   │   ├── api.service.ts
│   │   ├── websocket.service.ts
│   │   └── simulation.service.ts
│   ├── store/
│   │   ├── simulation.slice.ts
│   │   ├── routes.slice.ts
│   │   └── capacity.slice.ts
│   └── App.tsx
```

**Pantallas Principales**:

1. **Mapa Interactivo**:
   - Visualización de aeropuertos
   - Rutas animadas de maletas
   - Ocupación de vuelos en tiempo real
   - Click en aeropuerto → detalles de capacidad

2. **Panel de Control**:
   - Iniciar/detener simulación
   - Configurar parámetros (tipo, fecha, algoritmo)
   - Monitorear progreso en tiempo real
   - Alertas de colapso

3. **Dashboard de Métricas**:
   - Fitness en tiempo real
   - Ocupación de vuelos (gauge)
   - Ocupación de almacenes (gauge)
   - SLA compliance (%)
   - Semáforos de estado

4. **Registro de Envíos**:
   - Formulario de ingreso
   - Lista de envíos pendientes
   - Búsqueda por cliente/fecha
   - Detalles de ruta asignada

---

## 🏗️ Arquitectura Propuesta (Completa)

```
┌─────────────────────────────────────────────────────────────┐
│                    FRONTEND (React/Angular)                 │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │
│  │ Mapa         │  │ Dashboard    │  │ Control      │     │
│  │ Interactivo  │  │ Métricas     │  │ Simulación   │     │
│  └──────────────┘  └──────────────┘  └──────────────┘     │
└────────────────────────┬────────────────────────────────────┘
                         │ HTTP REST + WebSocket
┌────────────────────────┴────────────────────────────────────┐
│              BACKEND (Spring Boot)                          │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  Controllers (REST + WebSocket)                      │  │
│  │  - SimulationController                              │  │
│  │  - ShipmentController                                │  │
│  │  - RouteController                                   │  │
│  │  - CapacityController                                │  │
│  └──────────────────────────────────────────────────────┘  │
│                         │                                    │
│  ┌──────────────────────┴──────────────────────────────┐  │
│  │  Services (Lógica de Negocio)                        │  │
│  │  - SimulationService                                 │  │
│  │  - ShipmentService                                   │  │
│  │  - RouteService                                      │  │
│  │  - CapacityService                                   │  │
│  └──────────────────────────────────────────────────────┘  │
│                         │                                    │
│  ┌──────────────────────┴──────────────────────────────┐  │
│  │  Core (Actual - Sin cambios)                         │  │
│  │  - Scheduler                                         │  │
│  │  - GeneticAlgorithm, TabuSearch                      │  │
│  │  - SolutionEvaluator, RouteValidator                 │  │
│  │  - Modelo de dominio                                 │  │
│  └──────────────────────────────────────────────────────┘  │
│                         │                                    │
│  ┌──────────────────────┴──────────────────────────────┐  │
│  │  Repositories (JPA)                                  │  │
│  │  - SimulationRepository                              │  │
│  │  - RouteRepository                                   │  │
│  │  - ShipmentRepository                                │  │
│  └──────────────────────────────────────────────────────┘  │
└────────────────────────┬────────────────────────────────────┘
                         │
┌────────────────────────┴────────────────────────────────────┐
│                  BASE DE DATOS (PostgreSQL)                 │
│  - simulations                                              │
│  - simulation_executions                                    │
│  - routes                                                   │
│  - route_flights                                            │
│  - shipments                                                │
└─────────────────────────────────────────────────────────────┘
```

---

## 📋 Checklist de Implementación

### Backend API
- [ ] Configurar Spring Boot project
- [ ] Crear DTOs (Request/Response)
- [ ] Implementar Controllers REST
- [ ] Implementar Services
- [ ] Configurar JPA + PostgreSQL
- [ ] Crear Repositories
- [ ] Implementar WebSocket
- [ ] Documentar API (Swagger/OpenAPI)
- [ ] Tests unitarios
- [ ] Tests de integración

### Frontend
- [ ] Configurar proyecto React/Angular
- [ ] Implementar servicio de API
- [ ] Implementar servicio de WebSocket
- [ ] Crear componente de Mapa
- [ ] Crear Dashboard de métricas
- [ ] Crear Panel de control
- [ ] Crear formulario de envíos
- [ ] Implementar estado global (Redux/NgRx)
- [ ] Tests unitarios
- [ ] Tests E2E

### Infraestructura
- [ ] Dockerizar backend
- [ ] Dockerizar frontend
- [ ] Docker Compose para desarrollo
- [ ] CI/CD pipeline
- [ ] Monitoring (Prometheus + Grafana)
- [ ] Logging centralizado
- [ ] Documentación de deployment

---

## 🎓 Recomendaciones de Diseño

### Principios SOLID
- **Single Responsibility**: Cada clase tiene una única responsabilidad
- **Open/Closed**: Abierto a extensión, cerrado a modificación
- **Liskov Substitution**: Interfaces bien definidas
- **Interface Segregation**: Interfaces específicas
- **Dependency Inversion**: Depender de abstracciones

### Patrones Recomendados
- **Repository Pattern**: Para acceso a datos
- **Service Layer Pattern**: Para lógica de negocio
- **DTO Pattern**: Para transferencia de datos
- **Factory Pattern**: Para creación de objetos complejos
- **Observer Pattern**: Para notificaciones en tiempo real

### Clean Architecture
```
Presentation Layer (Controllers, DTOs)
    ↓
Application Layer (Services, Use Cases)
    ↓
Domain Layer (Entities, Business Logic)
    ↓
Infrastructure Layer (Repositories, External Services)
```

---

## 📚 Recursos Adicionales

### Documentación Técnica
- Spring Boot: https://spring.io/projects/spring-boot
- Spring WebSocket: https://spring.io/guides/gs/messaging-stomp-websocket/
- React: https://react.dev/
- Leaflet: https://leafletjs.com/
- PostgreSQL: https://www.postgresql.org/docs/

### Tutoriales Recomendados
- REST API con Spring Boot
- WebSocket con STOMP
- React + Redux
- Leaflet para mapas interactivos
- Docker para desarrollo

---

**Última actualización**: Abril 2026  
**Próxima revisión**: Al iniciar Fase 1 (Backend API)
