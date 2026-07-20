# Evaluación incremental del planificador — cómo funciona ahora

## 1. El problema que existía

Cada ciclo de planificación, `Scheduler.executePlanningCycle` acumula las rutas nuevas sobre
`currentSolution` (que contiene TODAS las rutas desde el inicio de la simulación) y luego
recalculaba el fitness, validaba, dividía por capacidad y clasificaba reintentos **recorriendo
esa solución acumulada completa, entera, cada vez** — con 63,000 rutas acumuladas (~77 días
simulados desde enero 2026 a volumen bajo), esos recorridos por sí solos hacían que un ciclo
pasara de tomar 5-10s a tomar 60-117s, muy por encima del presupuesto (Ta=30s, Sa=45s). El
algoritmo (GA/Tabú) se quedaba sin tiempo, degradaba su calidad, y eso adelantaba el colapso
real — no por falta de capacidad logística, sino por agotamiento de presupuesto de cómputo.

Arrancando desde una fecha de alto volumen real (dic-2027, ~14,000 envíos/día), el mismo techo
se golpeaba en días en vez de meses — exactamente el patrón reportado ("mi colapso llega solo
hasta enero, los otros equipos llegan hasta agosto 2028").

## 2. Principio de diseño

El corte entre "hay que seguir vigilando esto" y "esto ya quedó fijo para siempre" es por
**tiempo físico** (¿ya pasó?), no por cantidad de ciclos — un número de ciclos fijo cortaría
rutas legítimamente activas (una ruta con escalas y SLA de 48h puede tardar ~32 ciclos en
asentar). Con el corte por tiempo, lo que se recorre cada ciclo queda acotado por **cuántos
vuelos siguen sin salir y cuántos aeropuertos existen** — no por cuánto tiempo simulado ha
pasado. Verificado empíricamente: a 85,000 rutas acumuladas, el costo extra por ciclo (más
allá de GA/Tabú) sigue en ~110-130ms, igual que a 761 rutas.

Pero "ya pasó" significa cosas distintas según qué se esté calculando:

| Término | ¿Cuándo es seguro sellarlo para siempre? | Por qué |
|---|---|---|
| SLA / escala / holgura de una ruta | En el momento en que la ruta se crea | Depende solo de la ruta misma, nunca de otras |
| Capacidad de un vuelo | Cuando su hora de salida ya pasó | Es una suma (conmutativa) — no importa el orden en que se acumule, pero puede seguir creciendo mientras el vuelo no salga |
| Exceso puntual de un evento de almacén | Cuando ningún evento aún no visto puede tener fecha anterior (marca de agua) | NO es conmutativo — el excedente en un instante depende de la ocupación acumulada hasta ese instante en orden cronológico real |
| Pico de ocupación de un aeropuerto (término convexo + desbalance global) | Nunca — se mantiene un máximo corriente para siempre | El pico histórico pudo alcanzarse en cualquier ciclo pasado; hay que poder decir "el pico más alto que vi hasta ahora", así que el mapa (chico, uno por aeropuerto) vive mientras dure la simulación |

## 3. Piezas nuevas

### `AccumulatedFitnessTracker` (paquete `logic`)

Vive dentro de `Scheduler` (una instancia por simulación) y lleva:

- **Acumulados sellados** (`sealedIntrinsic`, `sealedFlight`, `sealedStorage`): números que ya
  no se vuelven a tocar.
- **`activeFlightLoad`**: vuelos que aún no salen → maletas asignadas (mapa chico, acotado por
  cuántos vuelos recientes siguen sin salir).
- **`peakOccupancyPerAirport`**: pico histórico por aeropuerto, nunca se borra, solo crece.
- **`pendingEvents`**: eventos de almacén de rutas ya creadas pero aún no sellados, esperando
  que la marca de agua los alcance (acotado igual que `activeFlightLoad`).

Métodos clave: `recordSettledRoutes` (sella SLA+escala+vuelo de una ruta que acaba de asentar),
`settleExpiredFlights` (cierra vuelos cuya salida ya pasó, con su carga final), 
`advanceStorageWatermark` (sella eventos de almacén con fecha anterior a la marca de agua, en
orden cronológico), `currentFitness` (recalcula el total fresco cada vez, pero solo sobre los
mapas chicos + lo que sigue en frente caliente — nunca sobre la historia).

Las fórmulas de penalización/premio se **extrajeron** de `SolutionEvaluator` a métodos
reutilizables (`calculateFlightPenaltyFor`, `calculateStorageConvexPenaltyFor`,
`calculateIntrinsicRoutePenalty`, `replayStorageEvents`) para que la ruta "completa" (usada por
GA/Tabú sobre lotes chicos) y la ruta "incremental" (usada por `Scheduler` sobre la solución
acumulada) sean matemáticamente **la misma fórmula**, nunca dos implementaciones que puedan
divergir.

### `frenteCaliente` (campo de `Scheduler`)

Rutas creadas pero cuyo **primer vuelo aún no sale** — las únicas que
`applyCapacityAwareSplitting` puede seguir modificando (partir, reemplazar) en este ciclo o en
futuros. Mientras una ruta está acá, su contribución al fitness se recalcula **fresca** cada
vez (barato, porque el conjunto es chico) — nunca se sella, porque splitting todavía podría
cambiar su cantidad.

Cuando el primer vuelo de una ruta ya salió, se "promueve": sale de `frenteCaliente` y entra al
tracker para siempre (splitting ya nunca la va a tocar, mismo criterio que usa
`applyCapacityAwareSplitting` para decidir qué puede modificar).

### La marca de agua de eventos de almacén

Es la pieza más delicada. Un evento de almacén (llegada/salida de maletas) **no es conmutativo**:
el excedente en un instante depende del ORDEN cronológico real de todos los eventos. Sellar el
evento de una ruta antes de tiempo podría procesarlo fuera de orden respecto a otra ruta que
todavía sigue pendiente — dando un total incorrecto (un bug real que el test de invariante
encontró: un evento de llegada, con fecha temprana, se sellaba antes de que la ruta pudiera
seguir siendo modificada por splitting, mientras su evento de salida — mismo lote, fecha
posterior — seguía pendiente esperando su turno).

La solución: solo se sellan eventos con fecha anterior a `storageEventWatermark`, calculada
como la MÁS TEMPRANA entre tres cosas — el inicio de la ventana del ciclo, el ingreso de
cualquier lote en reintento (`carryoverBatches`), y **el evento más antiguo de cualquier ruta
que siga en `frenteCaliente`**. Esta última es la clave: mientras una ruta siga siendo
modificable por splitting, NINGUNO de sus eventos es seguro de sellar, ni siquiera el más
temprano.

## 4. Flujo de un ciclo (`Scheduler.executePlanningCycle`), resumido

```
1. Consumir lotes (reintentos primero, luego nuevos) de la ventana [windowStart, windowEnd)
2. Ejecutar GA + Tabú sobre esos lotes (línea base de almacén aplicada solo acá)
3. Acumular rutas nuevas sobre una copia superficial de currentSolution
   → entran a frenteCaliente + se encolan sus eventos como pendientes
4. applyCapacityAwareSplitting (divide envíos si algún vuelo quedó sobre capacidad)
   → refreshFrenteCalienteAfterSplitting sincroniza: rutas peladas/absorbidas se actualizan
     o se retiran (con sus eventos pendientes correspondientes), sub-lotes nuevos se descubren
5. Validar SOLO las rutas nuevas de este ciclo (no toda la historia)
6. Clasificar lotes sin ruta: reintento / vencido / estructuralmente imposible
7. Fitness incremental:
   a. settleExpiredFlights — sella vuelos ya cerrados
   b. promoteSettledFrenteCaliente — saca del frente caliente lo que ya no es tocable
   c. advanceStorageWatermark — sella eventos de almacén hasta donde es seguro
   d. currentFitness — recalcula el total (sellado + frente caliente fresco)
```

## 5. Aplica igual a los tres escenarios

`DAY_TO_DAY`, `PERIOD_SIMULATION` y `COLLAPSE_SIMULATION` usan la **misma** clase `Scheduler`
(vía `SchedulerFactory.createGATSScheduler`, solo cambian Ta/Sa/K) — ninguno de los cambios de
este documento tiene una rama por escenario. Verificado por código:

- **D2D**: la carga manual de envíos (`addShipment`) alimenta la misma `shipmentQueue` y pasa
  por el mismo `executePlanningCycle`. No hay atajo que lo salte.
- **Ninguna simulación "resume" desde MySQL cargando rutas históricas fuera del ciclo normal**
  (revisado en `SimulationService.startOrJoinSimulation`): "unirse" solo ocurre si hay un
  controlador ya corriendo en memoria; si no, siempre arranca un `Scheduler` nuevo desde cero.
  Esto importa porque el tracker vive solo en memoria — si algo cargara rutas por otro lado, las
  ignoraría silenciosamente. No pasa en ningún camino encontrado.
- **Cancelación de vuelo / replanificación de emergencia** (`Replanner`, común a los tres)
  reemplaza la solución completa vía `Scheduler.updateSolution()`, el único punto de entrada
  que cambia `currentSolution` fuera del ciclo normal — ya blindado: resetea el tracker y
  devuelve TODAS las rutas de la nueva solución al frente caliente, así el ciclo siguiente las
  reclasifica bien.
- **D2D corre indefinidamente** (sin fin natural a los 5 días de PERIOD) — su volumen por
  sesión suele ser bajo (carga manual), pero si una demo queda corriendo mucho tiempo, el mismo
  techo de costo acotado por ciclo lo protege igual.

Verificado empíricamente para COLLAPSE (el caso más exigente): 90 minutos reales desde
20-dic-2027 (volumen real ~14,000/día), 120 ciclos, 85,291 rutas acumuladas, 0 errores, 0
advertencias de presupuesto excedido, costo extra por ciclo constante en ~110-130ms.

## 6. Pendiente conocido (no resuelto en esta pasada)

**`applyCapacityAwareSplitting`** sigue recorriendo `solution.getRoutes().values()` completo
cada ciclo (para construir `usedByFlight`/`batchesByFlight`, detectar vuelos sobre-capacidad y
verificar espacio libre). Es la misma enfermedad que se corrigió en `evaluate()`, pero acá no
se tocó a propósito: es lógica densa (despegue de exceso, reubicación en directos y con
escalas, generación de sub-lotes) con mucho estado ya cuidadosamente ordenado — arreglarla
apurado arriesgaba un bug real de ruteo (maletas perdidas o mal contadas), no solo una cifra de
reporte.

En la verificación de 90 min (85,000 rutas) no se volvió un problema visible — es un recorrido
simple sin sort ni multi-pasada, mucho más barato que lo que sí se arregló — pero seguirá
creciendo linealmente con el tiempo simulado. El mismo criterio "frente caliente" que ya existe
(`r.getFlights().get(0).departureTime().isBefore(windowStart)` → no tocar) sugiere el camino:
construir esos mapas solo desde `frenteCaliente` + el `activeFlightLoad` del tracker, ambos ya
acotados, en vez de la solución completa.

## 7. Archivos tocados

- `logic/SolutionEvaluator.java` — fórmulas extraídas a métodos reutilizables, orden de eventos
  centralizado
- `logic/AccumulatedFitnessTracker.java` — nuevo, tracking incremental
- `model/Solution.java` — copia superficial (rutas inmutables, no hace falta reconstruirlas)
- `model/StorageEvent.java` — `CHRONOLOGICAL_ORDER` centralizado (con desempate ARRIVAL antes
  que DEPARTURE en el mismo instante)
- `execution/Scheduler.java` — `frenteCaliente`, integración del tracker, marca de agua,
  `updateSolution()` blindado
- `monitoring/StorageInventoryService.java` — usa el mismo `CHRONOLOGICAL_ORDER` (antes tenía
  su propia copia de la misma regla)
- `validation/RouteValidator.java`, `monitoring/CapacityMonitor.java` — mismo `CHRONOLOGICAL_ORDER`
- `execution/SchedulerIncrementalFitnessTest.java` — nuevo, test de invariante (fitness
  incremental == recálculo completo desde cero, ciclo a ciclo, en un escenario con asentamiento,
  splitting y reintentos)
