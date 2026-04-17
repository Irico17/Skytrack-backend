# Algoritmos de Optimización

Este documento explica en detalle los algoritmos metaheurísticos utilizados en el sistema de planificación logística.

## 📋 Índice

1. [Visión General](#visión-general)
2. [Algoritmo Genético](#algoritmo-genético)
3. [Búsqueda Tabú](#búsqueda-tabú)
4. [Generación de Rutas](#generación-de-rutas)
5. [Evaluación de Soluciones](#evaluación-de-soluciones)
6. [Configuración y Parámetros](#configuración-y-parámetros)

---

## Visión General

El sistema utiliza un enfoque híbrido de dos fases:

1. **Fase 1 - Algoritmo Genético**: Exploración del espacio de soluciones
2. **Fase 2 - Búsqueda Tabú**: Refinamiento de la mejor solución encontrada

Este enfoque combina la capacidad de exploración global del GA con la intensificación local de la búsqueda tabú.

### Flujo de Optimización

```
Lotes de Envíos
      ↓
┌─────────────────────┐
│ Algoritmo Genético  │ ← Exploración global
│ (20 generaciones)   │
└─────────────────────┘
      ↓
  Mejor Solución
      ↓
┌─────────────────────┐
│  Búsqueda Tabú      │ ← Refinamiento local
│ (50 iteraciones)    │
└─────────────────────┘
      ↓
 Solución Optimizada
```

---

## Algoritmo Genético

### Descripción

El Algoritmo Genético (GA) es una metaheurística inspirada en la evolución natural que mantiene una población de soluciones y las mejora mediante operadores genéticos.

### Componentes

#### 1. Representación (Cromosoma)

Cada individuo es una `Solution` que contiene:
- **Genes**: Mapa de `batchId` → `AssignedRoute`
- **Fitness**: Valor de calidad de la solución

```java
public class Solution {
    private Map<String, AssignedRoute> routes;  // Genes
    private double fitness;                      // Aptitud
}
```

#### 2. Población Inicial

Se genera una población de N individuos aleatorios:

```java
// Configuración
populationSize = 50  // Tamaño de población

// Generación
for (int i = 0; i < populationSize; i++) {
    Solution individual = generateRandomSolution(batches);
    population.add(individual);
}
```

Cada solución aleatoria:
1. Para cada lote, genera una ruta válida usando `RouteGenerator`
2. Evalúa el fitness usando `SolutionEvaluator`

#### 3. Selección por Torneo

Selecciona padres para reproducción mediante torneos:

```java
// Configuración
tournamentSize = 5  // Tamaño del torneo

// Proceso
1. Seleccionar K individuos aleatorios
2. Elegir el mejor (mayor fitness)
3. Repetir para obtener 2 padres
```

**Ventajas**:
- Presión selectiva ajustable (tamaño del torneo)
- Mantiene diversidad
- Eficiente computacionalmente

#### 4. Cruce (Crossover)

Combina dos padres para crear un hijo:

```java
// Cruce de un punto
Solution crossover(Solution parent1, Solution parent2) {
    Solution child = new Solution();
    
    // Dividir lotes en dos grupos
    int splitPoint = batches.size() / 2;
    
    // Primera mitad del padre 1
    for (int i = 0; i < splitPoint; i++) {
        child.addRoute(parent1.getRoute(batches.get(i)));
    }
    
    // Segunda mitad del padre 2
    for (int i = splitPoint; i < batches.size(); i++) {
        child.addRoute(parent2.getRoute(batches.get(i)));
    }
    
    return child;
}
```

#### 5. Mutación

Introduce variación aleatoria en la solución:

```java
// Configuración
mutationRate = 0.1  // 10% de probabilidad

// Proceso
void mutate(Solution solution) {
    for (ShipmentBatch batch : batches) {
        if (random.nextDouble() < mutationRate) {
            // Generar nueva ruta aleatoria para este lote
            AssignedRoute newRoute = routeGenerator.generateRoute(batch);
            solution.replaceRoute(batch.batchId(), newRoute);
        }
    }
}
```

**Tipos de mutación**:
- Regenerar ruta completa para un lote
- Cambiar un vuelo en una ruta existente
- Reordenar vuelos en una ruta

#### 6. Elitismo

Preserva las mejores soluciones entre generaciones:

```java
// Configuración
eliteCount = 5  // Número de élites

// Proceso
1. Ordenar población por fitness
2. Copiar los mejores 'eliteCount' individuos a la nueva generación
3. Completar el resto con hijos (cruce + mutación)
```

### Algoritmo Completo

```java
public Solution optimize(List<ShipmentBatch> batches) {
    // 1. Inicialización
    List<Solution> population = initializePopulation(batches);
    Solution bestSolution = findBest(population);
    
    // 2. Evolución
    for (int gen = 0; gen < generations; gen++) {
        List<Solution> newPopulation = new ArrayList<>();
        
        // 2.1 Elitismo
        newPopulation.addAll(getElites(population, eliteCount));
        
        // 2.2 Generar nueva población
        while (newPopulation.size() < populationSize) {
            // Selección
            Solution parent1 = tournamentSelection(population);
            Solution parent2 = tournamentSelection(population);
            
            // Cruce
            Solution child = crossover(parent1, parent2);
            
            // Mutación
            mutate(child);
            
            // Evaluar
            evaluator.evaluate(child);
            
            newPopulation.add(child);
        }
        
        // 2.3 Reemplazo
        population = newPopulation;
        
        // 2.4 Actualizar mejor
        Solution currentBest = findBest(population);
        if (currentBest.getFitness() > bestSolution.getFitness()) {
            bestSolution = currentBest;
        }
        
        System.out.println("Generation " + gen + " - Best Fitness: " + 
                          bestSolution.getFitness());
    }
    
    return bestSolution;
}
```

### Parámetros Recomendados

| Parámetro | Valor Recomendado | Descripción |
|-----------|-------------------|-------------|
| `populationSize` | 50 | Tamaño de la población |
| `generations` | 20 | Número de generaciones |
| `mutationRate` | 0.1 | Probabilidad de mutación (10%) |
| `tournamentSize` | 5 | Tamaño del torneo |
| `eliteCount` | 5 | Número de élites preservadas |

---

## Búsqueda Tabú

### Descripción

La Búsqueda Tabú es una metaheurística de búsqueda local que evita ciclos mediante una lista tabú de movimientos prohibidos.

### Componentes

#### 1. Solución Inicial

Recibe la mejor solución del Algoritmo Genético:

```java
Solution initialSolution = geneticAlgorithm.optimize(batches);
Solution currentSolution = initialSolution.copy();
Solution bestSolution = initialSolution.copy();
```

#### 2. Lista Tabú

Mantiene movimientos recientes prohibidos:

```java
// Configuración
tabuTenure = 10  // Duración en lista tabú

// Estructura
Queue<Move> tabuList = new LinkedList<>();

class Move {
    String batchId;
    AssignedRoute oldRoute;
    int iteration;  // Cuándo se agregó
}
```

#### 3. Vecindario

Genera soluciones vecinas mediante movimientos:

```java
// Configuración
neighborhoodSize = 20  // Número de vecinos a explorar

// Tipos de movimientos
enum MoveType {
    REGENERATE_ROUTE,    // Generar nueva ruta para un lote
    SWAP_ROUTES,         // Intercambiar rutas de dos lotes
    MODIFY_FLIGHT        // Cambiar un vuelo en una ruta
}

// Generación de vecinos
List<Solution> generateNeighborhood(Solution current) {
    List<Solution> neighbors = new ArrayList<>();
    
    for (int i = 0; i < neighborhoodSize; i++) {
        Solution neighbor = current.copy();
        
        // Seleccionar lote aleatorio
        ShipmentBatch batch = selectRandomBatch();
        
        // Aplicar movimiento
        AssignedRoute newRoute = routeGenerator.generateRoute(batch);
        neighbor.replaceRoute(batch.batchId(), newRoute);
        
        // Evaluar
        evaluator.evaluate(neighbor);
        
        neighbors.add(neighbor);
    }
    
    return neighbors;
}
```

#### 4. Criterio de Aspiración

Permite movimientos tabú si mejoran la mejor solución conocida:

```java
boolean isAcceptable(Move move, Solution neighbor) {
    // Criterio de aspiración
    if (neighbor.getFitness() > bestSolution.getFitness()) {
        return true;  // Aceptar aunque esté en lista tabú
    }
    
    // Verificar si está en lista tabú
    return !isTabu(move);
}
```

### Algoritmo Completo

```java
public Solution refine(Solution initialSolution, List<ShipmentBatch> batches) {
    Solution currentSolution = initialSolution.copy();
    Solution bestSolution = initialSolution.copy();
    Queue<Move> tabuList = new LinkedList<>();
    
    for (int iter = 0; iter < maxIterations; iter++) {
        // 1. Generar vecindario
        List<Solution> neighbors = generateNeighborhood(currentSolution);
        
        // 2. Encontrar mejor vecino aceptable
        Solution bestNeighbor = null;
        Move bestMove = null;
        
        for (Solution neighbor : neighbors) {
            Move move = getMove(currentSolution, neighbor);
            
            if (isAcceptable(move, neighbor)) {
                if (bestNeighbor == null || 
                    neighbor.getFitness() > bestNeighbor.getFitness()) {
                    bestNeighbor = neighbor;
                    bestMove = move;
                }
            }
        }
        
        // 3. Mover a mejor vecino
        if (bestNeighbor != null) {
            currentSolution = bestNeighbor;
            
            // 4. Actualizar lista tabú
            tabuList.add(bestMove);
            if (tabuList.size() > tabuTenure) {
                tabuList.poll();  // Eliminar el más antiguo
            }
            
            // 5. Actualizar mejor solución
            if (currentSolution.getFitness() > bestSolution.getFitness()) {
                bestSolution = currentSolution.copy();
            }
        }
        
        System.out.println("Iteration " + iter + " - Best Fitness: " + 
                          bestSolution.getFitness());
    }
    
    return bestSolution;
}
```

### Parámetros Recomendados

| Parámetro | Valor Recomendado | Descripción |
|-----------|-------------------|-------------|
| `maxIterations` | 50 | Número de iteraciones |
| `tabuTenure` | 10 | Duración en lista tabú |
| `neighborhoodSize` | 20 | Tamaño del vecindario |

---

## Generación de Rutas

### RouteGenerator

Genera rutas válidas para un lote usando búsqueda A*:

```java
public AssignedRoute generateRoute(ShipmentBatch batch) {
    Airport origin = batch.origin();
    Airport destination = batch.destination();
    
    // Búsqueda A* en el grafo de vuelos
    List<Flight> path = findPath(origin, destination);
    
    if (path.isEmpty()) {
        return null;  // No hay ruta válida
    }
    
    // Crear ruta asignada
    return new AssignedRoute(
        batch.batchId(),
        batch,
        path,
        calculateArrivalTime(path),
        calculateSlack(path, batch.deadline())
    );
}
```

### Algoritmo A*

```java
private List<Flight> findPath(Airport origin, Airport destination) {
    PriorityQueue<SearchNode> openSet = new PriorityQueue<>();
    Set<Airport> closedSet = new HashSet<>();
    
    // Nodo inicial
    SearchNode start = new SearchNode(origin, null, null, 0);
    openSet.add(start);
    
    while (!openSet.isEmpty()) {
        SearchNode current = openSet.poll();
        
        // ¿Llegamos al destino?
        if (current.airport.equals(destination)) {
            return reconstructPath(current);
        }
        
        closedSet.add(current.airport);
        
        // Explorar vuelos desde este aeropuerto
        for (Flight flight : flightPlan.getFlightsFrom(current.airport)) {
            Airport next = flight.destination();
            
            if (closedSet.contains(next)) {
                continue;
            }
            
            // Calcular costo
            double g = current.g + flight.duration();
            double h = estimateDistance(next, destination);
            double f = g + h;
            
            SearchNode neighbor = new SearchNode(next, current, flight, g);
            neighbor.f = f;
            
            openSet.add(neighbor);
        }
    }
    
    return Collections.emptyList();  // No hay ruta
}
```

### Heurística

La heurística estima la distancia restante:

```java
private double estimateDistance(Airport from, Airport to) {
    // Heurística: diferencia de zonas horarias como proxy de distancia
    return Math.abs(from.utcOffset() - to.utcOffset());
}
```

---

## Evaluación de Soluciones

### SolutionEvaluator

Calcula el fitness de una solución:

```java
public double evaluate(Solution solution) {
    double penalties = 0;
    double rewards = 0;
    
    // 1. Penalizaciones
    penalties += calculateFlightCapacityPenalties(solution);
    penalties += calculateStorageCapacityPenalties(solution);
    penalties += calculateSLAPenalties(solution);
    penalties += calculateLayoverPenalties(solution);
    
    // 2. Premios
    rewards += calculateSlackRewards(solution);
    rewards += calculateUnusedFlightRewards(solution);
    
    // 3. Fitness = Premios - Penalizaciones
    double fitness = rewards - penalties;
    solution.setFitness(fitness);
    
    return fitness;
}
```

### Penalizaciones

#### 1. Capacidad de Vuelos

```java
private double calculateFlightCapacityPenalties(Solution solution) {
    double penalty = 0;
    Map<String, Integer> flightLoads = new HashMap<>();
    
    // Calcular carga de cada vuelo
    for (AssignedRoute route : solution.getRoutes().values()) {
        for (Flight flight : route.flights()) {
            String flightId = flight.flightId();
            int load = flightLoads.getOrDefault(flightId, 0);
            flightLoads.put(flightId, load + route.batch().quantity());
        }
    }
    
    // Penalizar excesos
    for (Map.Entry<String, Integer> entry : flightLoads.entrySet()) {
        Flight flight = flightPlan.getFlight(entry.getKey());
        int load = entry.getValue();
        int capacity = flight.capacity();
        
        if (load > capacity) {
            int excess = load - capacity;
            penalty += excess * 1000;  // Penalización fuerte
        }
    }
    
    return penalty;
}
```

#### 2. Capacidad de Almacén

```java
private double calculateStorageCapacityPenalties(Solution solution) {
    double penalty = 0;
    
    // Simular eventos de almacenamiento
    List<StorageEvent> events = simulateStorage(solution);
    
    // Verificar capacidad en cada momento
    Map<String, Integer> currentLoad = new HashMap<>();
    
    for (StorageEvent event : events) {
        String airportCode = event.airportCode();
        Airport airport = airportManager.getAirport(airportCode);
        
        if (event.type() == StorageEventType.ARRIVAL) {
            currentLoad.merge(airportCode, event.quantity(), Integer::sum);
        } else {
            currentLoad.merge(airportCode, -event.quantity(), Integer::sum);
        }
        
        int load = currentLoad.getOrDefault(airportCode, 0);
        if (load > airport.storageCapacity()) {
            int excess = load - airport.storageCapacity();
            penalty += excess * 500;  // Penalización moderada
        }
    }
    
    return penalty;
}
```

#### 3. Violaciones de SLA

```java
private double calculateSLAPenalties(Solution solution) {
    double penalty = 0;
    
    for (AssignedRoute route : solution.getRoutes().values()) {
        if (!route.meetsSLA()) {
            // Penalización proporcional al retraso
            long delayHours = route.delayHours();
            penalty += delayHours * 100;
        }
    }
    
    return penalty;
}
```

#### 4. Tiempo de Escala

```java
private double calculateLayoverPenalties(Solution solution) {
    double penalty = 0;
    
    for (AssignedRoute route : solution.getRoutes().values()) {
        List<Flight> flights = route.flights();
        
        for (int i = 0; i < flights.size() - 1; i++) {
            Flight current = flights.get(i);
            Flight next = flights.get(i + 1);
            
            long layoverMinutes = calculateLayover(current, next);
            
            if (layoverMinutes < 30) {
                penalty += (30 - layoverMinutes) * 10;  // Muy corto
            } else if (layoverMinutes > 180) {
                penalty += (layoverMinutes - 180) * 5;   // Muy largo
            }
        }
    }
    
    return penalty;
}
```

### Premios

#### 1. Holgura de Tiempo

```java
private double calculateSlackRewards(Solution solution) {
    double reward = 0;
    
    for (AssignedRoute route : solution.getRoutes().values()) {
        if (route.meetsSLA()) {
            long slackHours = route.slackHours();
            reward += slackHours * 10;  // Premio por holgura
        }
    }
    
    return reward;
}
```

#### 2. Vuelos No Utilizados

```java
private double calculateUnusedFlightRewards(Solution solution) {
    int totalFlights = flightPlan.getTotalFlights();
    int usedFlights = countUsedFlights(solution);
    int unusedFlights = totalFlights - usedFlights;
    
    return unusedFlights * 50;  // Premio por eficiencia
}
```

---

## Configuración y Parámetros

### Configuración Estándar

Para operación normal (Ta = 1 minuto):

```java
// Algoritmo Genético
AlgorithmConfig gaConfig = new AlgorithmConfig();
gaConfig.setInt("populationSize", 50);
gaConfig.setInt("generations", 20);
gaConfig.setDouble("mutationRate", 0.1);
gaConfig.setInt("tournamentSize", 5);
gaConfig.setInt("eliteCount", 5);

// Búsqueda Tabú
AlgorithmConfig tabuConfig = new AlgorithmConfig();
tabuConfig.setInt("maxIterations", 50);
tabuConfig.setInt("tabuTenure", 10);
tabuConfig.setInt("neighborhoodSize", 20);
```

### Configuración Rápida

Para cumplir Ta estricto (< 1 minuto):

```java
// Algoritmo Genético (reducido)
gaConfig.setInt("populationSize", 10);
gaConfig.setInt("generations", 5);
gaConfig.setInt("tournamentSize", 3);
gaConfig.setInt("eliteCount", 2);

// Búsqueda Tabú (reducida)
tabuConfig.setInt("maxIterations", 15);
tabuConfig.setInt("tabuTenure", 7);
tabuConfig.setInt("neighborhoodSize", 8);
```

### Configuración Intensiva

Para mejor calidad (sin límite de tiempo):

```java
// Algoritmo Genético (intensivo)
gaConfig.setInt("populationSize", 100);
gaConfig.setInt("generations", 50);
gaConfig.setInt("tournamentSize", 7);
gaConfig.setInt("eliteCount", 10);

// Búsqueda Tabú (intensiva)
tabuConfig.setInt("maxIterations", 100);
tabuConfig.setInt("tabuTenure", 15);
tabuConfig.setInt("neighborhoodSize", 50);
```

---

## Complejidad Computacional

### Algoritmo Genético

- **Inicialización**: O(P × B × R)
  - P = tamaño población
  - B = número de lotes
  - R = costo de generar ruta (A*)

- **Por generación**: O(P × B × E)
  - E = costo de evaluación

- **Total**: O(G × P × B × E)
  - G = número de generaciones

### Búsqueda Tabú

- **Por iteración**: O(N × B × E)
  - N = tamaño vecindario

- **Total**: O(I × N × B × E)
  - I = número de iteraciones

### Tiempo Estimado

Para 50 lotes:
- GA (20 gen, pop 50): ~10-15 segundos
- Tabú (50 iter, vecindario 20): ~5-8 segundos
- **Total**: ~15-23 segundos

---

## Mejoras Futuras

1. **Paralelización**: Evaluar individuos en paralelo
2. **Operadores Adaptativos**: Ajustar mutationRate dinámicamente
3. **Memoria a Largo Plazo**: En búsqueda tabú
4. **Hibridación**: Combinar con otros algoritmos (Simulated Annealing, ACO)
5. **Aprendizaje**: Usar soluciones pasadas para mejorar inicialización
