# Análisis Técnico Crítico: Simulación Operación Día a Día

La simulación **Operación Día a Día (Scenario: `DAY_TO_DAY`)** modela el sistema de logística de maletas en tiempo real ($K=1$), donde los envíos son creados de manera dinámica y transaccional por el operador desde el panel web, y las cancelaciones de vuelos se registran para fechas específicas, forzando la replanificación de las maletas afectadas.

A continuación, se presenta un análisis crítico completo de su estado actual (end-to-end, tanto en frontend como backend), detallando los fallos y bugs identificados, y la especificación técnica requerida para dejarlos resueltos.

---

## 1. Mapa de Integración y Flujo de Datos

El siguiente diagrama detalla la integración transaccional entre los componentes del frontend y las APIs REST / WebSockets del backend en la operación Día a Día:

```
[ FRONTEND ]                                             [ BACKEND ]
     │                                                        │
     │ 1. POST /api/simulations/{id}/shipments                │
     ├───────────────────────────────────────────────────────>│ 
     │   IngressTime = simClock.toISOString()                │ 2. Encolar en ShipmentQueue
     │                                                        │    (Indexado por IngressTime)
     │                                                        │
     │ 3. POST /api/simulations/{id}/flights/{id}/cancel      │
     ├───────────────────────────────────────────────────────>│ 4. Registrar en FlightPlan
     │   Day = YYYY-MM-DD (Ej. 2026-05-31)                    │ 5. Reroutear con Replanner
     │                                                        │ 6. Retornar ReplanResultDTO
     │<───────────────────────────────────────────────────────┤
     │   ReplanResultDTO (Lotes replanificados)               │ 7. ¡BUG! No se emite
     │                                                        │    CYCLE_UPDATE al instante
     │                                                        │
     │ 8. WebSocket: CYCLE_UPDATE                             │ 9. Ciclo de Planificación
     │<───────────────────────────────────────────────────────┤    Cada Sa = 5 minutos:
     │   (Refresca vuelos, ocupación y mapa)                  │    Consume cola en [T, T+Sc)
```

---

## 2. Diagnóstico de Bugs Críticos (Backend & Frontend)

### Bug A: Condición de Carrera en la Cola de Envío por Deriva del Reloj (Race Condition)
* **Ubicación**: [ShipmentQueue.java:L66-L89](file:///c:/Users/Irico/Documents/DP1/scheduling-core/src/main/java/com/equipo2b/scheduler/model/ShipmentQueue.java#L66-L89)
* **El Problema**: El backend consume los envíos en una ventana temporal estricta de planificación `[windowStart, windowEnd)` (Sc = 5 minutos) usando:
  ```java
  var windowMap = timeIndex.subMap(start, true, end, false);
  ```
  Si existe un retraso en la red o una ligera deriva entre el reloj del frontend (`simClock`) y el cursor de planificación del backend (`windowStart`), el frontend podría registrar un envío con un `ingressTime` ligeramente anterior a `windowStart` (por ejemplo, 2 segundos en el pasado). 
  Al estar fuera de la ventana actual y habiéndose completado ya el ciclo anterior, **ese lote nunca será consumido por el planificador** y se quedará atrapado en estado `PENDING` en la cola indefinidamente.
* **Solución Técnica**: El planificador no debe limitar el consumo al inicio de la ventana. Debe consumir **todo lo acumulado en el pasado hasta la fecha límite actual**.
  Modificar el consumo en `ShipmentQueue.java` para utilizar `headMap`:
  ```java
  // Consume desde el inicio de los tiempos hasta el final de la ventana (exclusivo)
  var windowMap = timeIndex.headMap(end, false);
  ```

### Bug B: Coincidencia de IDs Ajustados en la Cancelación
* **Ubicación**: [SimulationController.java:L333-L340](file:///c:/Users/Irico/Documents/DP1/scheduling-core/src/main/java/com/equipo2b/scheduler/execution/SimulationController.java#L333-L340)
* **El Problema**: La cancelación llama al controlador con el ID ajustado (ej. `"FL001-D150"`). El controlador busca este ID directamente en `flightPlan.getAllFlights()`, el cual solo contiene los **vuelos base** sin proyectar (ej. `"FL001"`). Al no coincidir, el método retorna `null`, y el controlador aborta con error sin ejecutar el replanificador.
* **Solución Técnica**: Modificar `findFlightById` para extraer el offset `-Dxx`, ubicar el vuelo base y retornar una instancia proyectada a la fecha correspondiente:
  ```java
  private Flight findFlightById(String flightId) {
      String baseId = flightId;
      long dayOffset = 0;
      if (flightId.contains("-D")) {
          int idx = flightId.lastIndexOf("-D");
          baseId = flightId.substring(0, idx);
          dayOffset = Long.parseLong(flightId.substring(idx + 2));
      }
      for (Flight flight : flightPlan.getAllFlights()) {
          if (flight.flightId().equals(baseId)) {
              if (dayOffset == 0) return flight;
              return new Flight(
                  flightId, flight.origin(), flight.destination(),
                  flight.departureTime().plusDays(dayOffset),
                  flight.arrivalTime().plusDays(dayOffset),
                  flight.capacity(), flight.type()
              );
          }
      }
      return null;
  }
  ```

### Bug C: Desactualización de la UI tras una Cancelación (Falta de Broadcast)
* **Ubicación**: [SimulationController.java:L253](file:///c:/Users/Irico/Documents/DP1/scheduling-core/src/main/java/com/equipo2b/scheduler/execution/SimulationController.java#L253)
* **El Problema**: Al cancelar un vuelo y ejecutar la replanificación, el controlador actualiza el estado de las rutas internamente pero **no emite ninguna notificación por WebSocket de inmediato**. En consecuencia, el frontend continúa mostrando las maletas volando en la ruta cancelada en el mapa, y los cambios no se ven hasta el siguiente ciclo periódico de planificación (que en `DAY_TO_DAY` toma 5 minutos reales).
* **Solución Técnica**: Emitir un `CYCLE_UPDATE` a través del WebSocket handler inmediatamente después de que finalice la replanificación en `registerCancellation`.
  ```java
  if (listener != null) {
      listener.onCycleCompleted(getStatus(), currentSolution);
  }
  ```

### Bug D: Pérdida de Métricas del API en `CancellationService`
* **Ubicación**: [CancellationService.java:L69-L80](file:///c:/Users/Irico/Documents/DP1/scheduling-core/src/main/java/com/equipo2b/scheduler/service/CancellationService.java#L69-L80)
* **El Problema**: El controlador ejecuta el replanificador de emergencia y genera un `ReplanResult` con la lista de lotes replanificados y los no replanificables (huérfanos), pero su firma es `void`. El servicio de cancelación se ve obligado a retornar listas vacías, provocando que en la UI siempre se registre `0 lotes replanificados`.
* **Solución Técnica**: Cambiar el retorno de `SimulationController.registerCancellation(flightId)` a `ReplanResult` y propagar dicho valor a través de `CancellationService`:
  ```java
  SimulationController controller = simulationService.getActiveController();
  ReplanResult result = controller.registerCancellation(adjustedFlightId);
  return DTOMapper.toReplanResultDTO(adjustedFlightId, result);
  ```

---

## 3. Plan de Modificaciones Requeridas

### Backend (Spring Boot)
1. **[SimulationService.java](file:///c:/Users/Irico/Documents/DP1/scheduling-core/src/main/java/com/equipo2b/scheduler/service/SimulationService.java)**: Evitar la carga de maletas desde archivos al inicio si `scenario == ScenarioType.DAY_TO_DAY`, asignando `batches = new ArrayList<>()`.
2. **[ShipmentQueue.java](file:///c:/Users/Irico/Documents/DP1/scheduling-core/src/main/java/com/equipo2b/scheduler/model/ShipmentQueue.java)**: Cambiar `subMap` por `headMap` en `consumeShipments` para evitar la pérdida de lotes por deriva horaria.
3. **[SimulationController.java](file:///c:/Users/Irico/Documents/DP1/scheduling-core/src/main/java/com/equipo2b/scheduler/execution/SimulationController.java)**:
   * Corregir `findFlightById` para interpretar los IDs proyectados con sufijo `-Dxx`.
   * Hacer que `registerCancellation` retorne `ReplanResult` e invoque al listener para emitir el broadcast del WebSocket en caliente.

### Frontend (React)
1. **Tolerancia Horaria**: El frontend debe asegurar el envío del `ingressTime` con precisión. Si el usuario ingresa una maleta, se debe forzar una recarga manual de la solución (`refreshSolution`) a los 1000 ms para reflejar las nuevas rutas asignadas por el planificador sin esperar el WebSocket.
2. **Alertas en Mapa**: Al recibir la confirmación de la cancelación de un vuelo, el mapa debe aplicar inmediatamente el color de alerta (`#FF4D4D`) a la línea del tramo cancelado y removerlo del renderizador en el siguiente tick.
