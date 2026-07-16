# Análisis Técnico Crítico: Simulación de Colapso

La simulación de **Colapso (Scenario: `COLLAPSE_SIMULATION`)** corre con un factor de aceleración moderado ($K=75$) y no tiene un límite de tiempo fijo. Su objetivo es ejecutar la operación de manera indefinida hasta que la red logística de aeropuertos, vuelos o algoritmos se degrade hasta un punto crítico irreversible (punto de colapso).

A continuación, se presenta un análisis crítico completo de su estado actual (end-to-end, tanto en frontend como backend), detallando los fallos de flujo lógico identificados, los criterios de colapso, y la implementación requerida.

---

## 1. Mapa de Integración y Flujo de Datos

El siguiente flujo describe la secuencia lógica de control para evaluar el colapso operativo:

```
[ CICLO DE SIMULACIÓN ]
         │
         │ 1. Termina iteración de planificación (Ciclo N)
         ▼
[ CONTROLADOR / SCHEDULER ]
         │
         ├─► A. Medir tiempo de ejecución del algoritmo (¿Es > Ta?) ──┐
         │                                                            │
         ├─► B. Evaluar estado de almacenes en aeropuertos            │
         │      (¿Más del 50% de almacenes críticos/llenos?) ─────────┼─► COLAPSO
         │                                                            │   (Detiene simulación)
         ├─► C. Evaluar entregas retrasadas (SLA)                      │
         │      (¿Más del 25%-40% de lotes retrasados?) ──────────────┘
         │
         │ 2. Si NO colapsa: avanza tiempo simulado y espera Sa min reales
         ▼
[ CONTINÚA SIMULACIÓN ]
```

---

## 2. Diagnóstico de Bugs Críticos (Backend & Frontend)

### Bug A: Inanición de Envíos y Bucle Infinito sin Colapso (Starvation)
* **El Problema**: El backend carga los envíos iniciales con `dataService.loadAllShipments(...)`. Sin embargo, el archivo estático de envíos (`orders.txt`) solo contiene datos planificados para **5 días**. 
  Al correr la simulación de colapso indefinidamente (2.5 meses simulados a $K=75$), **después del Día 5 no ingresan nuevas maletas a la cola**. Los aeropuertos se vacían paulatinamente, los vuelos viajan con carga cero y el sistema se estabiliza de forma artificial, provocando que la simulación corra infinitamente sin colapsar jamás.
* **Solución Técnica**: Implementar un generador recursivo o bucle en la cola de envíos. Si el cursor del tiempo simulado supera el Día 5, el backend debe clonar y re-encolar los lotes del archivo de órdenes aplicando un offset de tiempo (`daysElapsed`) a su fecha de ingreso y plazo límite (deadline), incrementando el volumen de maletas de forma acumulativa (por ejemplo, $+10\%$ cada 5 días) para forzar el colapso de la capacidad física de la red.

### Bug B: Falta de Archivo de Resultados para Colapso (Error 404)
* **Ubicación**: [SimulationService.java:L480](file:///c:/Users/Irico/Documents/DP1/scheduling-core/src/main/java/com/equipo2b/scheduler/service/SimulationService.java#L480)
* **El Problema**: Al colapsar la simulación, el backend emite `SIMULATION_FINISHED`. El frontend recibe este evento y procede a consultar los resultados finales en `/results`. Pero el backend solo escribe el archivo JSON de exportación si `currentScenario == ScenarioType.PERIOD_SIMULATION`, dejando al modo de colapso sin archivo físico. La petición REST del frontend retorna un **404 Not Found** y el modal de resultados falla al cargarse.
* **Solución Técnica**: Modificar la condición en `SimulationService.java` para que exporte resultados en ambos escenarios:
  ```java
  if (currentScenario == ScenarioType.PERIOD_SIMULATION || currentScenario == ScenarioType.COLLAPSE_SIMULATION) {
      resultExporter.exportResults(...);
  }
  ```

### Bug C: Tamaño de Instantáneas Fijo a 5 Días
* **Ubicación**: [SimulationResultExporter.java:L119](file:///c:/Users/Irico/Documents/DP1/scheduling-core/src/main/java/com/equipo2b/scheduler/service/SimulationResultExporter.java#L119)
* **El Problema**: El exportador itera rígidamente `for (int day = 1; day <= 5; day++)` al compilar los snapshots diarios. Si el colapso toma 12 días reales de ejecución simulada, los datos de los días 6 al 12 quedan fuera del reporte.
* **Solución Técnica**: Calcular dinámicamente los días transcurridos (`daysElapsed`) basados en el tiempo simulado final y proyectar las instantáneas en correspondencia:
  ```java
  int totalDays = (int) Math.ceil(daysElapsed);
  for (int day = 1; day <= totalDays; day++) { ... }
  ```

### Bug D: Ignorar la Señal de Término de Colapso en el Frontend
* **Ubicación**: [useSimulation.ts:L361](file:///C:/Users/Irico/Documents/DP1/Skytrack-Frontend/src/app/hooks/useSimulation.ts#L361)
* **El Problema**: Al recibir `SIMULATION_FINISHED` en modo colapso, el frontend detiene la animación del reloj pero no actualiza la variable de estado `collapseComplete` a `true` ni calcula las métricas operacionales reales, impidiendo la transición visual a la vista de resultados.
* **Solución Técnica**: Detectar en el manejador del WebSocket si el modo activo es `collapse` y forzar la actualización de los estados correspondientes:
  ```typescript
  if (mode === 'collapse') {
      setCollapseComplete(true);
      // Calcular métricas operativas reales basadas en el estado final de shipments y airports
  }
  ```

---

## 3. Especificación de los Criterios de Colapso y Solución Técnica

El colapso debe ser gatillado en el backend y frontend mediante tres criterios de degradación:

### Criterio A: Tiempo del Algoritmo Supera el Límite ($Ta = 2$ min reales)
* **Efecto**: Si el optimizador de rutas del scheduler toma más de 120,000 ms en resolver un ciclo de planificación, se interrumpe la simulación.
* **Código en el Backend** (`SimulationController.java`):
  ```java
  long algorithmMs = System.currentTimeMillis() - cycleStartRealMs;
  if (algorithmMs > (scenario.getTa() * 60_000L)) {
      System.out.println("⚠️  COLAPSO POR COMPLEJIDAD ALGORÍTMICA (Tiempo ciclo > 2 min)");
      this.completedNaturally = true;
      break;
  }
  ```

### Criterio B: Más del 50% de los Almacenes Saturados
* **Efecto**: Si más de la mitad de los aeropuertos de la red tienen capacidad crítica (ocupación >= 90%).
* **Código en el Backend** (`CollapseDetector.java`):
  ```java
  public boolean isWarehouseCollapsed(AirportManager manager) {
      List<Airport> airports = manager.getAirports();
      long fullAirports = airports.stream()
          .filter(a -> (a.occupancy() * 100.0 / a.storageCapacity()) >= 90.0)
          .count();
      return airports.size() > 0 && ((double) fullAirports / airports.size()) > 0.50;
  }
  ```

### Criterio C: Tasa de Retraso de SLA superior al 25% o 40%
* **Efecto**: Si los lotes que superaron su plazo de entrega más los no ruteables superan el porcentaje configurado.
* **Código en el Backend** (`SimulationController.java`):
  ```java
  // Corregir la actualización de batchesFailed acumulados en cada ciclo
  long delayedCount = currentSolution.getRoutes().values().stream()
      .filter(route -> !route.meetsSLA())
      .count();
  int unroutedCount = scheduler.getPendingCount();
  this.batchesFailed = (int) (delayedCount + unroutedCount);
  ```

---

## 4. Mapeo de Métricas de Colapso en el Frontend

Para evitar que el componente `CollapseResults.tsx` muestre datos hardcodeados tras un colapso real, se deben calcular las siguientes métricas en caliente usando las variables del estado final del frontend:

1. **Tiempo hasta el Colapso (`timeToCollapse`)**:
   $$\text{Duración} = \text{simClock (final)} - \text{startDate}$$
   Convertir a formato de texto legible (ej. `"14 días, 6 horas"`).
2. **Resilience Score (Puntaje de Resiliencia)**:
   $$\text{Score} = \left(\frac{\text{Envíos a Tiempo}}{\text{Total Envíos}} \times 50\right) + \left(\frac{\text{Aeropuertos Normales}}{\text{Total Aeropuertos}} \times 30\right) + (\text{hasReplanned} ? 20 : 5)$$
3. **Pico de Congestión (`peakCongestion`)**:
   $$\text{Max} \left( \frac{\text{Ocupación de Aeropuerto}}{\text{Capacidad de Aeropuerto}} \times 100 \right)$$
   Identificando el ID del aeropuerto pico (`peakAirport`).
4. **Pérdida de Envíos (`shipmentsLost`)**:
   Conteo de `shipments` en tránsito (`progress < 1.0`) que quedaron bloqueados en aeropuertos con estado `critical` al momento del colapso.
