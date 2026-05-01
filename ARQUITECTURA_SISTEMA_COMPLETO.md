# Arquitectura Completa del Sistema Tasf.B2B

## 📋 Tabla de Contenidos

1. [Visión General del Sistema](#visión-general-del-sistema)
2. [Problema de Negocio](#problema-de-negocio)
3. [Arquitectura de Software](#arquitectura-de-software)
4. [Modelo de Dominio](#modelo-de-dominio)
5. [Algoritmos de Optimización](#algoritmos-de-optimización)
6. [Sistema de Evaluación](#sistema-de-evaluación)
7. [Flujo de Ejecución](#flujo-de-ejecución)
8. [Gestión de Capacidad](#gestión-de-capacidad)
9. [Experimentación y Resultados](#experimentación-y-resultados)
10. [Consideraciones Técnicas](#consideraciones-técnicas)

---

## 1. Visión General del Sistema

### Contexto del Problema

Tasf.B2B es una empresa de transporte aéreo que ofrece servicios de traslado de equipajes extraviados entre aeropuertos de América, Asia y Europa. El sistema debe planificar rutas óptimas para miles de maletas diariamente, cumpliendo estrictos plazos de entrega (SLA) y respetando capacidades limitadas de vuelos y almacenes.

### Desafío Principal

**Problema de Optimización Combinatoria NP-Hard**:
- Asignar ~10,000 lotes de maletas/día
- Usar ~2,866 vuelos disponibles
- Conectar 30 aeropuertos
- Cumplir SLA: 12h mismo continente, 24h diferentes continentes
- Respetar capacidades: 150-400 maletas/vuelo, 400-480 maletas/almacén
- Máximo 3 vuelos por ruta (2 escalas)

### Solución Implementada

Sistema de planificación basado en **algoritmos metaheurísticos** que:
1. Genera rutas factibles usando BFS (Breadth-First Search)
2. Optimiza asignaciones con Genetic Algorithm + Tabu Search (GATS)
3. Evalúa soluciones con función fitness multi-objetivo
4. Valida restricciones de capacidad y SLA
5. Acumula soluciones incrementalmente en simulación acelerada

---

## 2. Problema de Negocio

### Actores del Sistema

**Cliente (Aerolínea)**:
- Entrega maletas en oficinas de Tasf.B2B en aeropuertos
- Recibe plan de viaje de las maletas
- Puede solicitar reporte de monitoreo

**Operador de Tasf.B2B**:
- Registra envíos de maletas
- Monitorea operaciones en tiempo real
- Gestiona cancelaciones de vuelos
- Visualiza métricas de capacidad

**Sistema de Planificación**:
- Genera rutas óptimas automáticamente
- Replanifica ante cancelaciones
- Detecta colapsos de capacidad

### Restricciones del Negocio

**Plazos de Entrega (SLA)**:
```
Mismo continente:    12 horas máximo
Diferentes continentes: 24 horas máximo
```

**Capacidades Físicas**:
```
Vuelos:    150-400 maletas (según ruta)
Almacenes: 400-480 maletas (según aeropuerto)
Escala mínima: 10 minutos entre vuelos
```

**Rutas**:
```
Máximo: 3 vuelos por ruta (2 escalas)
Vuelos: Horarios fijos recurrentes diarios
```


### Escenarios de Operación

**1. Operaciones Día a Día (Tiempo Real)**:
- Registro transaccional de envíos conforme llegan
- Planificación incremental cada Sa minutos
- Monitoreo en tiempo real de rutas
- Manejo dinámico de cancelaciones

**2. Simulación de Período (5 días)**:
- Simula 5 días de operaciones en 50-90 minutos reales
- Usa datos históricos proyectados
- Evalúa capacidad bajo carga sostenida
- Identifica cuellos de botella

**3. Simulación Hasta Colapso**:
- Ejecuta hasta detectar colapso del sistema
- Identifica punto de quiebre de capacidad
- Fitness positivo indica colapso
- Duración: 60-90 minutos reales

---

## 3. Arquitectura de Software

### Capas del Sistema

```
┌─────────────────────────────────────────────────────────────┐
│                  CAPA DE PRESENTACIÓN                       │
│                    (Por Implementar)                        │
│  - Frontend Web (React/Angular)                             │
│  - Mapa interactivo (Leaflet/Mapbox)                        │
│  - Dashboard de métricas                                    │
│  - Panel de control de simulaciones                         │
└────────────────────────┬────────────────────────────────────┘
                         │ REST API + WebSockets
┌────────────────────────┴────────────────────────────────────┐
│                  CAPA DE APLICACIÓN                         │
│                    (Por Implementar)                        │
│  - Controllers REST                                         │
│  - WebSocket handlers                                       │
│  - DTOs y mappers                                           │
│  - Servicios de negocio                                     │
└────────────────────────┬────────────────────────────────────┘
                         │
┌────────────────────────┴────────────────────────────────────┐
│                  CAPA DE DOMINIO                            │
│                    (Implementado)                           │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  Scheduler (Orquestador Principal)                   │  │
│  │  - Gestiona ciclos de planificación                  │  │
│  │  - Consume envíos por ventanas temporales            │  │
│  │  - Acumula soluciones incrementalmente               │  │
│  │  - Detecta colapsos del sistema                      │  │
│  └──────────────────────────────────────────────────────┘  │
│                         │                                    │
│  ┌──────────────────────┴──────────────────────────────┐  │
│  │  Algoritmos de Optimización                          │  │
│  │  - GeneticAlgorithm (paralelizado)                   │  │
│  │  - TabuSearch                                        │  │
│  │  - RouteGenerator (BFS)                              │  │
│  └──────────────────────────────────────────────────────┘  │
│                         │                                    │
│  ┌──────────────────────┴──────────────────────────────┐  │
│  │  Evaluación y Validación                             │  │
│  │  - SolutionEvaluator (función fitness)               │  │
│  │  - RouteValidator (restricciones)                    │  │
│  │  - CapacityMonitor (ocupación)                       │  │
│  │  - TrafficLightIndicator (semáforos)                 │  │
│  └──────────────────────────────────────────────────────┘  │
│                         │                                    │
│  ┌──────────────────────┴──────────────────────────────┐  │
│  │  Modelo de Dominio (Entidades)                       │  │
│  │  - Airport, Flight, ShipmentBatch                    │  │
│  │  - AssignedRoute, Solution                           │  │
│  │  - FlightPlan, ShipmentQueue                         │  │
│  │  - AirportManager, ClientRegistry                    │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                         │
┌────────────────────────┴────────────────────────────────────┐
│                  CAPA DE DATOS                              │
│                    (Implementado)                           │
│  - Uploaders (carga de archivos)                           │
│  - Parsers (procesamiento de datos)                        │
│  - Validadores de formato                                  │
└─────────────────────────────────────────────────────────────┘
```

### Patrones de Diseño Utilizados

**Strategy Pattern**:
- `OptimizationAlgorithm` interface
- Implementaciones: `GeneticAlgorithm`, `TabuSearch`
- Permite intercambiar algoritmos sin cambiar el Scheduler

**Factory Pattern**:
- `SchedulerFactory`: Crea instancias configuradas de Scheduler
- `AlgorithmConfig`: Configuración de parámetros

**Builder Pattern**:
- Construcción de `AssignedRoute` con validaciones
- Construcción de `Solution` incremental

**Observer Pattern** (Futuro):
- Para notificaciones en tiempo real al frontend
- WebSocket para actualizaciones de estado

**Repository Pattern** (Futuro):
- Para persistencia de soluciones
- Para histórico de ejecuciones

---

## 4. Modelo de Dominio

### Entidades Principales

#### Airport
```java
public record Airport(
    String id,              // Código IATA (ej: "SPJC")
    String name,            // Nombre del aeropuerto
    Continent continent,    // AMERICA, EUROPE, ASIA
    ZoneId zoneId,          // Huso horario
    int storageCapacity     // Capacidad de almacén (maletas)
)
```

**Responsabilidades**:
- Representa un aeropuerto físico
- Almacena capacidad de almacenamiento
- Gestiona huso horario para cálculos temporales

#### Flight
```java
public record Flight(
    String flightId,           // ID único del vuelo
    Airport origin,            // Aeropuerto origen
    Airport destination,       // Aeropuerto destino
    ZonedDateTime departureTime, // Hora de salida
    ZonedDateTime arrivalTime,   // Hora de llegada
    int capacity,              // Capacidad (maletas)
    FlightType type            // DOMESTIC, INTERNATIONAL
)
```

**Responsabilidades**:
- Representa un vuelo programado
- Almacena capacidad máxima
- Calcula duración del vuelo

**Nota Importante**: Los vuelos tienen fechas base (2026-01-01) pero representan horarios recurrentes diarios. El sistema proyecta estos vuelos a las fechas necesarias dinámicamente.

#### ShipmentBatch
```java
public record ShipmentBatch(
    String batchId,            // ID único del lote
    String airportBatchId,     // ID en aeropuerto origen
    String clientId,           // ID del cliente (aerolínea)
    Airport origin,            // Aeropuerto origen
    Airport destination,       // Aeropuerto destino
    int quantity,              // Cantidad de maletas
    ZonedDateTime ingressTime  // Timestamp de ingreso
)
```

**Responsabilidades**:
- Representa un lote de maletas del mismo cliente
- Calcula SLA según continentes: `calculateSLA()`
- Verifica cumplimiento de SLA: `meetsSLA(arrivalTime)`

**Principio**: "Equipaje con Dueño" - cada lote pertenece a un cliente específico

#### AssignedRoute
```java
public class AssignedRoute {
    private final ShipmentBatch batch;
    private final List<Flight> flights;
    
    // Constructor valida:
    // - Primer vuelo desde origen del lote
    // - Último vuelo a destino del lote
    // - Conexiones válidas entre vuelos
    // - Tiempos de escala >= 10 minutos
}
```

**Responsabilidades**:
- Representa una ruta asignada a un lote
- Valida factibilidad de la ruta
- Calcula tiempo total de viaje
- Verifica cumplimiento de SLA

#### Solution
```java
public class Solution {
    private final Map<String, AssignedRoute> routes; // BatchId -> Route
    private double fitness;
    private boolean evaluated;
}
```

**Responsabilidades**:
- Contiene todas las rutas de una planificación
- Almacena fitness evaluado
- Permite fusión incremental: `merge(Solution other)`
- Calcula vuelos utilizados: `getUsedFlights()`
- Calcula total de maletas: `getTotalBags()`

**Diseño**: Usa `HashMap` para acceso O(1) a rutas por batchId, facilitando operaciones genéticas (crossover, mutación).


#### FlightPlan
```java
public class FlightPlan {
    private final List<Flight> allFlights;
    private final Map<String, List<Flight>> flightsByOrigin; // Índice
}
```

**Responsabilidades**:
- Almacena todos los vuelos programados
- Índice por aeropuerto origen para búsqueda O(1)
- Proyecta vuelos a fechas específicas: `getFlightsFromAirport(airport, start, end)`

**Proyección Temporal**:
```java
// Vuelo base: LIMA-MIAMI 10:00 (2026-01-01)
// Proyección: Crea instancias para 2026-09-25 10:00, 2026-09-26 10:00, etc.
```

#### ShipmentQueue
```java
public class ShipmentQueue {
    private final TreeMap<ZonedDateTime, List<ShipmentBatch>> timeIndex;
}
```

**Responsabilidades**:
- Cola de envíos pendientes de planificación
- Índice temporal con `TreeMap` para consultas O(log n)
- Consumo por ventana temporal: `consumeShipments(start, end)`
- Los envíos consumidos se eliminan (no se repiten)

---

## 5. Algoritmos de Optimización

### 5.1 RouteGenerator (BFS)

**Propósito**: Generar rutas factibles entre origen y destino.

**Algoritmo**: Breadth-First Search (BFS) con restricciones.

**Restricciones Verificadas**:
- Tiempo de escala mínimo: 10 minutos
- SLA: 12h mismo continente, 24h diferentes continentes
- Profundidad máxima: 3 vuelos (2 escalas)
- Vuelos disponibles en ventana temporal

**Pseudocódigo**:
```
function findPath(origin, destination, startTime, sla):
    deadline = startTime + sla
    queue = [(origin, startTime, [])]
    visited = set()
    
    while queue not empty:
        (airport, time, path) = queue.dequeue()
        
        if airport == destination:
            return path
        
        if len(path) >= MAX_HOPS:
            continue
        
        for flight in getAvailableFlights(airport, time, deadline):
            if layover(time, flight.departure) >= 10 minutes:
                if flight.arrival < deadline:
                    newPath = path + [flight]
                    queue.enqueue((flight.destination, flight.arrival, newPath))
    
    return null  // No path found
```

**Aleatorización**: Shufflea vuelos disponibles para generar diversidad en población inicial del GA.

**Intentos**: Hasta 20 intentos con diferentes órdenes aleatorios.

### 5.2 Genetic Algorithm (Paralelizado)

**Configuración Actual**:
```
Población: 40 individuos
Generaciones: 80
Elitismo: 2 mejores individuos
Tasa de mutación: 0.15
Torneo: 3 individuos
```

**Flujo de Ejecución**:
```
1. Inicializar población (PARALELO)
   - 40 individuos en paralelo con parallelStream()
   - Cada individuo: RouteGenerator para cada lote
   
2. Por cada generación (1-80):
   a. Evaluar población (PARALELO)
      - Fitness de 40 individuos en paralelo
   
   b. Ordenar por fitness
   
   c. Crear nueva generación:
      - Elitismo: Copiar 2 mejores
      - Resto: Selección por torneo + Crossover + Mutación
   
3. Retornar mejor individuo
```

**Paralelización**:
```java
// Inicialización paralela
List<Solution> population = IntStream.range(0, populationSize)
    .parallel()
    .mapToObj(i -> {
        Solution solution = new Solution();
        for (ShipmentBatch batch : batches) {
            AssignedRoute route = routeGenerator.generateFeasibleRoute(batch);
            if (route != null) solution.addRoute(route);
        }
        return solution;
    })
    .collect(Collectors.toList());

// Evaluación paralela
population.parallelStream().forEach(solution -> {
    evaluator.evaluate(solution);
});
```

**Thread Safety**:
- Usa `ThreadLocalRandom.current()` en lugar de `Random` compartido
- Cada thread tiene su propio generador de números aleatorios
- No hay estado compartido mutable

**Operadores Genéticos**:

**Crossover Uniforme**:
```java
for (String batchId : allBatchIds) {
    if (random.nextDouble() < 0.5) {
        child.addRoute(parent1.getRoute(batchId));
    } else {
        child.addRoute(parent2.getRoute(batchId));
    }
}
```

**Mutación**:
```java
if (random.nextDouble() < mutationRate) {
    // Regenerar ruta para un lote aleatorio
    ShipmentBatch batch = selectRandomBatch();
    AssignedRoute newRoute = routeGenerator.generateFeasibleRoute(batch);
    solution.addRoute(newRoute);
}
```

**Rendimiento**:
- Tiempo promedio: 144 segundos (54% carga)
- Speedup: 3.2x vs versión secuencial
- Escalabilidad: Lineal hasta 8 núcleos

### 5.3 Tabu Search

**Configuración Actual**:
```
Iteraciones: 200
Lista tabú: 20 movimientos
Vecindario: Swap de rutas
```

**Flujo de Ejecución**:
```
1. Solución inicial (greedy o aleatoria)

2. Por cada iteración (1-200):
   a. Generar vecindario:
      - Swap: Intercambiar rutas de 2 lotes
      - Regenerate: Regenerar ruta de 1 lote
   
   b. Evaluar vecinos (excluir tabú)
   
   c. Seleccionar mejor vecino
   
   d. Actualizar lista tabú
   
   e. Actualizar mejor solución global

3. Retornar mejor solución encontrada
```

**Movimientos**:
```java
// Swap: Intercambiar rutas de 2 lotes
swap(solution, batchId1, batchId2)

// Regenerate: Regenerar ruta de 1 lote
regenerate(solution, batchId)
```

**Lista Tabú**:
- Almacena últimos 20 movimientos
- Evita ciclos en la búsqueda
- Criterio de aspiración: Acepta movimiento tabú si mejora mejor solución global

**Rendimiento**:
- Tiempo promedio: 50 segundos (constante)
- Predecible y rápido
- Menor calidad que GATS (~2-3%)

### 5.4 GATS (Hybrid)

**Estrategia**: Genetic Algorithm seguido de Tabu Search refinement.

**Flujo**:
```
1. Ejecutar Genetic Algorithm (80 generaciones)
   → Solución GA

2. Refinar con Tabu Search (150 iteraciones)
   → Solución GATS (mejorada)
```

**Ventajas**:
- Exploración global (GA) + Explotación local (Tabu)
- 2-3% mejor que Tabu puro en operación normal
- 8.7% mejor en condiciones de colapso
- Más robusto bajo estrés

**Desventajas**:
- 3x más lento que Tabu puro
- Mayor uso de CPU y memoria

---

## 6. Sistema de Evaluación

### 6.1 Función Fitness

**Fórmula**:
```
fitness = PENALIZACIONES - PREMIOS
```

**Menor valor = mejor solución** (idealmente negativo)

### Penalizaciones (Restricciones Duras)

| Violación | Penalización | Propósito |
|-----------|--------------|-----------|
| **Capacidad de vuelo excedida** | 10,000 puntos/maleta | Evitar sobrecarga de vuelos |
| **Capacidad de almacén excedida** | 15,000 puntos/maleta | Evitar congestión en aeropuertos |
| **Violación de SLA** | 20,000 puntos/hora | Cumplir compromisos con clientes |
| **Tiempo de escala insuficiente** | 5,000 puntos/violación | Garantizar factibilidad física |

**Ejemplo de Cálculo**:
```
Vuelo LIMA-MIAMI: Capacidad 330, Asignadas 350
Exceso: 20 maletas
Penalización: 20 × 10,000 = 200,000 puntos
```

### Premios (Optimización)

| Concepto | Premio | Propósito |
|----------|--------|-----------|
| **Holgura de tiempo** | 100 puntos/hora | Robustez ante disrupciones |
| **Vuelos no utilizados** | 50 puntos/vuelo | Eficiencia de recursos |

**Límites**:
- Holgura máxima: 500 puntos por ruta
- Premios totales típicos: ~2,000,000 puntos

### Interpretación del Fitness

**Fitness Negativo** (Sistema Saludable):
```
Ejemplo: -1,545,740
Penalizaciones: ~500,000 (pocas violaciones)
Premios:      ~2,045,740 (muchos vuelos sin usar, holgura)
Resultado: Sistema operando normalmente
```

**Fitness Positivo** (Sistema Colapsado):
```
Ejemplo: +27,619,035
Penalizaciones: ~30,000,000 (MUCHAS violaciones)
Premios:        ~2,000,000 (pocos vuelos sin usar)
Resultado: Sistema NO puede manejar la carga
```

### 6.2 Validación de Restricciones

**RouteValidator** verifica:

1. **Capacidad de Vuelos**:
```java
for (Flight flight : usedFlights) {
    int load = calculateFlightLoad(solution, flight);
    if (load > flight.capacity()) {
        violations.add(new CapacityViolation(flight, load));
    }
}
```

2. **Capacidad de Almacenes**:
```java
for (Airport airport : airports) {
    Map<ZonedDateTime, Integer> occupancy = calculateStorageOccupancy(solution, airport);
    for (Entry<ZonedDateTime, Integer> entry : occupancy.entrySet()) {
        if (entry.getValue() > airport.storageCapacity()) {
            violations.add(new StorageViolation(airport, entry.getKey(), entry.getValue()));
        }
    }
}
```

3. **Cumplimiento de SLA**:
```java
for (AssignedRoute route : solution.getRoutes().values()) {
    if (!route.meetsSLA()) {
        violations.add(new SLAViolation(route));
    }
}
```

4. **Tiempos de Escala**:
```java
for (AssignedRoute route : solution.getRoutes().values()) {
    List<Flight> flights = route.getFlights();
    for (int i = 0; i < flights.size() - 1; i++) {
        Duration layover = Duration.between(
            flights.get(i).arrivalTime(),
            flights.get(i+1).departureTime()
        );
        if (layover.toMinutes() < 10) {
            violations.add(new LayoverViolation(route, i));
        }
    }
}
```

**ValidationReport**:
```java
public class ValidationReport {
    private final boolean valid;
    private final List<Violation> violations;
    private final String summary;
}
```


---

## 7. Flujo de Ejecución

### 7.1 Scheduler (Orquestador Principal)

**Responsabilidad**: Gestionar ciclos de planificación incremental.

**Parámetros**:
- `Ta`: Tiempo máximo de algoritmo (2 min)
- `Sa`: Salto de avance entre ejecuciones (5-30 min)
- `K`: Multiplicador de consumo de datos (96-480)
- `Sc`: Ventana de consumo = Sa × K (1-5 días)

**Flujo Principal**:
```java
public Solution run(ZonedDateTime startTime, int maxCycles) {
    ZonedDateTime dataTime = startTime;
    int cycle = 0;
    
    while (shipmentQueue.getPendingCount() > 0) {
        cycle++;
        
        // 1. Consumir envíos en ventana [dataTime, dataTime + Sc]
        List<ShipmentBatch> batches = shipmentQueue.consumeShipments(
            dataTime, 
            dataTime.plusMinutes(Sc)
        );
        
        // 2. Ejecutar algoritmo (máximo Ta minutos)
        Solution newSolution = algorithm.optimize(batches);
        
        // 3. Acumular rutas a solución global
        currentSolution.merge(newSolution);
        
        // 4. Re-evaluar solución acumulada
        evaluator.evaluate(currentSolution);
        
        // 5. Validar capacidades
        ValidationReport report = validator.validate(currentSolution);
        
        // 6. Verificar colapso
        if (currentSolution.getFitness() > 0) {
            System.out.println("❌ COLAPSO DETECTADO");
            break;
        }
        
        // 7. Avanzar tiempo de datos
        dataTime = dataTime.plusMinutes(Sc);
        
        // 8. Verificar límite de ciclos
        if (maxCycles > 0 && cycle >= maxCycles) {
            break;
        }
    }
    
    return currentSolution;
}
```

**Características Clave**:
- **Consumo Incremental**: Cada ejecución consume datos nuevos (no repite)
- **Acumulación**: Solución global persiste entre ejecuciones
- **Detección de Colapso**: Fitness > 0 indica colapso
- **Tracking de Capacidad**: Vuelos acumulan maletas de todas las ejecuciones

### 7.2 Ejemplo de Ejecución

**Configuración**: Ta=2min, Sa=5min, K=288, Sc=1440min (1 día)

```
TIEMPO REAL          TIEMPO DE DATOS              ACCIÓN
─────────────────────────────────────────────────────────────
00:00                2026-09-25 00:00             Inicio
  ↓
  Ejecución 1 (2 min)
  Consume: [2026-09-25 00:00 → 2026-09-26 00:00]
  Procesa: 6,000 lotes
  ↓
00:02                                             Termina
  ↓ Espera Sa=5min
00:07                2026-09-26 00:00             Avanzó 1 día
  ↓
  Ejecución 2 (2 min)
  Consume: [2026-09-26 00:00 → 2026-09-27 00:00]
  Procesa: 6,000 lotes
  ↓
00:09                                             Termina
```

**Resultado**: 2 días simulados en 9 minutos reales (aceleración 320x)

---

## 8. Gestión de Capacidad

### 8.1 Tracking de Vuelos

**Problema**: ¿Cómo saber cuántas maletas tiene un vuelo si se asigna en múltiples ejecuciones?

**Solución**: Solución acumulada global.

**Implementación**:
```java
// CapacityMonitor.calculateAverageFlightOccupancy()
Map<Flight, Integer> flightLoads = new HashMap<>();

// Acumular carga de TODAS las rutas
for (AssignedRoute route : currentSolution.getRoutes().values()) {
    int quantity = route.getBatch().quantity();
    for (Flight flight : route.getFlights()) {
        flightLoads.merge(flight, quantity, Integer::sum);
    }
}

// Calcular ocupación
for (Entry<Flight, Integer> entry : flightLoads.entrySet()) {
    double occupancy = (double) entry.getValue() / entry.getKey().capacity();
    if (occupancy > 1.0) {
        // VIOLACIÓN: Vuelo sobrecargado
    }
}
```

**Ejemplo**:
```
Vuelo: LIMA → MIAMI (2026-09-26 10:00), Capacidad: 330

Ejecución 1 (día 25):
  Ruta A: 200 maletas → Ocupación: 60.6%

Ejecución 2 (día 26):
  Ruta B: 150 maletas → Ocupación acumulada: 106% ❌

Penalización: 20 maletas × 10,000 = 200,000 puntos
```

### 8.2 Indicadores de Semáforo

**TrafficLightIndicator** clasifica métricas en colores:

| Métrica | Verde | Ámbar | Rojo |
|---------|-------|-------|------|
| **Ocupación de vuelos** | < 70% | 70-85% | > 85% |
| **Ocupación de almacenes** | < 70% | 70-85% | > 85% |
| **SLA Compliance** | > 95% | 90-95% | < 90% |

**Uso**:
```java
TrafficLightReport report = indicator.generateReport(solution);
System.out.println("Vuelos: " + report.flightColor());      // GREEN/AMBER/RED
System.out.println("Almacenes: " + report.storageColor());  // GREEN/AMBER/RED
System.out.println("SLA: " + report.slaColor());            // GREEN/AMBER/RED
```

---

## 9. Experimentación y Resultados

### 9.1 Progresión hacia el Colapso

| Carga | Maletas | Fitness GATS | Violaciones | Tiempo | Estado |
|-------|---------|--------------|-------------|--------|--------|
| 54% | 6,971 | -1,545,740 | 0 | 144 seg | ✅ Normal |
| 69% | 8,962 | -1,614,195 | 0 | 221 seg | ✅ Normal |
| 78% | 10,045 | -1,863,880 | 0 | 178 seg | ✅ Normal |
| 93% | 11,964 | +27,619,035 | 78.9 | 273 seg | ❌ **COLAPSO** |

**Conclusión**: Sistema opera hasta **78% de capacidad** (10,045 maletas/día). Colapso entre 78-93%.

### 9.2 Comparación GATS vs Tabu

**Operación Normal (54% carga)**:
```
GATS:  Fitness -1,545,740, Tiempo 144 seg
Tabu:  Fitness -1,510,945, Tiempo 51 seg
Ventaja GATS: 2.30% mejor fitness
```

**Colapso (93% carga)**:
```
GATS:  Fitness +27,619,035, Violaciones 78.9
Tabu:  Fitness +30,256,920, Violaciones 86.8
Ventaja GATS: 8.72% mejor (maneja mejor el estrés)
```

### 9.3 Impacto de Paralelización

| Métrica | Sin Paralelización | Con Paralelización | Mejora |
|---------|-------------------|-------------------|--------|
| Tiempo (54%) | 226 seg | 144 seg | 36% más rápido |
| Fitness | -1,519,200 | -1,545,740 | 1.7% mejor |
| Ventaja vs Tabu | 0.79% | 2.30% | 2.9x mejor |

**Conclusión**: Paralelización logra velocidad + calidad sin sacrificar ninguno.

---

## 10. Consideraciones Técnicas

### 10.1 Requisitos del Sistema

**Mínimo**:
- Java 17+
- 4 núcleos / 8 threads
- 8 GB RAM
- Tiempo esperado: ~200-250 seg/corrida GATS

**Recomendado**:
- Java 17+
- 8 núcleos / 16 threads
- 16 GB RAM
- Tiempo esperado: ~140-180 seg/corrida GATS

**Óptimo**:
- Java 17+
- 16+ núcleos
- 32 GB RAM
- Tiempo esperado: ~100-140 seg/corrida GATS

### 10.2 Escalabilidad

**Paralelización Actual**:
- Usa `parallelStream()` con ForkJoinPool común
- Threads = `Runtime.getRuntime().availableProcessors() - 1`
- Escalabilidad lineal hasta 8 núcleos
- Rendimiento decreciente después de 16 núcleos

**Limitaciones**:
- Memoria: ~2 GB por ejecución (solución + población)
- CPU: Bound por evaluación de fitness (operación más costosa)
- I/O: Carga de datos inicial (~30 archivos, 9.5M lotes)

### 10.3 Optimizaciones Futuras

**Backend**:
- [ ] Paralelizar múltiples ejecuciones (2-4 simultáneas)
- [ ] Cache de rutas frecuentes (LRU cache)
- [ ] Persistencia de soluciones (base de datos)
- [ ] Streaming de resultados (WebSocket)

**Algoritmos**:
- [ ] Adaptive parameter tuning (ajuste dinámico de parámetros)
- [ ] Hybrid operators (más operadores genéticos)
- [ ] Multi-objective optimization (Pareto front)

**Infraestructura**:
- [ ] Containerización (Docker)
- [ ] Orquestación (Kubernetes)
- [ ] Load balancing (múltiples instancias)
- [ ] Monitoring (Prometheus + Grafana)

---

## 📚 Referencias

### Código Fuente Principal

**Algoritmos**:
- `GeneticAlgorithm.java`: Algoritmo genético paralelizado
- `TabuSearch.java`: Búsqueda tabú
- `RouteGenerator.java`: Generación de rutas con BFS

**Ejecución**:
- `Scheduler.java`: Orquestador principal
- `SchedulerFactory.java`: Factory de schedulers configurados

**Evaluación**:
- `SolutionEvaluator.java`: Función fitness
- `RouteValidator.java`: Validación de restricciones
- `CapacityMonitor.java`: Monitoreo de capacidad

**Modelo**:
- `Airport.java`, `Flight.java`, `ShipmentBatch.java`
- `AssignedRoute.java`, `Solution.java`
- `FlightPlan.java`, `ShipmentQueue.java`

### Documentación

- `README.md`: Visión general y guía de inicio
- `ARQUITECTURA_SIMULACION_TIEMPO_REAL.md`: Simulación acelerada
- `InformacionCaso/`: Documentación del problema de negocio

### Datos

- `data/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt`: 30 aeropuertos
- `data/planes_vuelo.txt`: 2,866 vuelos
- `data/_envios_preliminar_/`: 30 archivos, 9.5M lotes, 17.8M maletas

---

**Última actualización**: Abril 2026  
**Versión del documento**: 1.0  
**Equipo**: Equipo 2B - PUCP
