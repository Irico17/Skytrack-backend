# Plan de cambios de simulacion

## Estado encontrado

- La simulacion de 5 dias cargaba los envios del rango, pero despues `SimulationController.prepareData()` recortaba a los primeros 500 lotes.
- El orden de esos primeros lotes dependia del orden de archivos en `_envios_preliminar_`, por eso podia verse una planificacion concentrada en pocos aeropuertos.
- El selector del frontend solo permitia fecha; el backend interpretaba la simulacion desde `00:00 UTC`.
- El boton `Completar y Ver Resultados` no completa una simulacion real de 5 dias: en el hook actual esta protegido para no aplicar a `mode === '5day'`.
- El boton `Replanificar Rutas` no dispara una replanificacion real en modos backend; la replanificacion real ocurre al cancelar un vuelo especifico.
- `Reiniciar Simulacion` si detiene la simulacion activa y limpia el estado de la UI; funcionalmente es el boton de cancelar/resetear.

## Cambios aplicados ahora

- Ordenar globalmente los envios por `ingressTime` despues de cargarlos.
- Filtrar `DAY_TO_DAY` por una ventana de 24 horas desde la fecha/hora elegida.
- Filtrar `PERIOD_SIMULATION` por una ventana de 5 dias desde la fecha/hora elegida.
- Quitar el recorte de 500 lotes en `PERIOD_SIMULATION`, para que planifique todos los lotes de la ventana seleccionada.
- Mantener el limite de 100 lotes en `DAY_TO_DAY`, porque ese modo es transaccional y rapido.
- Mantener el limite de 2000 lotes en `COLLAPSE_SIMULATION`, porque es una prueba de estres y puede saturar memoria/CPU si toma todo el historico.
- Aceptar `startDateTime` en backend y frontend, manteniendo `startDate` por compatibilidad.
- Proyectar el plan de vuelos desde la fecha/hora seleccionada, no siempre desde medianoche.

## Recomendacion de UX para botones

Mantener:

- `Iniciar`: debe arrancar la simulacion con la fecha/hora seleccionada.
- `Pausar`: debe pausar la simulacion activa, pero necesita mostrar luego `Reanudar` en vez de volver a mostrar `Iniciar`.
- `Reiniciar Simulacion`: conviene renombrarlo a `Cancelar Simulacion` cuando hay una simulacion activa, y a `Limpiar Vista` cuando no hay simulacion activa.
- `Cancelar Vuelo`: conservarlo solo en operacion dia a dia, porque ahi si dispara replanificacion real.
- `Cargar Datos`: conservarlo deshabilitado durante simulacion.

Quitar u ocultar por ahora:

- `Replanificar Rutas` global: agrega complejidad y hoy no ejecuta una replanificacion real de backend. La replanificacion real debe vivir dentro del flujo de `Cancelar Vuelo`.
- `Completar y Ver Resultados` en simulacion real de 5 dias: hoy no hace fast-forward real. Debe ocultarse hasta implementar un endpoint backend tipo `POST /api/simulations/{id}/finish-now` o `fast-forward`.

## Plan recomendado siguiente

1. Agregar estado `isPaused` en `useSimulation` para diferenciar pausado vs detenido.
2. Exponer accion `resume()` desde el hook y usarla en `TopBar`/`LeftSidebar`.
3. Cambiar el boton principal a tres estados: `Iniciar`, `Pausar`, `Reanudar`.
4. Cambiar `Reiniciar Simulacion` a `Cancelar Simulacion` si hay una simulacion activa o pausada.
5. Ocultar `Replanificar Rutas` en modos backend y dejar solo `Cancelar Vuelo` para replanificacion real.
6. Ocultar `Completar y Ver Resultados` en `5day` hasta que exista soporte backend real.
7. Si se quiere fast-forward real, crear endpoint backend que ejecute ciclos restantes sin esperar `Sa`, exporte resultados y emita `SIMULATION_FINISHED`.
8. Agregar una confirmacion modal para cancelar simulacion, porque detiene el thread activo y limpia resultados parciales.

## Riesgos

- Planificar todos los lotes de una ventana de 5 dias puede ser mucho mas pesado que planificar 500. En la data actual, una ventana de 5 dias tiene alrededor de 10 mil lotes. Si la VM se queda corta, conviene hacer procesamiento por ventanas mas pequenas o paginar internamente por ciclo.
- El frontend puede recibir muchos vuelos/rutas y volverse pesado si se renderiza todo sin filtros. Conviene mostrar rutas con maletas asignadas por defecto y dejar vuelos sin carga como filtro opcional desactivado.
