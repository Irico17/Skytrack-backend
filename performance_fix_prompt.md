# Prompt de Implementación: Optimización de Rendimiento del Mapa WorldMap

## Contexto del Proyecto

Aplicación React (Vite + TypeScript) de simulación de logística aérea. El mapa (`WorldMap.tsx`) muestra aeropuertos como SVG y aviones animados en un `<canvas>` overlay. El estado de simulación viene por WebSocket y se gestiona en `useSimulation.ts`. El componente raíz es `App.tsx`.

**Archivos principales a modificar:**
- `src/app/components/WorldMap.tsx` (1220 líneas, 47KB)
- `src/app/hooks/useSimulation.ts` (1068 líneas, 42KB)
- `src/app/App.tsx` (501 líneas, 23KB)

---

## BUG 1: Aviones desaparecen / glitchean

### Traza del flujo actual que causa el bug

```
App.tsx L82-88: setInterval cada 100ms
  → setMapSimulationTime(next)           ← dispara re-render de App
  → WorldMap recibe simClock como prop   ← nuevo Date object cada 100ms

WorldMap.tsx L572-616: useMemo(activeFlightDots)
  deps: [simClock, activeFlightBagsById, flightPlanGeometry, maxFlightDuration, viewBox]
  → Recalcula TODOS los dots activos
  → L577-579: Viewport culling DENTRO del useMemo (depende de viewBox)
  → L588: if (now > f.arr) continue;  ← descarta vuelos que "ya aterrizaron"
  → L597: if (cx < vx0 || cx > vx1 || ...) continue; ← descarta fuera de viewport

WorldMap.tsx L629-688: useEffect del canvas
  deps: [filteredFlightDots, hasBackendFlightData, toggles.showRoutes, viewBox, canvasSizeVersion]
  → L646: ctx.clearRect(...)  ← LIMPIA todo el canvas
  → L647: flightHitTargetsRef.current = [] ← VACÍA hit targets
  → L649: if (filteredFlightDots.length === 0) return; ← SALE SIN PINTAR si no hay dots
  → Luego pinta los dots
```

### Causa raíz A: viewBox en las dependencias del useMemo

Cuando el usuario hace pan/zoom, `viewBox` cambia. Esto invalida `activeFlightDots`, que depende de `viewBox` (L616). El recálculo genera un nuevo array. Mientras React procesa el render, el canvas se limpia (L646) pero los nuevos dots pueden no estar listos si hay un frame intermedio.

**Más crítico:** cuando el usuario colapsa un panel en `App.tsx` (L209: `{!hidePanels && !leftCollapsed && ...}`), el contenedor del mapa cambia de tamaño → `ResizeObserver` (L325) dispara → `canvasSizeVersion` cambia → el canvas `useEffect` se ejecuta con `filteredFlightDots` del frame anterior → **si entre el resize y el recálculo de dots hay un frame vacío, el canvas queda limpio por un instante visible**.

### Causa raíz B: Salto del reloj tras pausa de WebSocket

```
useSimulation.ts L508-571: Playback tick (cada 50ms)
  → L515: renderAtMs = Date.now() - 500ms (retraso de buffer)
  → L535-537: Limpia frames viejos: while (buffer[1].receivedAtMs <= renderAtMs) buffer.shift()
```

Si el WebSocket se desconecta temporalmente (cambio de pestaña, red lenta):
1. El buffer deja de recibir frames → el playback tick repite el último frame
2. `simClock` se congela en el último valor del buffer
3. Cuando el WS se reconecta, llega un frame con `simulatedMs` mucho mayor
4. Los frames viejos se limpian (L535-537), solo queda el nuevo
5. `simClock` **salta** al nuevo tiempo → `now > f.arr` para muchos vuelos → **desaparecen de golpe**

### Causa raíz C: `setAirports([])` en start() borra y recarga innecesariamente

```
useSimulation.ts L688: setAirports([])  ← BORRA airports
useSimulation.ts L708-711: fetchAirports().then(setAirports)  ← RE-CARGA (ya estaban cargados desde L384-390)
```

Los airports se cargan al cambiar de modo (L384-390). Pero `start()` los borra (L688) y los recarga. Esto causa un frame donde airports es `[]`, lo que afecta a `airportById` (L464-468) → `flightPlanGeometry` se recalcula sin airports → devuelve `[]` → **aviones desaparecen hasta que airports recarga**.

### Correcciones requeridas

**Corrección 1A — Sacar viewBox de las dependencias del useMemo de `activeFlightDots`:**

En `WorldMap.tsx`, el `useMemo` de `activeFlightDots` (L572-616) NO debe depender de `viewBox`. Mover el viewport culling al `useEffect` del canvas (L629-688) o al rAF loop (ver Bug 3). El `useMemo` calcula TODOS los dots sin filtrar por viewport. El filtro por viewport se aplica solo al momento de pintar:

```typescript
// WorldMap.tsx — useMemo SIN viewport culling
const activeFlightDots = useMemo(() => {
  if (!simClock || flightPlanGeometry.length === 0) return [];
  const now = simClock.getTime();
  const dots: FlightDot[] = [];
  const startIndex = lowerBoundDeparture(flightPlanGeometry, now - maxFlightDuration);
  const endIndex = upperBoundDeparture(flightPlanGeometry, now);
  for (let i = startIndex; i < endIndex; i += 1) {
    const f = flightPlanGeometry[i];
    if (now > f.arr) continue;
    const t = (now - f.dep) / f.duration;
    if (t < 0 || t > 1) continue;
    // Calcular posición SIN viewport culling
    const { ox, oy, dx, dy, cpx, cpy } = f;
    const cx = (1-t)*(1-t)*ox + 2*(1-t)*t*cpx + t*t*dx;
    const cy = (1-t)*(1-t)*oy + 2*(1-t)*t*cpy + t*t*dy;
    const tanX = 2*(1-t)*(cpx - ox) + 2*t*(dx - cpx);
    const tanY = 2*(1-t)*(cpy - oy) + 2*t*(dy - cpy);
    const angle = Math.atan2(tanY, tanX) * (180 / Math.PI);
    const bags = activeFlightBagsById.get(f.flightId);
    const hasBags = bags !== undefined && bags.bagsCount > 0;
    const meetsSla = hasBags ? bags!.meetsSla : false;
    const color = hasBags ? (meetsSla ? '#4DA6FF' : '#FFC857') : '#3A4A5E';
    dots.push({ flightId: f.flightId, cx, cy, color, t, angle, pathD: f.pathD,
      bagsCount: hasBags ? bags!.bagsCount : 0, originId: f.originId,
      destinationId: f.destinationId, hasBags, meetsSla, ox, oy, dx, dy, cpx, cpy });
  }
  return dots;
}, [simClock, activeFlightBagsById, flightPlanGeometry, maxFlightDuration]);
//                                                       ^^^ viewBox ELIMINADO
```

En el canvas paint (el `useEffect` o el rAF loop), antes de pintar cada dot, aplicar el viewport culling:

```typescript
// Dentro del loop de pintado:
const pad = Math.max(viewBox.w, viewBox.h) * 0.08;
const vx0 = viewBox.x - pad, vx1 = viewBox.x + viewBox.w + pad;
const vy0 = viewBox.y - pad, vy1 = viewBox.y + viewBox.h + pad;

for (const dot of filteredFlightDots) {
  if (dot.cx < vx0 || dot.cx > vx1 || dot.cy < vy0 || dot.cy > vy1) continue; // culling al pintar
  const x = toCanvasX(dot.cx);
  const y = toCanvasY(dot.cy);
  drawPlaneMarker(ctx, x, y, ...);
}
```

**Corrección 1B — No borrar airports en start():**

En `useSimulation.ts` L688, reemplazar `setAirports([])` con un comentario o eliminar la línea. Los airports ya están cargados desde el `useEffect` de L384-390 y NO cambian entre ejecuciones:

```typescript
// useSimulation.ts start() — ANTES:
setAirports([]);  // ← ELIMINAR ESTA LÍNEA

// DESPUÉS: no tocar airports, ya están cargados
```

También en el try block, cambiar la lógica de fetch de airports para solo cargar si están vacíos:

```typescript
// ANTES (L708-711):
const airportsPromise = fetchAirports()
  .then(backendAirports => {
    setAirports(mapAirports(backendAirports));
    return backendAirports;
  });

// DESPUÉS: Solo re-fetch si no hay airports en state
const airportsPromise = airports.length > 0
  ? Promise.resolve(airports)
  : fetchAirports()
      .then(backendAirports => {
        const mapped = mapAirports(backendAirports);
        setAirports(mapped);
        return mapped;
      });
```

> **IMPORTANTE:** Esto requiere añadir `airports` al closure de `start`. Agregar `airports.length` como referencia via un ref para evitar que `airports` sea dependency del useCallback:
> ```typescript
> const airportsLoadedRef = useRef(false);
> // Actualizar en el useEffect L384:
> useEffect(() => {
>   if (isBackendMode(mode)) {
>     fetchAirports().then(data => {
>       setAirports(mapAirports(data));
>       airportsLoadedRef.current = true;
>     });
>   }
> }, [mode]);
> // En start():
> const airportsPromise = airportsLoadedRef.current
>   ? Promise.resolve(null)  // ya cargados, no re-fetch
>   : fetchAirports().then(data => { setAirports(mapAirports(data)); airportsLoadedRef.current = true; });
> ```

---

## BUG 2: Carga inicial lenta ("Cargando datos...")

### Traza del flujo actual

```
Usuario hace click en "Iniciar" → start() se ejecuta:

T+0ms:   setIsRunning(true), setAirports([]), resets...     ← airports BORRADOS
T+0ms:   setEvents("Cargando aeropuertos y plan de vuelos...")
T+0ms:   fetchAirports() lanzado                            ← HTTP request #1
T+0ms:   fetchFlightPlan() lanzado en paralelo               ← HTTP request #2
T+?ms:   await airportsPromise                               ← BLOQUEA ~200-800ms
T+?ms:   setAirports(mapped)                                 ← airports disponibles
T+?ms:   await startSimulation()                             ← BLOQUEA ~200-500ms (HTTP request #3)
T+?ms:   ws.connect()                                        ← WS handshake ~100ms
T+?ms:   getSimulationStatus() lanzado (no-await)            ← HTTP request #4
```

**Tiempo total bloqueante:** fetchAirports (~500ms) + startSimulation (~300ms) ≈ **800ms mínimo** donde la UI muestra "Cargando" sin progreso.

### Correcciones requeridas

**Corrección 2A — No borrar airports (ya cubierto en 1B):**

Al no llamar `setAirports([])` en L688, si los airports ya están cargados (del useEffect L384-390), el `airportsPromise` resuelve instantáneamente → se ahorra ~200-800ms.

**Corrección 2B — Feedback de progreso por fase:**

Añadir eventos de progreso entre cada paso de carga para que el usuario sepa qué está pasando:

```typescript
// En start(), después de cada paso resuelto:
setEvents(prev => [{
  id: `progress-airports-${Date.now()}`,
  type: 'info',
  message: '✓ Aeropuertos cargados',
  time: new Date(), severity: 'info',
}, ...prev.slice(0, 19)]);

// Después de await airportsPromise:
setEvents(prev => [{
  id: `progress-sim-${Date.now()}`,
  type: 'info',
  message: 'Iniciando motor de simulación...',
  time: new Date(), severity: 'info',
}, ...prev.slice(0, 19)]);

// Después de ws.connect():
setEvents(prev => [{
  id: `progress-ws-${Date.now()}`,
  type: 'info',
  message: '✓ Conexión WebSocket establecida',
  time: new Date(), severity: 'info',
}, ...prev.slice(0, 19)]);
```

---

## BUG 3: Mapa no es fluido (baja tasa de frames)

### Traza del flujo actual (cadena de actualización del canvas)

```
useSimulation.ts L511: setInterval cada 50ms (PLAYBACK_TICK_MS)
  → L541: setSimClock(nextDate)          ← React state update #1
  → React encola re-render

App.tsx L78-79: useEffect([displayedSimulationTime])
  → latestMapClockRef.current = displayedSimulationTime

App.tsx L82-88: setInterval cada 100ms
  → setMapSimulationTime(next)           ← React state update #2 (throttle extra)
  → React encola re-render de App

App.tsx L252: <WorldMap simClock={mapSimulationTime} />
  → WorldMap recibe nueva prop simClock  ← React re-render #3

WorldMap.tsx L572-616: useMemo(activeFlightDots)
  → Recalcula ~1000+ dots (cuadrática Bézier para cada uno)

WorldMap.tsx L619-622: useMemo(filteredFlightDots)
  → Filtra por flightFilter

WorldMap.tsx L629-688: useEffect del canvas
  → ctx.clearRect + repinta todo         ← AQUÍ se ve el frame nuevo
```

**Resultado:** El canvas se actualiza como máximo cada **100ms (10 FPS)** debido al throttle de `mapSimulationTime` en App.tsx. Además:
- Cada actualización genera 3+ state updates en cascada
- El `useEffect` del canvas corre DESPUÉS del commit de React (post-paint), causando 1 frame de retraso
- El useMemo de `activeFlightDots` itera ~5000+ vuelos del flight plan en cada frame

### Corrección requerida: rAF loop para el canvas

Reemplazar la cadena `simClock state → useMemo → useEffect` con un `requestAnimationFrame` loop que lee datos de refs y pinta directamente:

**Paso 1: Añadir `simClockRef` al hook useSimulation**

```typescript
// useSimulation.ts — añadir después de L242:
const simClockRef = useRef<Date>(today);

// En el playback tick (L539-541), SIEMPRE actualizar el ref:
const nextDate = new Date(renderSimMs);
simClockRef.current = nextDate;  // ← instantáneo, sin re-render

// Para los paneles UI, throttlear el state update:
setSimulationTime(nextDate);  // mantener (paneles lo necesitan)
setSimClock(prev => Math.abs(prev.getTime() - renderSimMs) < 1 ? prev : nextDate);  // mantener
```

Exponer `simClockRef` en el return del hook:

```typescript
// useSimulation.ts — return:
return {
  ...existente,
  simClockRef,  // ← NUEVO
};
```

**Paso 2: Pasar simClockRef a WorldMap desde App.tsx**

```tsx
// App.tsx — en WorldMapProps:
<WorldMap
  ...existente
  simClockRef={simulation.simClockRef}  // ← NUEVO
/>
```

Eliminar el timer de 100ms de mapSimulationTime (L82-88) y las líneas L75-92 completas. Pasar `displayedSimulationTime` directamente como `simClock` para tooltips y fallback:

```tsx
// App.tsx — ELIMINAR estas líneas (L75-92):
// const latestMapClockRef = ...
// const [mapSimulationTime, setMapSimulationTime] = ...
// React.useEffect timers...

// Reemplazar L252:
<WorldMap
  ...
  simClock={displayedSimulationTime}     // para tooltips y SVG fallback
  simClockRef={simulation.simClockRef}   // para animación canvas 60fps
/>
```

**Paso 3: Añadir simClockRef al interface de WorldMap y crear rAF loop**

```typescript
// WorldMap.tsx — añadir a WorldMapProps (L73-90):
interface WorldMapProps {
  ...existente,
  simClockRef?: React.RefObject<Date>;  // ← NUEVO
}
```

**Paso 4: Reemplazar useEffect del canvas con rAF loop**

Reemplazar el `useEffect` de L629-688 con:

```typescript
// WorldMap.tsx — NUEVO rAF loop (reemplaza L629-688)

// Refs que el rAF lee sin causar re-render:
const viewBoxRef = useRef(viewBox);
useEffect(() => { viewBoxRef.current = viewBox; }, [viewBox]);

const filteredDotsRef = useRef(filteredFlightDots);
useEffect(() => { filteredDotsRef.current = filteredFlightDots; }, [filteredFlightDots]);

const showRoutesRef = useRef(toggles.showRoutes);
useEffect(() => { showRoutesRef.current = toggles.showRoutes; }, [toggles.showRoutes]);

const flightFilterRef = useRef(flightFilter);
useEffect(() => { flightFilterRef.current = flightFilter; }, [flightFilter]);

// El rAF loop que pinta a 60 FPS:
useEffect(() => {
  const canvas = flightCanvasRef.current;
  if (!canvas || !hasBackendFlightData) return;

  let frameId: number;
  let lastClockMs = 0;

  const paint = () => {
    frameId = requestAnimationFrame(paint);

    // 1. Leer reloj del ref (no del state, no causa re-render)
    const clockDate = simClockRef?.current ?? simClock;
    const nowMs = clockDate.getTime();
    
    // Throttle: solo repintar si el reloj avanzó al menos 15ms o viewBox cambió
    // (evita pintar frames idénticos)
    const vb = viewBoxRef.current;
    if (Math.abs(nowMs - lastClockMs) < 15) return; // 15ms = ~66fps max
    lastClockMs = nowMs;

    // 2. Obtener canvas context y dimensiones
    const rect = canvas.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) return;
    
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const width = Math.max(1, Math.floor(rect.width * dpr));
    const height = Math.max(1, Math.floor(rect.height * dpr));
    if (canvas.width !== width || canvas.height !== height) {
      canvas.width = width;
      canvas.height = height;
    }
    
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, rect.width, rect.height);

    // 3. Calcular dots activos INLINE (sin useMemo, lee de refs)
    const dots = filteredDotsRef.current;
    if (dots.length === 0) {
      flightHitTargetsRef.current = [];
      return;
    }

    // 4. Viewport culling y pintado
    const toCanvasX = (x: number) => (x - vb.x) / vb.w * rect.width;
    const toCanvasY = (y: number) => (y - vb.y) / vb.h * rect.height;
    const pad = Math.max(vb.w, vb.h) * 0.08;
    const vx0 = vb.x - pad, vx1 = vb.x + vb.w + pad;
    const vy0 = vb.y - pad, vy1 = vb.y + vb.h + pad;
    const canvasZoomLevel = BASE_W / vb.w;
    const routeWidthScale = Math.min(1.7, 1 + Math.max(0, canvasZoomLevel - 1) * 0.16);

    // Filtrar dots visibles en viewport
    const visibleDots = dots.filter(d => 
      d.cx >= vx0 && d.cx <= vx1 && d.cy >= vy0 && d.cy <= vy1
    );

    // 5. Pintar rutas
    if (showRoutesRef.current) {
      ctx.save();
      ctx.lineCap = 'round';
      ctx.lineJoin = 'round';
      ctx.setLineDash([]);
      ctx.globalAlpha = 0.035;
      ctx.lineWidth = 1.55 * routeWidthScale;
      for (const color of ['#4DA6FF', '#FFC857']) {
        ctx.strokeStyle = color;
        drawRouteBatch(ctx, visibleDots, color, toCanvasX, toCanvasY);
      }
      ctx.setLineDash([4, 10]);
      ctx.globalAlpha = 0.64;
      ctx.lineWidth = 0.68 * routeWidthScale;
      for (const color of ['#4DA6FF', '#FFC857']) {
        ctx.strokeStyle = color;
        drawRouteBatch(ctx, visibleDots, color, toCanvasX, toCanvasY);
      }
      ctx.restore();
    }

    // 6. Pintar aviones
    const denseMode = visibleDots.length > 2500 && canvasZoomLevel < 1.7;
    const hitTargets: FlightHitTarget[] = [];
    for (const dot of visibleDots) {
      const x = toCanvasX(dot.cx);
      const y = toCanvasY(dot.cy);
      drawPlaneMarker(ctx, x, y, dot.angle, dot.color, dot.hasBags, denseMode, canvasZoomLevel);
      if (dot.hasBags) hitTargets.push({ x, y, dot });
    }
    flightHitTargetsRef.current = hitTargets;
  };

  frameId = requestAnimationFrame(paint);
  return () => cancelAnimationFrame(frameId);
}, [hasBackendFlightData, simClock, simClockRef]); 
// ↑ simClock como fallback si simClockRef no existe
// ↑ hasBackendFlightData para montar/desmontar el loop
// ↑ NO incluir viewBox, filteredFlightDots, toggles — se leen de refs
```

> **CRÍTICO:** Las funciones `drawRouteBatch` y `drawPlaneMarker` ya existen (L124-224). No las modifiques. El rAF loop las llama igual que antes.

> **CRÍTICO:** `flightHitTargetsRef` (L265) se sigue actualizando en cada frame. Los mouse events (`getCanvasFlightHit` L289-309) siguen funcionando igual.

> **CRÍTICO:** El `useMemo` de `activeFlightDots` (L572-616) se MANTIENE pero sin viewBox en dependencias. Alimenta `filteredFlightDots` → `filteredDotsRef`. El rAF loop lee de `filteredDotsRef` y aplica viewport culling al pintar.

---

## Resumen de cambios por archivo

### `useSimulation.ts`
1. **L35:** Cambiar `SIMULATION_K = 120` → `SIMULATION_K = 240` (si se aprueba cambio de K)
2. **Después de L242:** Añadir `const simClockRef = useRef<Date>(today);`
3. **Después de L383:** Añadir `const airportsLoadedRef = useRef(false);` y actualizar en el useEffect L384-390
4. **L539:** Añadir `simClockRef.current = nextDate;` antes de `setSimulationTime`
5. **L688:** Eliminar `setAirports([])` — no borrar airports al iniciar
6. **L708-711:** Condicionar fetch de airports a `!airportsLoadedRef.current`
7. **L1065:** Añadir `simClockRef,` al return

### `App.tsx`
1. **L75-92:** Eliminar `latestMapClockRef`, `mapSimulationTime` state, los tres useEffects del timer
2. **L252:** Cambiar `simClock={mapSimulationTime}` → `simClock={displayedSimulationTime}` y añadir `simClockRef={simulation.simClockRef}`

### `WorldMap.tsx`
1. **L73-90:** Añadir `simClockRef?: React.RefObject<Date>` al interface `WorldMapProps`
2. **L256-261:** Añadir `simClockRef` a los props destructurados
3. **L572-616:** Eliminar `viewBox` de las dependencias del `useMemo` de `activeFlightDots`. Eliminar las líneas de viewport culling (L576-579, L597)
4. **L629-688:** Reemplazar el `useEffect` completo con el rAF loop descrito arriba. Añadir los refs intermedios (`viewBoxRef`, `filteredDotsRef`, `showRoutesRef`, `flightFilterRef`) antes del rAF loop.

### `ScenarioType.java` (backend, SOLO si se aprueba K=240)
1. **L38-43:** Cambiar `K=120` → `K=240` en `PERIOD_SIMULATION`

---

## Qué NO modificar

- **NO** modificar `applyAirportCapacities` (L261-294) — las capacidades de aeropuertos se actualizan correctamente
- **NO** modificar el `handle5DayWsMessage` (L415-505) — el procesamiento de WebSocket está correcto
- **NO** modificar `drawPlaneMarker` ni `drawRouteBatch` — las funciones de pintado son correctas
- **NO** modificar los airport markers SVG (L962-1044) — son pocos y necesitan interactividad
- **NO** modificar el playback buffer (L296-338) ni el playback tick (L508-571) excepto añadir la línea de `simClockRef.current`
- **NO** paralelizar `fetchAirports` con `startSimulation` — airports deben existir en state antes de que lleguen WS messages con capacidades
- **NO** tocar las funciones de tooltip (`makeAirportTooltip`, `makeCanvasFlightTooltip`) — funcionan bien
- **NO** modificar el flow de `scheduleSolutionRefresh` — el refresh de solución/shipments es independiente del canvas

## Validación post-implementación

```bash
cd c:\Users\Irico\Documents\DP1\Skytrack-Frontend
npm run build  # Debe compilar sin errores TypeScript
```

### Tests manuales:
1. Iniciar simulación 5 días → la carga debe ser más rápida (sin re-fetch de airports)
2. Durante simulación: hacer zoom/pan rápido → aviones NO deben desaparecer
3. Colapsar panel izquierdo/derecho/inferior → aviones NO deben parpadear
4. Abrir modal de resultados y cerrar → aviones siguen en sus posiciones
5. Dejar simulación corriendo 10+ minutos → aviones NO deben desaparecer gradualmente
6. Verificar que las capacidades de aeropuertos siguen actualizándose (colores de los dots cambian según ocupación)
7. Verificar tooltips de aeropuertos en hover → deben mostrar datos actualizados
8. Verificar click en aviones → debe seleccionar el vuelo correctamente
9. Verificar filtros de vuelos (panel inferior izquierdo del mapa) → deben seguir funcionando
