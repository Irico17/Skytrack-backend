package com.equipo2b.scheduler.logic;

import com.equipo2b.scheduler.model.Airport;
import com.equipo2b.scheduler.model.AssignedRoute;
import com.equipo2b.scheduler.model.Flight;
import com.equipo2b.scheduler.model.ShipmentBatch;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Ocupación parcial de vuelos y almacenes mientras se construye una solución.
 *
 * <p>Modelo time-phased (red tiempo-espacio): cada estancia de maletas en un almacén es un
 * intervalo {@code [from, until)}. El residual duro mira el <em>pico</em> en ese intervalo.
 * A timestamps iguales se aplican ARRIVALs antes que DEPARTUREs — misma convención que
 * {@code StorageEvent.CHRONOLOGICAL_ORDER} / inventario live.</p>
 *
 * <p>ATP = rutas comprometidas en la timeline vía {@link #fromSolution}. El
 * {@link #fromBaseline} solo aporta ocupación constante ya en suelo (p.ej. unrouted).</p>
 */
public final class CapacityContext {
    public static final double HUB_SOFT_LIMIT_RATIO = 0.80;

    /**
     * Holgura provisional al evaluar un hub en BFS cuando aún no se conoce el siguiente
     * despegue: se reserva hasta min(llegada+hold, deadline SLA).
     */
    public static final Duration PROVISIONAL_HUB_HOLD = Duration.ofHours(12);

    /** Misma ventana de recojo que {@code AssignedRoute} (destino final). */
    public static final Duration FINAL_PICKUP_WINDOW = Duration.ofMinutes(10);

    private final Map<String, Integer> flightLoad;
    /** Piso constante (p.ej. unrouted en origen). */
    private final Map<Airport, Integer> baselineFloor;
    /** ARRIVALs time-phased: epochMilli → cantidad que entra. */
    private final Map<Airport, NavigableMap<Long, Integer>> storageArrivals;
    /** DEPARTUREs time-phased: epochMilli → cantidad que sale. */
    private final Map<Airport, NavigableMap<Long, Integer>> storageDepartures;
    /**
     * Pico global cacheado por aeropuerto (baseline + timeline). Sin esto, cada
     * {@link #hasHubCapacity(Airport, int)} del BFS re-escaneaba toda la serie → O(T)
     * por arista y OOM/GC en la VM de 2 GB con 10k+ lotes.
     */
    private final Map<Airport, Integer> peakCache;
    private final boolean hubSoftLimitEnabled;
    private final boolean flightCapacityEnabled;

    private CapacityContext(
            Map<String, Integer> flightLoad,
            Map<Airport, Integer> baselineFloor,
            Map<Airport, NavigableMap<Long, Integer>> storageArrivals,
            Map<Airport, NavigableMap<Long, Integer>> storageDepartures,
            Map<Airport, Integer> peakCache,
            boolean hubSoftLimitEnabled,
            boolean flightCapacityEnabled) {
        this.flightLoad = flightLoad;
        this.baselineFloor = baselineFloor;
        this.storageArrivals = storageArrivals;
        this.storageDepartures = storageDepartures;
        this.peakCache = peakCache;
        this.hubSoftLimitEnabled = hubSoftLimitEnabled;
        this.flightCapacityEnabled = flightCapacityEnabled;
    }

    public static CapacityContext empty() {
        return new CapacityContext(
            new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
            new HashMap<>(), true, true);
    }

    public static CapacityContext fromBaseline(Map<Airport, Integer> storageBaseline) {
        Map<Airport, Integer> floor = new HashMap<>();
        Map<Airport, Integer> peaks = new HashMap<>();
        if (storageBaseline != null) {
            floor.putAll(storageBaseline);
            peaks.putAll(storageBaseline);
        }
        return new CapacityContext(
            new HashMap<>(), floor, new HashMap<>(), new HashMap<>(), peaks, true, true);
    }

    public CapacityContext copy() {
        return new CapacityContext(
            new HashMap<>(flightLoad),
            new HashMap<>(baselineFloor),
            copyDeltas(storageArrivals),
            copyDeltas(storageDepartures),
            new HashMap<>(peakCache),
            hubSoftLimitEnabled,
            flightCapacityEnabled);
    }

    private static Map<Airport, NavigableMap<Long, Integer>> copyDeltas(
            Map<Airport, NavigableMap<Long, Integer>> source) {
        Map<Airport, NavigableMap<Long, Integer>> copy = new HashMap<>();
        for (Map.Entry<Airport, NavigableMap<Long, Integer>> e : source.entrySet()) {
            copy.put(e.getKey(), new TreeMap<>(e.getValue()));
        }
        return copy;
    }

    public CapacityContext withHubSoftLimitRelaxed() {
        return new CapacityContext(
            flightLoad, baselineFloor, storageArrivals, storageDepartures, peakCache,
            false, flightCapacityEnabled);
    }

    public CapacityContext withFlightCapacityRelaxed() {
        return new CapacityContext(
            flightLoad, baselineFloor, storageArrivals, storageDepartures, peakCache,
            hubSoftLimitEnabled, false);
    }

    public boolean hasFlightCapacity(Flight flight, int quantity) {
        Objects.requireNonNull(flight, "flight");
        if (!flightCapacityEnabled) {
            return true;
        }
        int used = flightLoad.getOrDefault(flight.flightId(), 0);
        return flight.capacity() - used >= quantity;
    }

    public int flightResidual(Flight flight) {
        Objects.requireNonNull(flight, "flight");
        if (!flightCapacityEnabled) {
            return flight.capacity();
        }
        return Math.max(0, flight.capacity() - flightLoad.getOrDefault(flight.flightId(), 0));
    }

    /**
     * Residual duro sin intervalo: pico global (baseline + timeline) + qty ≤ capacidad.
     */
    public boolean hasHubCapacity(Airport airport, int quantity) {
        Objects.requireNonNull(airport, "airport");
        return peakOccupancy(airport) + quantity <= airport.storageCapacity();
    }

    /**
     * Residual duro time-phased: max(baseline, pico timeline en intervalo) + qty ≤ cap.
     *
     * <p>Atajo O(1): si el pico <em>global</em> ya cabe, cualquier sub-intervalo también.
     * Solo escanea la serie cuando el hub está cerca del límite (caso caro pero raro).
     */
    public boolean hasHubCapacity(
            Airport airport, int quantity, ZonedDateTime from, ZonedDateTime until) {
        Objects.requireNonNull(airport, "airport");
        Objects.requireNonNull(from, "from");
        if (peakOccupancy(airport) + quantity <= airport.storageCapacity()) {
            return true;
        }
        ZonedDateTime end = until != null && until.isAfter(from)
            ? until
            : from.plus(PROVISIONAL_HUB_HOLD);
        int timed = peakInInterval(airport, from.toInstant().toEpochMilli(), end.toInstant().toEpochMilli());
        return effectiveOccupancy(airport, timed) + quantity <= airport.storageCapacity();
    }

    /**
     * Ocupación efectiva en un intervalo: piso constante MÁS el pico time-phased.
     *
     * <p>Son ADITIVOS, no alternativos: el piso son maletas sin ruta físicamente sentadas en
     * el almacén (ver {@link #fromBaseline}) y la timeline son las que entran/salen por rutas
     * comprometidas. Ocupan el mismo espacio a la vez. Usar {@code max(piso, timeline)}
     * subestimaba la ocupación real y dejaba pasar rutas que sí desbordaban — además de
     * contradecir a {@link #peakOccupancy}, que sí arranca la reproducción desde el piso.</p>
     */
    private int effectiveOccupancy(Airport airport, int timedPeak) {
        return baselineFloor.getOrDefault(airport, 0) + Math.max(0, timedPeak);
    }

    /**
     * Residual usando el pico GLOBAL (todo el horizonte). Conservador: si el almacén se
     * satura en cualquier instante futuro, devuelve poco aunque ahora esté vacío. Preferir
     * {@link #storageResidual(Airport, ZonedDateTime, ZonedDateTime)} cuando se conoce la
     * ventana real de estancia.
     */
    public int storageResidual(Airport airport) {
        Objects.requireNonNull(airport, "airport");
        return Math.max(0, airport.storageCapacity() - peakOccupancy(airport));
    }

    /**
     * Residual time-phased: capacidad libre durante {@code [from, until)} concretamente.
     *
     * <p>Necesario para la admisión en origen: con el residual GLOBAL, un aeropuerto que se
     * satura en cualquier momento del horizonte comprometido rechazaba (o partía en trozos
     * mínimos) todo lote nuevo, aunque en la ventana en que esas maletas van a estar ahí
     * hubiera espacio de sobra. Eso hundía la tasa de asignación sin ganar nada: las maletas
     * rechazadas se quedan igualmente en el almacén de origen, solo que sin ruta.</p>
     */
    public int storageResidual(Airport airport, ZonedDateTime from, ZonedDateTime until) {
        Objects.requireNonNull(airport, "airport");
        if (from == null) {
            return storageResidual(airport);
        }
        // OJO: aquí NO vale el atajo "si el pico global deja hueco, devuelve ese hueco" que sí
        // sirve para los chequeos booleanos. El residual global es solo una COTA INFERIOR del
        // de la ventana, y devolverlo anularía el propósito de este método (un almacén que se
        // satura por la tarde reportaría ~0 libre para una estancia de la mañana). Siempre se
        // mide el intervalo; peakInInterval ya sale en O(1) si el aeropuerto no tiene serie.
        ZonedDateTime end = until != null && until.isAfter(from)
            ? until
            : from.plus(PROVISIONAL_HUB_HOLD);
        int timed = peakInInterval(
            airport, from.toInstant().toEpochMilli(), end.toInstant().toEpochMilli());
        return Math.max(0, airport.storageCapacity() - effectiveOccupancy(airport, timed));
    }

    public boolean isHubNearLimit(Airport airport) {
        return isHubNearLimit(airport, 0);
    }

    public boolean isHubNearLimit(Airport airport, int quantity) {
        Objects.requireNonNull(airport, "airport");
        if (!hubSoftLimitEnabled) {
            return false;
        }
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity must be >= 0, got: " + quantity);
        }
        return peakOccupancy(airport) + quantity
            >= airport.storageCapacity() * HUB_SOFT_LIMIT_RATIO;
    }

    public boolean isHubNearLimit(
            Airport airport, int quantity, ZonedDateTime from, ZonedDateTime until) {
        Objects.requireNonNull(airport, "airport");
        if (!hubSoftLimitEnabled) {
            return false;
        }
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity must be >= 0, got: " + quantity);
        }
        Objects.requireNonNull(from, "from");
        // Si ni el pico global roza el soft, ningún intervalo lo hará.
        if (!isHubNearLimit(airport, quantity)) {
            return false;
        }
        ZonedDateTime end = until != null && until.isAfter(from)
            ? until
            : from.plus(PROVISIONAL_HUB_HOLD);
        int timed = peakInInterval(airport, from.toInstant().toEpochMilli(), end.toInstant().toEpochMilli());
        return effectiveOccupancy(airport, timed) + quantity
            >= airport.storageCapacity() * HUB_SOFT_LIMIT_RATIO;
    }

    public int flightLoad(Flight flight) {
        return flightLoad.getOrDefault(flight.flightId(), 0);
    }

    public int storageOccupancy(Airport airport) {
        return peakOccupancy(airport);
    }

    public Map<String, Integer> flightLoadsView() {
        return Collections.unmodifiableMap(flightLoad);
    }

    public Map<Airport, Integer> storageOccupancyView() {
        Map<Airport, Integer> view = new HashMap<>();
        Set<Airport> airports = new HashSet<>();
        airports.addAll(baselineFloor.keySet());
        airports.addAll(storageArrivals.keySet());
        airports.addAll(storageDepartures.keySet());
        for (Airport airport : airports) {
            view.put(airport, peakOccupancy(airport));
        }
        return Collections.unmodifiableMap(view);
    }

    public void applyRoute(AssignedRoute route) {
        Objects.requireNonNull(route, "route");
        int qty = route.getBatch().quantity();
        for (Flight flight : route.getFlights()) {
            flightLoad.merge(flight.flightId(), qty, Integer::sum);
        }
        applyStayIntervals(route, qty);
    }

    public void removeRoute(AssignedRoute route) {
        Objects.requireNonNull(route, "route");
        int qty = route.getBatch().quantity();
        for (Flight flight : route.getFlights()) {
            flightLoad.computeIfPresent(flight.flightId(), (key, load) -> {
                int next = load - qty;
                return next <= 0 ? null : next;
            });
        }
        applyStayIntervals(route, -qty);
    }

    public boolean pathFitsWarehouseHard(ShipmentBatch batch, List<Flight> path, int quantity) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(path, "path");
        if (path.isEmpty()) {
            return false;
        }
        if (!hasHubCapacity(
                batch.origin(),
                quantity,
                batch.ingressTime(),
                path.get(0).departureTime())) {
            return false;
        }
        for (int i = 0; i < path.size(); i++) {
            Flight flight = path.get(i);
            ZonedDateTime until = (i < path.size() - 1)
                ? path.get(i + 1).departureTime()
                : flight.arrivalTime().plus(FINAL_PICKUP_WINDOW);
            if (!hasHubCapacity(flight.destination(), quantity, flight.arrivalTime(), until)) {
                return false;
            }
        }
        return true;
    }

    public boolean pathFitsHard(ShipmentBatch batch, List<Flight> path, int quantity) {
        if (!pathFitsWarehouseHard(batch, path, quantity)) {
            return false;
        }
        for (Flight flight : path) {
            if (!hasFlightCapacity(flight, quantity)) {
                return false;
            }
        }
        return true;
    }

    public boolean pathFitsHardWithSoft(
            ShipmentBatch batch, List<Flight> path, int quantity, Airport finalDestination) {
        if (!pathFitsHard(batch, path, quantity)) {
            return false;
        }
        if (!hubSoftLimitEnabled) {
            return true;
        }
        for (int i = 0; i < path.size() - 1; i++) {
            Flight flight = path.get(i);
            Airport hub = flight.destination();
            if (hub.equals(finalDestination)) {
                continue;
            }
            ZonedDateTime until = path.get(i + 1).departureTime();
            if (isHubNearLimit(hub, quantity, flight.arrivalTime(), until)) {
                return false;
            }
        }
        return true;
    }

    public static CapacityContext fromSolution(
            Iterable<AssignedRoute> routes,
            Map<Airport, Integer> storageBaseline) {
        CapacityContext ctx = fromBaseline(storageBaseline);
        if (routes != null) {
            for (AssignedRoute route : routes) {
                ctx.applyRoute(route);
            }
        }
        return ctx;
    }

    private void applyStayIntervals(AssignedRoute route, int signedQty) {
        if (signedQty == 0) {
            return;
        }
        ShipmentBatch batch = route.getBatch();
        List<Flight> flights = route.getFlights();
        if (flights.isEmpty()) {
            return;
        }
        addStay(batch.origin(), batch.ingressTime(), flights.get(0).departureTime(), signedQty);
        for (int i = 0; i < flights.size(); i++) {
            Flight flight = flights.get(i);
            ZonedDateTime until = (i < flights.size() - 1)
                ? flights.get(i + 1).departureTime()
                : flight.arrivalTime().plus(FINAL_PICKUP_WINDOW);
            addStay(flight.destination(), flight.arrivalTime(), until, signedQty);
        }
    }

    private void addStay(Airport airport, ZonedDateTime from, ZonedDateTime until, int signedQty) {
        if (from == null || until == null || !until.isAfter(from) || signedQty == 0) {
            return;
        }
        long fromMs = from.toInstant().toEpochMilli();
        long untilMs = until.toInstant().toEpochMilli();
        int absQty = Math.abs(signedQty);
        if (signedQty > 0) {
            mergeDelta(storageArrivals, airport, fromMs, absQty);
            mergeDelta(storageDepartures, airport, untilMs, absQty);
        } else {
            mergeDelta(storageArrivals, airport, fromMs, -absQty);
            mergeDelta(storageDepartures, airport, untilMs, -absQty);
        }
        invalidatePeak(airport);
    }

    private static void mergeDelta(
            Map<Airport, NavigableMap<Long, Integer>> maps,
            Airport airport,
            long key,
            int delta) {
        NavigableMap<Long, Integer> series = maps.computeIfAbsent(airport, a -> new TreeMap<>());
        series.merge(key, delta, Integer::sum);
        Integer value = series.get(key);
        if (value != null && value == 0) {
            series.remove(key);
        }
        if (series.isEmpty()) {
            maps.remove(airport);
        }
    }

    /**
     * Pico global con convención live: en cada instante, ARRIVAL antes que DEPARTURE.
     * Cacheado: el BFS lo consulta millones de veces durante el ciclo 1.
     */
    private int peakOccupancy(Airport airport) {
        Integer cached = peakCache.get(airport);
        if (cached != null) {
            return cached;
        }
        int floor = baselineFloor.getOrDefault(airport, 0);
        NavigableMap<Long, Integer> arrivals = storageArrivals.get(airport);
        NavigableMap<Long, Integer> departures = storageDepartures.get(airport);
        if ((arrivals == null || arrivals.isEmpty()) && (departures == null || departures.isEmpty())) {
            peakCache.put(airport, floor);
            return floor;
        }
        int peak = peakOverTimestamps(floor, arrivals, departures, Long.MIN_VALUE, Long.MAX_VALUE, true);
        peakCache.put(airport, peak);
        return peak;
    }

    private void invalidatePeak(Airport airport) {
        peakCache.remove(airport);
    }

    /**
     * Pico en {@code [fromMs, untilMs)} con ARRIVAL-before-DEPARTURE en cada instante.
     */
    private int peakInInterval(Airport airport, long fromMs, long untilMs) {
        NavigableMap<Long, Integer> arrivals = storageArrivals.get(airport);
        NavigableMap<Long, Integer> departures = storageDepartures.get(airport);
        if ((arrivals == null || arrivals.isEmpty()) && (departures == null || departures.isEmpty())) {
            return 0;
        }
        int occ = occupancyJustBefore(arrivals, departures, fromMs);
        return peakOverTimestamps(occ, arrivals, departures, fromMs, untilMs, false);
    }

    private static int occupancyJustBefore(
            NavigableMap<Long, Integer> arrivals,
            NavigableMap<Long, Integer> departures,
            long fromMs) {
        int occ = 0;
        if (arrivals != null) {
            for (int delta : arrivals.headMap(fromMs, false).values()) {
                occ += delta;
            }
        }
        if (departures != null) {
            for (int delta : departures.headMap(fromMs, false).values()) {
                occ -= delta;
            }
        }
        return Math.max(0, occ);
    }

    private static int peakOverTimestamps(
            int initialOcc,
            NavigableMap<Long, Integer> arrivals,
            NavigableMap<Long, Integer> departures,
            long fromMs,
            long untilMs,
            boolean includeAll) {
        int occ = initialOcc;
        int peak = Math.max(0, occ);

        // Merge de dos series ordenadas sin TreeSet intermedio: en el ciclo 1 cada
        // chequeo BFS/Dijkstra llamaba peakInInterval miles de veces y el TreeSet
        // disparaba GC/RSS hasta OOM-kill (systemd MemoryMax) en la VM de 2 GB.
        Iterator<Map.Entry<Long, Integer>> arrIt = emptyIfNull(
            includeAll
                ? (arrivals != null ? arrivals.entrySet().iterator() : null)
                : (arrivals != null ? arrivals.subMap(fromMs, true, untilMs, false).entrySet().iterator() : null));
        Iterator<Map.Entry<Long, Integer>> depIt = emptyIfNull(
            includeAll
                ? (departures != null ? departures.entrySet().iterator() : null)
                : (departures != null ? departures.subMap(fromMs, true, untilMs, false).entrySet().iterator() : null));

        Map.Entry<Long, Integer> nextArr = arrIt.hasNext() ? arrIt.next() : null;
        Map.Entry<Long, Integer> nextDep = depIt.hasNext() ? depIt.next() : null;

        while (nextArr != null || nextDep != null) {
            long tArr = nextArr != null ? nextArr.getKey() : Long.MAX_VALUE;
            long tDep = nextDep != null ? nextDep.getKey() : Long.MAX_VALUE;
            long t = Math.min(tArr, tDep);
            int arr = 0;
            int dep = 0;
            if (nextArr != null && nextArr.getKey() == t) {
                arr = nextArr.getValue();
                nextArr = arrIt.hasNext() ? arrIt.next() : null;
            }
            if (nextDep != null && nextDep.getKey() == t) {
                dep = nextDep.getValue();
                nextDep = depIt.hasNext() ? depIt.next() : null;
            }
            // ARRIVAL antes que DEPARTURE (igual que StorageEvent.CHRONOLOGICAL_ORDER)
            occ += arr;
            if (occ > peak) {
                peak = occ;
            }
            occ -= dep;
            if (occ < 0) {
                occ = 0;
            }
        }
        return peak;
    }

    private static Iterator<Map.Entry<Long, Integer>> emptyIfNull(
            Iterator<Map.Entry<Long, Integer>> it) {
        return it != null ? it : Collections.emptyIterator();
    }
}
