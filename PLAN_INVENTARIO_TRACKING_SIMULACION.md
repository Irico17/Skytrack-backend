# Plan de inventario y tracking de maletas

## Objetivo

Hacer que la capacidad/ocupacion de aeropuertos represente las maletas presentes en cada almacen en el tiempo simulado actual, no un maximo agregado de toda la solucion. El frontend debe recibir actualizaciones continuas mientras el reloj simulado avanza, independiente de que el planificador solo ejecute cada Sa minutos reales.

## Problema actual

- `CapacityMonitor.calculateStorageOccupancy(solution)` calcula ocupacion maxima historica por aeropuerto.
- `SimulationService` envia ese maximo como `currentBags`, aunque no es el estado actual.
- `AssignedRoute` solo genera eventos de llegada al destino de cada vuelo y salida en escalas, pero no representa bien ingreso inicial, salida del origen ni retiro final.
- El frontend solo actualiza aeropuertos cuando llega `CYCLE_UPDATE`; entre ciclos los aviones se mueven, pero los almacenes quedan congelados.

## Diseno propuesto

1. Mantener `CapacityMonitor` para metricas agregadas: pico, promedio, cuellos de botella.
2. Agregar un servicio de inventario temporal en backend que calcule ocupacion por aeropuerto para un `ZonedDateTime` dado.
3. Completar la lista de eventos de almacenamiento por ruta:
   - ingreso del lote al almacen origen;
   - salida del origen al primer vuelo;
   - llegada a cada aeropuerto de vuelo;
   - salida de cada escala hacia el siguiente vuelo;
   - entrega/retiro final en destino.
4. Enviar un nuevo mensaje WebSocket `STORAGE_UPDATE` cada segundo real durante la simulacion.
5. Enviar tambien `airportCapacities` corregido en cada `CYCLE_UPDATE` usando inventario actual.
6. En el frontend, consumir `STORAGE_UPDATE` igual que `CYCLE_UPDATE.airportCapacities` y actualizar `airport.occupancy/status`.

## Primer corte implementable

- Crear `StorageInventoryService` con `calculateCurrentCapacities(solution, currentTime)`.
- Crear `StorageUpdateDTO`.
- Agregar metodo `onStorageUpdated` al listener y al `SimulationWebSocketHandler`.
- Hacer que `SimulationController` emita updates periodicos durante la espera entre ciclos y al terminar cada ciclo.
- Actualizar tipos frontend y `useSimulation.ts` para procesar `STORAGE_UPDATE`.

## Pendientes despues del primer corte

- Persistir un ledger/event stream de inventario para modo dia a dia real.
- Hacer que cancelaciones futuras eliminen instancias proyectadas de vuelos por fecha, no solo vuelos base.
- Ajustar la logica de retiro final si el dominio define un tiempo de permanencia en destino en vez de retiro inmediato.
